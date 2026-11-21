package sbirds.links

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.headers.Authorization
import sbirds.auth.{Crypto, SecretBox}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * 兄弟のサービスに OAuth 2.0（認可コード + PKCE）でつなぐ設定. url は画面から開く URL、internalUrl はサーバーから呼ぶ URL（省けば url）.
 * scope は求めるスコープ（"read write" など）.
 */
final case class LinkConfig(provider: String,
                            url: String,
                            internalUrl: String,
                            clientId: String,
                            clientSecret: String,
                            scope: String = "read")

object LinkConfig {

  /**
   * 環境変数 {prefix}_URL・{prefix}_CLIENT_ID・{prefix}_CLIENT_SECRET（3つ揃えたときだけ）と {prefix}_INTERNAL_URL から読む.
   * 3つとも無ければ Right(None)、一部だけなら Left.
   */
  def fromEnv(prefix: String,
              provider: String,
              scope: String = "read",
              env: String => Option[String] = sys.env.get): Either[String, Option[LinkConfig]] = {
    def opt(k: String) = env(s"${prefix}_$k").map(_.trim).filter(_.nonEmpty)
    (opt("URL"), opt("CLIENT_ID"), opt("CLIENT_SECRET")) match {
      case (Some(u), Some(i), Some(s)) =>
        val url = u.stripSuffix("/")
        Right(Some(LinkConfig(provider, url, opt("INTERNAL_URL").map(_.stripSuffix("/")).getOrElse(url), i, s, scope)))
      case (None, None, None) => Right(None)
      case _ => Left(s"${prefix}_URL・${prefix}_CLIENT_ID・${prefix}_CLIENT_SECRET は3つとも設定してください")
    }
  }
}

/** 連携したトークンで呼んだときの失敗. */
enum LinkFailure {

  /** 連携していない. */
  case NotConnected

  /** トークンが失効していた（401. 保存していたトークンは消した）. */
  case Expired

  /** 相手が成功以外の状態を返した. */
  case Rejected(
    status: Int)

  /** つながらなかった・時間切れ. */
  case Unreachable(
    cause: Throwable)
}

/**
 * 兄弟のサービスとの連携（クライアントの側）. 各ユーザーが相手の許可の画面で許可し、受け取ったトークンを box で暗号化して保存する
 * （表は integration_state・integration_token. マイグレーションは各サービス）.
 */
final class LinkClient(val cfg: LinkConfig, http: Client[IO], val store: IntegrationTokenStore, box: SecretBox) {

  def provider: String = cfg.provider

  /** サーバーから呼ぶ相手の URL. */
  def api(path: String): Uri = Uri.unsafeFromString(cfg.internalUrl.stripSuffix("/") + path)

  private def basic = Authorization(BasicCredentials(cfg.clientId, cfg.clientSecret))

  def bearer(token: String): Authorization = Authorization(Credentials.Token(AuthScheme.Bearer, token))

  def token(uid: UUID): IO[Option[String]] = store.token(uid, provider).map(_.flatMap(box.decrypt))

  def connected(uid: UUID): IO[Boolean] = token(uid).map(_.isDefined)

  /** 連携を始める: state と PKCE の verifier を10分保存し、相手の許可の画面の URL を返す. */
  def authorizeUrl(uid: UUID, redirectUri: String, returnTo: String): IO[String] = {
    val state = Crypto.randomToken(24)
    val verifier = Crypto.randomToken(48)
    store.saveState(state, uid, provider, verifier, returnTo, Instant.now().plusSeconds(600)).as(
      Uri.unsafeFromString(s"${cfg.url.stripSuffix("/")}/oauth/authorize").withQueryParams(Map(
        "response_type" -> "code",
        "client_id" -> cfg.clientId,
        "redirect_uri" -> redirectUri,
        "scope" -> cfg.scope,
        "state" -> state,
        "code_challenge" -> Crypto.pkceChallenge(verifier),
        "code_challenge_method" -> "S256"
      )).renderString
    )
  }

  /** 許可の画面からの戻り: state を取り出す（1回だけ. 期限切れなら None）. (ユーザー, verifier, 戻り先). */
  def takeState(state: String): IO[Option[(UUID, String, String)]] = store.takeState(state, provider)

  /** 認可コードをトークンに替えて保存する. */
  def exchange(uid: UUID, code: String, verifier: String, redirectUri: String): IO[Boolean] = {
    val form = UrlForm(
      "grant_type" -> "authorization_code",
      "code" -> code,
      "redirect_uri" -> redirectUri,
      "code_verifier" -> verifier
    )
    http.run(Request[IO](Method.POST, api("/oauth/token")).withEntity(form).putHeaders(basic))
      .use(r =>
        if r.status.isSuccess then r.as[Json].map(_.hcursor.get[String]("access_token").toOption) else IO.pure(None)
      )
      .timeout(15.seconds).handleError(_ => None)
      .flatMap(_.traverse(t => store.saveToken(uid, provider, box.encrypt(t))).map(_.isDefined))
  }

  /** 連携をやめる（相手のトークンも失効させる. 相手に届かなくても、こちらでは消す）. */
  def disconnect(uid: UUID): IO[Unit] =
    token(uid).flatMap(_.traverse_ { t =>
      http.run(Request[IO](Method.POST, api("/oauth/revoke")).withEntity(UrlForm("token" -> t)).putHeaders(basic))
        .use(_ => IO.unit).timeout(10.seconds).handleError(_ => ())
    }) *> store.deleteToken(uid, provider)

  /** 本人のトークンで呼ぶ. 401 ならトークンを消して Expired. 成功のときだけ ok で応答を読む. */
  def call[A](uid: UUID, req: Request[IO], timeout: FiniteDuration = 15.seconds)(
    ok: Response[IO] => IO[A]): IO[Either[LinkFailure, A]] =
    token(uid).flatMap {
      case None => IO.pure(Left(LinkFailure.NotConnected))
      case Some(t) =>
        http.run(req.putHeaders(bearer(t))).use { r =>
          if r.status == Status.Unauthorized then store.deleteToken(uid, provider).as(Left(LinkFailure.Expired))
          else if !r.status.isSuccess then IO.pure(Left(LinkFailure.Rejected(r.status.code)))
          else ok(r).map(Right(_))
        }.timeout(timeout).handleError(e => Left(LinkFailure.Unreachable(e)))
    }
}

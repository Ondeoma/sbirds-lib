package sbirds.oauth

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{`Content-Type`, Authorization, Location}
import sbirds.auth.Crypto

import java.time.Instant
import java.util.UUID

/**
 * OAuth 2.0 の提供側（認可コード + PKCE〈S256〉. RFC 6749・7636・7009）.
 *  - `GET /oauth/authorize`: 許可の画面（ログインしていなければ、ログインしてから戻る）
 *  - `POST /oauth/authorize`: 許可・拒否（CSRF は SameSite=Strict の Cookie と hidden の値の照合で防ぐ）
 *  - `POST /oauth/token`: authorization_code（Host.refresh があれば refresh_token も）. クライアントの認証は Basic
 *  - `POST /oauth/revoke`: トークンの失効
 *
 * 決まっていること（サービスからは変えられない）:
 * 許可は Host.browserSession（Cookie のセッションだけを返すこと. API キーでは許可させない）、redirect_uri は完全一致、PKCE（S256）は必須、
 * コードは 1 回限り、クライアントの秘密は定数時間で比べる、フォームとして読めない本文は 500 にせずエラーの画面・invalid_request にする.
 * サービスごとの違い（同意の画面の文言・スコープ・発行するトークン・監査）は Host で差し込む.
 */
final class OAuthProvider[S](host: OAuthProvider.Host[S]) extends Http4sDsl[IO] {

  import OAuthProvider.*

  private val st = host.settings

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "oauth" / "authorize" => getOauthAuthorize(req)
    case req @ POST -> Root / "oauth" / "authorize" => postOauthAuthorize(req)
    case req @ POST -> Root / "oauth" / "token" => postOauthToken(req)
    case req @ POST -> Root / "oauth" / "revoke" => postOauthRevoke(req)
  }

  private val CsrfCookie = s"${st.service}_oauth_csrf"

  /** 確かめた認可の要求. */
  private final case class Valid(client: OAuthClient, state: Option[String], challenge: String, scopes: List[String])

  private def getOauthAuthorize(req: Request[IO]): IO[Response[IO]] = {
    val param = (k: String) => req.params.get(k).map(_.trim).filter(_.nonEmpty)
    validate(param).flatMap {
      case Left(r) => r
      case Right(v) =>
        host.browserSession(req).flatMap {
          case None =>
            // ログインしてからこの画面に戻る
            val r = Response[IO](Status.Found).putHeaders(
              Location(Uri.unsafeFromString(s"${st.loginPath}?returnTo=${enc(req.uri.renderString)}"))
            )
            IO.pure(if st.loginRedirectNoStore then r.putHeaders("Cache-Control" -> "no-store") else r)
          case Some(s) =>
            val csrf = Crypto.randomToken(24)
            val hidden = HiddenParams.flatMap(k =>
              param(k).map(v => s"""<input type="hidden" name="$k" value="${escape(v)}">""")
            ).mkString
            host.consent(req, v.client, s, v.scopes).flatMap(c =>
              host.page(
                c.title,
                s"""${c.html}
                   |<form method="post" action="/oauth/authorize">$hidden<input type="hidden" name="csrf" value="$csrf">
                   |<div class="actions"><button type="submit" name="decision" value="deny">拒否する</button>
                   |<button type="submit" name="decision" value="allow" class="primary">許可する</button></div></form>""".stripMargin,
                Status.Ok
              )
            ).map(_.addCookie(ResponseCookie(
              CsrfCookie,
              csrf,
              path = Some("/oauth"),
              httpOnly = true,
              secure = st.cookieSecure,
              sameSite = Some(SameSite.Strict),
              maxAge = Some(600)
            )))
        }
    }
  }

  private def postOauthAuthorize(req: Request[IO]): IO[Response[IO]] =
    host.limit(req).as[UrlForm].attempt.flatMap {
      // フォームとして読めない本文は、内部エラー（500）にせず 400 の画面にする
      case Left(_) => errorPage(st.unreadableForm)
      case Right(u) =>
        val p = (k: String) => u.getFirst(k).map(_.trim).filter(_.nonEmpty)
        validate(p).flatMap {
          case Left(r) => r
          case Right(v) =>
            val c = v.client
            val csrfOk = req.cookies.find(_.name == CsrfCookie).exists(ck =>
              p("csrf").exists(x => Crypto.constantTimeEquals(ck.content, x))
            )
            host.decisionSession(req).flatMap {
              case Left(msg) => errorPage(msg)
              case Right(_) if !csrfOk => errorPage(st.csrfExpired)
              case Right(s) if p("decision").contains("allow") =>
                val code = Crypto.randomToken(32)
                host.codes.createCode(
                  Crypto.sha256(code),
                  OAuthCode(c.id, c.redirectUri, host.userId(s), v.challenge, host.codeScope(c, v.scopes)),
                  Instant.now().plusSeconds(st.codeTtlSeconds)
                ) *> host.authorized(req, s, c, v.scopes) *>
                  back(c.redirectUri, "code" -> Some(code), "state" -> v.state)
              case Right(_) => back(c.redirectUri, "error" -> Some("access_denied"), "state" -> v.state)
            }.map(_.removeCookie(ResponseCookie(CsrfCookie, "", path = Some("/oauth"))))
        }
    }

  private def postOauthToken(req: Request[IO]): IO[Response[IO]] =
    basicClient(req).flatMap {
      case None => invalidClient
      case Some(c) =>
        form(req) { f =>
          f.get("grant_type") match {
            case Some("authorization_code") => byCode(req, c, f)
            case Some("refresh_token") if host.refresh.isDefined => byRefresh(req, c, f)
            case g =>
              val supported =
                if host.refresh.isDefined then "grant_type は authorization_code か refresh_token です"
                else "grant_type は authorization_code です"
              g match {
                case None => failure(Status.BadRequest, "invalid_request", st.grantTypeMissing.getOrElse(supported))
                case Some(_) => failure(Status.BadRequest, "unsupported_grant_type", supported)
              }
          }
        }
    }

  private def byCode(req: Request[IO], c: OAuthClient, f: Map[String, String]): IO[Response[IO]] =
    (f.get("code"), f.get("redirect_uri"), f.get("code_verifier")) match {
      case (Some(code), Some(uri), Some(verifier)) if validVerifier(verifier) =>
        host.codes.consumeCode(Crypto.sha256(code)).flatMap {
          case Some(oc) if oc.clientId == c.id && oc.redirectUri == uri && pkceMatches(oc.codeChallenge, verifier) =>
            host.issue(req, c, oc).flatMap(tokenResponse)
          case _ => failure(Status.BadRequest, "invalid_grant", "認可コードが無効です（期限切れ・使用済み・不一致）")
        }
      case _ =>
        failure(Status.BadRequest, "invalid_request", "code・redirect_uri・code_verifier（43〜128文字）を指定してください")
    }

  private def byRefresh(req: Request[IO], c: OAuthClient, f: Map[String, String]): IO[Response[IO]] =
    (host.refresh, f.get("refresh_token").filter(_.nonEmpty)) match {
      case (Some(refresh), Some(rt)) =>
        refresh(req, c, rt, f).flatMap(_.fold(e => failure(e.status, e.error, e.description), tokenResponse))
      case _ => failure(Status.BadRequest, "invalid_request", "refresh_token を指定してください")
    }

  private def postOauthRevoke(req: Request[IO]): IO[Response[IO]] =
    basicClient(req).flatMap {
      case None => invalidClient
      case Some(c) => form(req)(f =>
          f.get("token").filter(_.nonEmpty).traverse_(t => host.revoke(req, c, t)) *> Ok(Json.obj())
            .map(r => if st.revokeNoStore then r.putHeaders("Cache-Control" -> "no-store") else r)
        )
    }

  private def enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

  /** redirect_uri に結果を付けて戻す. */
  private def back(uri: String, params: (String, Option[String])*): IO[Response[IO]] = {
    val q = params.collect { case (k, Some(v)) => s"$k=${enc(v)}" }.mkString("&")
    val r = Response[IO](Status.Found).putHeaders(
      Location(Uri.unsafeFromString(uri + (if uri.contains("?") then "&" else "?") + q))
    )
    IO.pure(if st.backNoStore then r.putHeaders("Cache-Control" -> "no-store") else r)
  }

  private def errorPage(msg: String): IO[Response[IO]] =
    host.page(
      "認可できません",
      s"<h1>認可できません</h1><p>${escape(msg)}</p><p class=\"muted\">連携元のサービスの設定を確認してください。</p>",
      Status.BadRequest
    )

  /** 認可の要求の確認（client_id・redirect_uri が不正ならリダイレクトせずに 400 の画面. それ以外の不正は redirect_uri へエラーを返す）. */
  private def validate(p: String => Option[String]): IO[Either[IO[Response[IO]], Valid]] =
    p("client_id").flatTraverse(host.client).map { client =>
      (client, p("redirect_uri")) match {
        case (None, _) => Left(errorPage("登録されていないクライアントです（client_id）"))
        case (Some(c), r) if !r.contains(c.redirectUri) => Left(errorPage("戻り先（redirect_uri）が登録されたものと一致しません"))
        case (Some(c), _) =>
          val state = p("state")
          if !p("response_type").contains("code") then
            Left(back(c.redirectUri, "error" -> Some("unsupported_response_type"), "state" -> state))
          else if !p("code_challenge_method").contains("S256") || !p("code_challenge").exists(validChallenge) then
            Left(back(
              c.redirectUri,
              "error" -> Some("invalid_request"),
              "error_description" -> Some("PKCE（S256）が必要です"),
              "state" -> state
            ))
          else
            host.scopes(c, p("scope")) match {
              case None => Left(back(
                  c.redirectUri,
                  "error" -> Some("invalid_scope"),
                  "error_description" -> st.invalidScopeDescription,
                  "state" -> state
                ))
              case Some(sc) => Right(Valid(c, state, p("code_challenge").get, sc))
            }
      }
    }

  // ---------------------------------------------------------------- トークンの発行・失効（クライアントの認証は Basic）

  private def failure(status: Status, code: String, desc: String): IO[Response[IO]] =
    IO.pure(Response[IO](status).withEntity(Json.obj("error" -> code.asJson, "error_description" -> desc.asJson))
      .putHeaders("Cache-Control" -> "no-store", "Pragma" -> "no-cache").putHeaders(
        (if status == Status.Unauthorized then
           List[Header.ToRaw]("WWW-Authenticate" -> s"Basic realm=\"${st.service}\"")
         else Nil)*
      ))

  private def invalidClient: IO[Response[IO]] = failure(Status.Unauthorized, "invalid_client", "クライアントの認証に失敗しました")

  private def basicClient(req: Request[IO]): IO[Option[OAuthClient]] =
    req.headers.get[Authorization].collect { case Authorization(BasicCredentials(id, secret)) => (id, secret) }
      .flatTraverse((id, secret) => host.client(id).map(_.filter(c => host.secretMatches(c, secret))))

  private def form(req: Request[IO])(f: Map[String, String] => IO[Response[IO]]): IO[Response[IO]] =
    host.limit(req).as[UrlForm].attempt.flatMap {
      case Left(_) => failure(Status.BadRequest, "invalid_request", "application/x-www-form-urlencoded で送ってください")
      case Right(u) =>
        f(u.values.toList.flatMap((k, vs) => st.formValue(vs.headOption.getOrElse("")).map(k -> _)).toMap)
    }

  private def tokenResponse(t: Token): IO[Response[IO]] =
    Ok(Json.fromFields(
      List(
        Some("access_token" -> t.accessToken.asJson),
        Some("token_type" -> "Bearer".asJson),
        t.expiresIn.map("expires_in" -> _.asJson),
        t.refreshToken.map("refresh_token" -> _.asJson),
        Some("scope" -> t.scope.asJson)
      ).flatten
    )).map(_.putHeaders("Cache-Control" -> "no-store", "Pragma" -> "no-cache"))
}

object OAuthProvider {

  /** トークンの応答（access_token・token_type=Bearer・expires_in・refresh_token・scope）. */
  final case class Token(accessToken: String,
                         scope: String,
                         expiresIn: Option[Long] = None,
                         refreshToken: Option[String] = None)

  /** トークンのエンドポイントのエラー（{"error":…, "error_description":…}）. */
  final case class Failure(status: Status, error: String, description: String)

  object Failure {
    def invalidGrant(description: String): Failure = Failure(Status.BadRequest, "invalid_grant", description)
    def invalidScope(description: String): Failure = Failure(Status.BadRequest, "invalid_scope", description)
    def invalidRequest(description: String): Failure = Failure(Status.BadRequest, "invalid_request", description)
  }

  /** 同意の画面: title は <title> に入れる文字（page がエスケープする）、html はフォームの手前までの本文（エスケープ済みの HTML）. */
  final case class Consent(title: String, html: String)

  /** refresh_token での更新: (要求, クライアント, refresh token, フォームの値) => 新しいトークンかエラー. */
  type Refresh = (Request[IO], OAuthClient, String, Map[String, String]) => IO[Either[Failure, Token]]

  /**
   * サービスごとの設定.
   *  - service: サービスの名前（CSRF の Cookie の名前 `<service>_oauth_csrf`・Basic の realm・画面の題とロゴ）
   *  - accent・extraCss: 既定の画面（page）の色と、足す CSS
   *  - loginRedirectNoStore・backNoStore・revokeNoStore: その応答に Cache-Control: no-store を付けるか
   *  - invalidScopeDescription: invalid_scope で戻すときの error_description
   *  - grantTypeMissing: grant_type が無いときの文言（省くと、使える grant_type の一覧の文言）
   *  - formValue: トークン・失効のフォームの値の扱い（None を返した項目は「無い」として扱う. 既定はそのまま）
   *  - unreadableForm・loginExpired・csrfExpired: エラーの画面の文言
   */
  final case class Settings(service: String,
                            cookieSecure: Boolean,
                            codeTtlSeconds: Long = 60L,
                            accent: String = "#2563eb",
                            extraCss: String = "",
                            loginPath: String = "/auth/login",
                            loginRedirectNoStore: Boolean = false,
                            backNoStore: Boolean = false,
                            revokeNoStore: Boolean = false,
                            invalidScopeDescription: Option[String] = None,
                            grantTypeMissing: Option[String] = None,
                            formValue: String => Option[String] = Some(_),
                            unreadableForm: String = "送られた内容が読めません。連携元の画面からやり直してください",
                            loginExpired: String = "ログインが切れました。連携元の画面からやり直してください",
                            csrfExpired: String = "画面の有効期限が切れました。連携元の画面からやり直してください")

  /** サービスが差し込むもの. S は画面のセッションの型（AuthSession・Principal など）. */
  trait Host[S] {
    def settings: Settings

    /** 登録済みのクライアント（環境変数の一覧なら `IO.pure(clients.find(_.id == id))`）. */
    def client(id: String): IO[Option[OAuthClient]]

    /** クライアントの秘密の照合（定数時間で比べること. ハッシュで持つサービスは差し替える）. */
    def secretMatches(c: OAuthClient, secret: String): Boolean = Crypto.constantTimeEquals(c.secret, secret)

    /** 画面のログイン（Cookie のセッション）. API キー・API トークンの Bearer では None を返すこと（同意の画面を経ずにスコープを広げさせない）. */
    def browserSession(req: Request[IO]): IO[Option[S]]

    /** 許可・拒否を受けるときのセッション. Left はエラーの画面に出す文言（既定は browserSession が無ければ loginExpired）. */
    def decisionSession(req: Request[IO]): IO[Either[String, S]] =
      browserSession(req).map(_.toRight(settings.loginExpired))

    def userId(s: S): UUID

    /** 求められたスコープを確かめる（None なら invalid_scope）. 既定は「空白区切り. 省くと read. クライアントに許したものだけ」. */
    def scopes(c: OAuthClient, requested: Option[String]): Option[List[String]] =
      requestedScopes(c.scopes, requested)

    /** 認可コードに保存する scope（既定は空白区切り. トークンのスコープが 1 つの値のサービスは、ここで read / write にまとめる）. */
    def codeScope(c: OAuthClient, scopes: List[String]): String = scopes.mkString(" ")

    /** 同意の画面の本文（フォームの手前まで）. */
    def consent(req: Request[IO], c: OAuthClient, s: S, scopes: List[String]): IO[Consent]

    /** 画面の枠（既定は sbirds 共通の見た目. ロゴや CSP を変えるサービスは差し替える）. */
    def page(title: String, body: String, status: Status): IO[Response[IO]] =
      IO.pure(OAuthProvider.page(settings.service, title, body, status, settings.accent, settings.extraCss))

    /** フォームを読む前の要求の加工（本文の上限を掛けるサービス用）. */
    def limit(req: Request[IO]): Request[IO] = req

    /** 認可コードの保存先. */
    def codes: OAuthCodes

    /** 許可されたとき（監査の記録など）. */
    def authorized(req: Request[IO], s: S, c: OAuthClient, scopes: List[String]): IO[Unit] = IO.unit

    /** 認可コードを確かめた後、トークンを発行する（API キーの登録・監査もここで）. code.scope は codeScope の値. */
    def issue(req: Request[IO], c: OAuthClient, code: OAuthCode): IO[Token]

    /** refresh_token での更新（None なら対応しない. unsupported_grant_type になる）. */
    def refresh: Option[Refresh] = None

    /** トークンの失効（そのクライアントが発行を受けたものだけ. なければ何もしない）. */
    def revoke(req: Request[IO], c: OAuthClient, token: String): IO[Unit]
  }

  /** 許可の画面の hidden に引き継ぐパラメータ. */
  val HiddenParams: List[String] =
    List("client_id", "redirect_uri", "state", "code_challenge", "code_challenge_method", "response_type", "scope")

  /** 求められたスコープ（空白区切り. 無ければ default）. allowed に無いものが含まれていれば None. */
  def requestedScopes(allowed: List[String],
                      requested: Option[String],
                      default: List[String] = List("read")): Option[List[String]] = {
    val rs = requested.map(_.split(' ').map(_.trim).filter(_.nonEmpty).toList.distinct).filter(_.nonEmpty)
      .getOrElse(default)
    Option.when(rs.forall(allowed.contains))(rs)
  }

  /** code_challenge の形（S256: SHA-256 の base64url. 43 文字）. */
  def validChallenge(s: String): Boolean = s.matches("^[A-Za-z0-9_-]{43}$")

  /** code_verifier の長さ（RFC 7636: 43〜128 文字）. */
  def validVerifier(s: String): Boolean = s.length >= 43 && s.length <= 128

  /** PKCE（S256）の照合. */
  def pkceMatches(challenge: String, verifier: String): Boolean =
    Crypto.constantTimeEquals(challenge, Crypto.pkceChallenge(verifier))

  /** HTML に埋め込む文字のエスケープ. */
  def escape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

  /**
   * 許可の画面の枠（sbirds 共通の見た目）. CSP の form-action は付けない（フォームの送信後のリダイレクトの連鎖にも効くため、
   * 連携元が callback の後に別の origin へ戻すと止まる）.
   */
  def page(service: String,
           title: String,
           body: String,
           status: Status = Status.Ok,
           accent: String = "#2563eb",
           extraCss: String = ""): Response[IO] = {
    val html =
      s"""<!DOCTYPE html><html lang="ja"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
         |<title>${escape(title)} | $service</title><style>
         |:root{color-scheme:light dark}body{margin:0;font-family:system-ui,-apple-system,"Hiragino Sans","Noto Sans JP",sans-serif;background:#f1f5f9;color:#1e293b;
         |display:flex;min-height:100vh;align-items:center;justify-content:center;padding:1rem}
         |@media(prefers-color-scheme:dark){body{background:#0f172a;color:#e2e8f0}.card{background:#1c273d!important;border-color:#283548!important}}
         |.card{background:#fff;border:1px solid #e2e8f0;border-radius:14px;padding:1.6rem 1.8rem;max-width:440px;width:100%;
         |box-shadow:0 11px 15px -7px rgba(0,0,0,.2),0 24px 38px 3px rgba(0,0,0,.14)}
         |h1{font-size:1.15rem;margin:0 0 .8rem}p{line-height:1.7;margin:.4rem 0}.muted{color:#64748b;font-size:.85rem}.logo{color:$accent;font-weight:800}
         |.actions{display:flex;gap:.6rem;justify-content:flex-end;margin-top:1.2rem}button{font:inherit;border-radius:8px;padding:.5rem 1.1rem;cursor:pointer;
         |border:1px solid #cbd5e1;background:transparent;color:inherit}button.primary{background:$accent;border-color:$accent;color:#fff}
         |ul{padding-left:1.2rem;margin:.4rem 0}$extraCss</style></head><body><div class="card"><div class="logo">$service</div>$body</div></body></html>""".stripMargin
    htmlResponse(html, status)
  }

  /** 画面の応答（キャッシュさせない・埋め込ませない・外のものを読み込ませない）. 自前の枠を使うサービス向け. */
  def htmlResponse(
    html: String,
    status: Status = Status.Ok,
    csp: String = "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'"): Response[IO] =
    Response[IO](status).withEntity(html)(using EntityEncoder.stringEncoder).putHeaders(
      `Content-Type`(MediaType.text.html, Charset.`UTF-8`),
      "Cache-Control" -> "no-store",
      "X-Frame-Options" -> "DENY",
      "Content-Security-Policy" -> csp
    )

  /**
   * 認可サーバーのメタデータ（RFC 8414. `GET /.well-known/oauth-authorization-server`）. 要るサービスだけ足す.
   * issuer は公開 URL（末尾の / なし）.
   */
  def metadata(issuer: String, scopes: List[String], refresh: Boolean = false): HttpRoutes[IO] = {
    val base = issuer.stripSuffix("/")
    val json = Json.obj(
      "issuer" -> base.asJson,
      "authorization_endpoint" -> s"$base/oauth/authorize".asJson,
      "token_endpoint" -> s"$base/oauth/token".asJson,
      "revocation_endpoint" -> s"$base/oauth/revoke".asJson,
      "response_types_supported" -> List("code").asJson,
      "grant_types_supported" -> (List("authorization_code") ++ Option.when(refresh)("refresh_token")).asJson,
      "code_challenge_methods_supported" -> List("S256").asJson,
      "token_endpoint_auth_methods_supported" -> List("client_secret_basic").asJson,
      "revocation_endpoint_auth_methods_supported" -> List("client_secret_basic").asJson,
      "scopes_supported" -> scopes.asJson
    )
    HttpRoutes.of[IO] {
      case req if req.method == Method.GET && req.pathInfo.renderString == "/.well-known/oauth-authorization-server" =>
        IO.pure(Response[IO](Status.Ok).withEntity(json).putHeaders("Cache-Control" -> "public, max-age=3600"))
    }
  }
}

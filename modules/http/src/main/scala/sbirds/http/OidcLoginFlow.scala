package sbirds.http

import cats.effect.IO
import cats.syntax.all.*
import org.http4s.*
import sbirds.auth.{Crypto, LoginDenied, LoginResult, OidcClient}

import java.time.Instant

/**
 * OIDC のログインの流れ（各サービスの AuthRoutes の login・callback・logout の共通部分）.
 *  - login: state・code_verifier・nonce を作って保存し、state をこのブラウザの Cookie にも入れて、IdP へ送る
 *  - callback: Cookie と state を照合する（他人が始めたログインを踏ませる「ログイン CSRF」を防ぐ）→ state を取り出す（1 回だけ）
 *    → IdP で確かめる → Host.loggedIn（ユーザーの upsert・セッションの発行・戻り先への 302）
 *  - logoutRedirect: IdP の end_session の URL
 *
 * 失敗の理由（Failure）は Host.failed に渡す. 利用者に見せてよいのは Failure.Denied（LoginDenied）の文言だけで、
 * Failure.Failed の例外の文言（検証に失敗した項目など）と IdP の error_description はログにだけ出すこと.
 */
object OidcLoginFlow {

  /** state の Cookie をいつ消すか. */
  enum Clear {

    /** 消さない（maxAge で切れる）. */
    case Never

    /** ログインできたとき（Host.loggedIn が 302 を返したとき）. */
    case OnSuccess

    /** Cookie と state が合った後は、結果によらず消す. */
    case Always
  }

  /**
   * state を入れる Cookie（HttpOnly・SameSite=Lax. IdP からの戻りはトップレベルの GET なので Lax でも送られる）.
   *  - hashed: Cookie には state の SHA-256（16 進）を入れる
   *  - checkWithoutCode: code が無くても、state があれば先に Cookie と照合する（false なら code と state の両方があるときだけ）
   */
  final case class StateCookie(name: String,
                               secure: Boolean,
                               maxAge: Long = 600,
                               path: String = "/auth",
                               hashed: Boolean = false,
                               clear: Clear = Clear.OnSuccess,
                               checkWithoutCode: Boolean = false) {
    private def encode(state: String): String = if hashed then Crypto.hex(Crypto.sha256(state)) else state

    def set(state: String): ResponseCookie = Cookies.httpOnly(name, encode(state), maxAge, secure, path)

    def expired: ResponseCookie = Cookies.httpOnly(name, "", 0, secure, path)

    /** ログインを始めたブラウザか. */
    def matches(req: Request[IO], state: String): Boolean =
      Cookies.value(req, name).exists(c => Crypto.constantTimeEquals(c, encode(state)))
  }

  /** IdP（R は確かめた結果. sbirds.auth.OidcClient なら LoginResult）. */
  trait Idp[R] {
    def authorizationUri(state: String, verifier: String, nonce: String): IO[Uri]
    def authenticate(code: String, verifier: String, nonce: String, now: Instant): IO[R]
  }

  object Idp {
    def apply(o: OidcClient): Idp[LoginResult] = new Idp[LoginResult] {
      def authorizationUri(state: String, verifier: String, nonce: String): IO[Uri] =
        o.authorizationUri(state, verifier, nonce)
      def authenticate(code: String, verifier: String, nonce: String, now: Instant): IO[LoginResult] =
        o.authenticate(code, verifier, nonce, now)
    }
  }

  /** callback で先へ進めなかった理由. */
  enum Failure {

    /** OIDC を設定していない. */
    case NotConfigured

    /** IdP が error を付けて戻した（文言は URL で差し替えられるので、画面には出さずログにだけ残す）. */
    case IdpError(error: String, description: Option[String])

    /** code・state が無い. */
    case ParamsMissing

    /** ログインを始めたブラウザではない（state の Cookie が無い・合わない）. */
    case BrowserMismatch

    /** state が保存されていない（期限切れ・使用済み）. */
    case Expired

    /** IdP・設定がログインを断った（文言は利用者に見せてよい）. */
    case Denied(e: LoginDenied)

    /** そのほかの失敗（文言はログにだけ出す）. */
    case Failed(e: Throwable)
  }

  /**
   * IdP が付けた error・error_description を、ログに残す形にする（制御文字を除き、長さを切る）.
   * どちらも URL で差し替えられる値なので、画面・応答には出さず、Errors.respondLogged・Log.report の詳細にだけ使うこと
   * （利用者には、各サービスの ErrorCode の既定の文言だけを返す）.
   */
  def idpErrorLog(error: String, description: Option[String]): String = {
    val clean = (s: String, max: Int) => s.map(c => if c.isControl then ' ' else c).take(max)
    (s"IdP error: ${clean(error, 100)}" + description.fold("")(d => s" (${clean(d, 300)})")).trim
  }

  /** サービスが差し込むもの. */
  trait Host[R] {
    def idp: Option[Idp[R]]
    def cookie: StateCookie

    /** IdP が付けた error パラメータを見るか（見るなら最初に確かめて Failure.IdpError にする）. */
    def idpErrorParam: Boolean = false

    /** ログインの後に戻る先（同じサイトのパスだけにすること. SafePath）. */
    def returnTo(req: Request[IO]): String

    def saveState(state: String, verifier: String, nonce: String, returnTo: String): IO[Unit]

    /** state を取り出して消す（1 回だけ使える）. 返すのは (verifier, nonce, returnTo). */
    def takeState(state: String): IO[Option[(String, String, String)]]

    /** IdP への 302. */
    def redirect(to: String): IO[Response[IO]]

    /** OIDC を設定していないときの login（開発用のログインの画面へ送る・エラーにする）. */
    def withoutIdp(req: Request[IO], returnTo: String): IO[Response[IO]]

    def failed(req: Request[IO], f: Failure): IO[Response[IO]]

    /** IdP で確かめた後: ユーザーの upsert・無効なユーザーの確認・セッションの発行・returnTo への 302. */
    def loggedIn(req: Request[IO], result: R, returnTo: String): IO[Response[IO]]
  }

  /** `GET /auth/login` */
  def login[R](h: Host[R])(req: Request[IO]): IO[Response[IO]] = {
    val back = h.returnTo(req)
    h.idp match {
      case Some(idp) =>
        val state = Crypto.randomToken(); val verifier = Crypto.randomToken(48); val nonce = Crypto.randomToken()
        // state をこのブラウザの Cookie にも入れ、戻ってきたときに照合する
        h.saveState(state, verifier, nonce, back) *> idp.authorizationUri(state, verifier, nonce).flatMap(u =>
          h.redirect(u.renderString).map(_.addCookie(h.cookie.set(state)))
        )
      case None => h.withoutIdp(req, back)
    }
  }

  /** `GET /auth/callback` */
  def callback[R](h: Host[R])(req: Request[IO]): IO[Response[IO]] = {
    val p = (k: String) => HttpParts.param(req, k)
    (h.idp, p("code"), p("state"), if h.idpErrorParam then p("error") else None) match {
      case (_, _, _, Some(e)) => h.failed(req, Failure.IdpError(e, p("error_description")))
      case (None, _, _, _) => h.failed(req, Failure.NotConfigured)
      case (Some(_), code, Some(state), _)
          if (code.isDefined || h.cookie.checkWithoutCode) && !h.cookie.matches(req, state) =>
        h.failed(req, Failure.BrowserMismatch)
      case (Some(idp), Some(code), Some(state), _) =>
        val done = h.takeState(state).flatMap {
          case None => h.failed(req, Failure.Expired)
          case Some((verifier, nonce, back)) =>
            idp.authenticate(code, verifier, nonce, Instant.now()).attempt.flatMap {
              case Left(e: LoginDenied) => h.failed(req, Failure.Denied(e))
              case Left(e) => h.failed(req, Failure.Failed(e))
              case Right(r) =>
                h.loggedIn(req, r, back).map(res =>
                  if h.cookie.clear == Clear.OnSuccess && res.status == Status.Found then
                    res.addCookie(h.cookie.expired)
                  else res
                )
            }
        }
        if h.cookie.clear == Clear.Always then done.map(_.addCookie(h.cookie.expired)) else done
      case _ => h.failed(req, Failure.ParamsMissing)
    }
  }

  /** ログアウトの後に画面が移る先: IdP の end_session の URL（OIDC でログインしていたとき）. なければ fallback. */
  def logoutRedirect(oidc: Option[OidcClient],
                     idToken: Option[String],
                     postLogout: String,
                     fallback: String = "/"): IO[String] =
    oidc.flatTraverse(_.logoutUri(idToken, postLogout)).map(_.fold(fallback)(_.renderString))
}

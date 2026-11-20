package sbirds.http

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import org.http4s.*
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString
import sbirds.shared.{AppError, ErrorCode, LibErrorCode}

/**
 * CSRF の確認（Cookie のセッションで状態を変える要求が、自分の画面から送られたものか）.
 * SameSite=Lax の Cookie だけでは、同じ登録ドメインの別のサービス（same-site）からの POST を防げないので、要求のヘッダーで確かめる.
 *
 * 確かめ方は2つあり、Policy で選ぶ（両方を指定すれば両方を確かめる）:
 *  - オリジンの照合（`Policy(publicUrl)`）: ブラウザが付ける Origin（なければ Referer）と Sec-Fetch-Site を見る.
 *  - 専用のヘッダー（`Policy.headerOnly("X-Skaka")`）: 画面が付けるヘッダーがあること. 他のサイトのフォームからは付けられない（CORS の事前確認が要る）.
 *
 * 「どの要求を確かめるか」（Cookie のセッションのときだけ・Bearer でないときはいつも、など）は認証の作りで決まるので、各サービスが決める.
 * {{{
 * private val csrf = Csrf.Policy(cfg.publicUrl, devLocalhost = cfg.devLogin)
 * // 全体に掛ける（Authorization がなく、セッションの Cookie がある更新系だけを確かめる）
 * Csrf.routes(csrf, Csrf.cookieAuth(cfg.cookieName), ErrorCode.CrossSiteRequest)(routes)
 * // 認証の中で確かめる
 * if s.isSession && Csrf.foreign(req, csrf) then err(ErrorCode.CrossSiteRequest) else f(s)
 * }}}
 */
object Csrf {

  /** 状態を変えない要求のメソッド（既定）. OPTIONS も確かめないサービスは SafeWithOptions を渡す. */
  val Safe: Set[Method] = Set(Method.GET, Method.HEAD)
  val SafeWithOptions: Set[Method] = Safe + Method.OPTIONS

  /**
   * 確かめ方. 既定（`Policy(publicUrl)`）がいちばん厳しく、ほかの引数は今の挙動を保つための差.
   *
   * @param origin         自分のオリジン（PUBLIC_URL. パスが付いていてもよい）. None ならオリジンは照合しない（専用のヘッダーだけのサービス）.
   * @param header         画面が付ける専用のヘッダーの名前（X-Skaka など）. Some なら、そのヘッダーがない要求を断る.
   * @param devLocalhost   開発中（DEV_LOGIN）だけ true: http://localhost・http://127.0.0.1（ポートは何でも）の Origin・Referer も自分の画面として扱う.
   * @param hostFallback   Origin が要求の Host ヘッダーと同じホスト・ポートなら自分の画面として扱う（PUBLIC_URL と違う名前で開かれる構成. skiwi）.
   * @param trustFetchSite Sec-Fetch-Site が same-origin・none なら、Origin・Referer を照合しない（skawau）.
   * @param safeMethods    確かめないメソッド.
   */
  final case class Policy(origin: Option[String],
                          header: Option[String] = None,
                          devLocalhost: Boolean = false,
                          hostFallback: Boolean = false,
                          trustFetchSite: Boolean = false,
                          safeMethods: Set[Method] = Safe) {
    private[http] val own: Option[String] = origin.map(u => originOf(u).getOrElse(u.trim.toLowerCase))
    private[http] val headerName: Option[CIString] = header.map(CIString(_))
    def withHeader(name: String): Policy = copy(header = Some(name))
  }

  object Policy {

    /** オリジンの照合（いちばん厳しい既定）. */
    def apply(publicUrl: String): Policy = Policy(Some(publicUrl))

    def apply(publicUrl: String, devLocalhost: Boolean): Policy = Policy(Some(publicUrl), devLocalhost = devLocalhost)

    /** 専用のヘッダーだけを確かめる（オリジンは照合しない）. */
    def headerOnly(name: String, safeMethods: Set[Method] = Safe): Policy =
      Policy(None, header = Some(name), safeMethods = safeMethods)
  }

  /** URL・Origin の値のオリジン（小文字の scheme://host[:port]）. 読めなければ None（"null" も None）. */
  def originOf(url: String): Option[String] =
    Uri.fromString(url.trim).toOption.flatMap(u =>
      for {
        s <- u.scheme
        a <- u.authority
      } yield s"${s.value.toString.toLowerCase}://${a.host.renderString.toLowerCase}${a.port.fold("")(":" + _)}"
    )

  private val Local = Set("localhost", "127.0.0.1")

  private def isDevLocal(url: String): Boolean =
    Uri.fromString(url.trim).toOption.exists(u =>
      u.scheme.exists(_.value.toString.equalsIgnoreCase("http")) &&
        u.host.exists(h => Local.contains(h.value.toLowerCase))
    )

  private def header(req: Request[IO], name: String): Option[String] =
    req.headers.get(CIString(name)).map(_.head.value.trim)

  /** 状態を変えない要求か（確かめなくてよい）. */
  def safe(req: Request[IO], policy: Policy): Boolean = policy.safeMethods.contains(req.method)

  /**
   * 自分の画面から送られた要求か（メソッドは見ない. ログインしていない人の更新〈ログアウト・開発用のログインなど〉にも使える）.
   *  - 専用のヘッダーを指定していれば、それがあること.
   *  - オリジンを照合するなら:
   *    - Sec-Fetch-Site があれば same-origin か none であること（same-site・cross-site は断る）.
   *    - Origin があれば自分のオリジンであること（"null" は別のサイトとして扱う）. なければ Referer を同じように確かめる.
   *    - どれもなければ、ブラウザ以外（curl など）とみなして通す.
   */
  def fromOwnScreen(req: Request[IO], policy: Policy): Boolean =
    policy.headerName.forall(h => req.headers.get(h).isDefined) && policy.own.forall(sameOrigin(req, policy, _))

  private def sameOrigin(req: Request[IO], policy: Policy, own: String): Boolean = {
    def mine(value: String): Boolean =
      originOf(value).contains(own) ||
        (policy.devLocalhost && isDevLocal(value)) ||
        (policy.hostFallback && header(req, "Host").exists(h =>
          originOf(value).exists(_.endsWith("://" + h.toLowerCase))
        ))
    val site = header(req, "Sec-Fetch-Site").map(_.toLowerCase)
    val siteOk = site.forall(v => v == "same-origin" || v == "none")
    if !siteOk then false
    else if policy.trustFetchSite && site.isDefined then true
    else
      header(req, "Origin") match {
        case Some(o) => mine(o)
        case None => header(req, "Referer").forall(mine)
      }
  }

  /** 断るべき要求か（状態を変えるメソッドで、自分の画面からではない）. */
  def foreign(req: Request[IO], policy: Policy): Boolean = !safe(req, policy) && !fromOwnScreen(req, policy)

  /** Authorization ヘッダーがなく、セッションの Cookie がある要求か（routes の applies に渡す）. */
  def cookieAuth(cookieName: String)(req: Request[IO]): Boolean =
    req.headers.get[Authorization].isEmpty && req.cookies.exists(_.name == cookieName)

  /** Authorization: Bearer でない要求すべて（ログインしていない要求も確かめるサービス）. */
  def notBearer(req: Request[IO]): Boolean = HttpParts.bearer(req).isEmpty

  /**
   * 全体に掛けるミドルウェア. applies が true の要求のうち、断るべきものを code（403）で返す.
   * skip はパスごとの例外（ほかのサイトから受ける取り込みの入口など）.
   */
  def routes(policy: Policy,
             applies: Request[IO] => Boolean,
             code: ErrorCode = LibErrorCode.CrossSite,
             skip: Request[IO] => Boolean = _ => false)(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    Kleisli { (req: Request[IO]) =>
      if !skip(req) && applies(req) && foreign(req, policy) then
        OptionT.liftF(Errors.respond(AppError(code), Some(req)))
      else routes(req)
    }

  /** 1つの要求を確かめてから f を動かす（認証の中・個別のルートで使う）. */
  def guard(req: Request[IO], policy: Policy, code: ErrorCode = LibErrorCode.CrossSite)(
    f: => IO[Response[IO]]): IO[Response[IO]] =
    if foreign(req, policy) then Errors.respond(AppError(code), Some(req)) else f
}

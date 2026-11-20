package sbirds.http

import cats.effect.*
import org.http4s.*
import org.typelevel.ci.CIString

/**
 * すべての応答に付けるセキュリティのヘッダー.
 * 既定（Policy()）は、nosniff・X-Frame-Options: DENY・Referrer-Policy: strict-origin-when-cross-origin を付け、
 * 応答が自分で CSP を付けていなければ、何も読み込ませない CSP（StrictCsp. frame-ancestors 'none' を含む）を付ける
 * （API・エラーの画面向け. 画面の配信は画面に合わせた CSP を自分で付ける）. HSTS は hsts に値を渡したときだけ.
 * {{{
 * SecurityHeaders.app(SecurityHeaders.Policy(hsts = SecurityHeaders.hsts(cfg.cookieSecure)))(httpApp)
 * }}}
 */
object SecurityHeaders {

  val StrictCsp =
    "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"

  /** 画面を iframe に入れさせないだけの CSP（画面の配信に CSP を付けていないサービス向け）. */
  val FrameCsp = "frame-ancestors 'none'; base-uri 'self'; object-src 'none'"

  val HstsOneYear = "max-age=31536000"

  /** https で公開しているときだけ HSTS を付ける. */
  def hsts(https: Boolean, value: String = HstsOneYear): Option[String] = Option.when(https)(value)

  /**
   * 付けるヘッダー（None は付けない）.
   * keepExisting: true なら、応答が自分で付けたヘッダーを残す（false なら上書きする）. CSP は、どちらでも応答が付けたものを残す.
   * cspHtmlOnly・frameOptionsHtmlOnly: HTML の応答だけに付ける.
   * extra: そのほかのヘッダー（Permissions-Policy など）.
   */
  final case class Policy(hsts: Option[String] = None,
                          frameOptions: Option[String] = Some("DENY"),
                          referrer: Option[String] = Some("strict-origin-when-cross-origin"),
                          csp: Option[String] = Some(StrictCsp),
                          extra: List[(String, String)] = Nil,
                          keepExisting: Boolean = false,
                          cspHtmlOnly: Boolean = false,
                          frameOptionsHtmlOnly: Boolean = false)

  def apply(policy: Policy)(routes: HttpRoutes[IO]): HttpRoutes[IO] = routes.map(add(_, policy))

  /** アプリ全体（見つからない 404・内部エラーの 500 を含む）に付ける. */
  def app(policy: Policy)(app: HttpApp[IO]): HttpApp[IO] = app.map(add(_, policy))

  /** 要求ごとに付けるものを変える（埋め込み用のページだけ frame-ancestors を緩める、など）. */
  def routesWith(policy: Request[IO] => Policy)(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes[IO](req => routes.run(req).map(add(_, policy(req))))

  def appWith(policy: Request[IO] => Policy)(app: HttpApp[IO]): HttpApp[IO] =
    HttpApp[IO](req => app.run(req).map(add(_, policy(req))))

  /** 1つの応答に付ける. */
  def add(r: Response[IO], p: Policy): Response[IO] = {
    val html = r.contentType.exists(_.mediaType == MediaType.text.html)
    def has(name: String): Boolean = r.headers.get(CIString(name)).isDefined
    val fixed = List(
      Some("X-Content-Type-Options" -> "nosniff"),
      p.frameOptions.filter(_ => html || !p.frameOptionsHtmlOnly).map("X-Frame-Options" -> _),
      p.referrer.map("Referrer-Policy" -> _),
      p.hsts.map("Strict-Transport-Security" -> _)
    ).flatten ++ p.extra
    val csp = p.csp.filter(_ => html || !p.cspHtmlOnly).filterNot(_ => has("Content-Security-Policy"))
      .map("Content-Security-Policy" -> _)
    val all = fixed.filterNot((k, _) => p.keepExisting && has(k)) ++ csp
    r.putHeaders(all.map(h => Header.ToRaw.keyValuesToRaw(h))*)
  }
}

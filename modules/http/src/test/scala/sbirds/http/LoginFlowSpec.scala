package sbirds.http

import cats.effect.*
import io.circe.{Decoder, Json}
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.{Authorization, Location}
import org.http4s.implicits.*
import org.typelevel.ci.CIString
import sbirds.auth.{Crypto, LoginDenied}
import sbirds.http.OidcLoginFlow.{Clear, Failure, StateCookie}
import sbirds.shared.{AppError, LibErrorCode}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

class LoginFlowSpec extends CatsEffectSuite {

  private given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.noop.NoOpFactory[IO]

  private def header(r: Response[IO], n: String) = r.headers.get(CIString(n)).map(_.head.value)
  private def location(r: Response[IO]): String = r.headers.get[Location].map(_.uri.renderString).getOrElse("")

  // ---------------------------------------------------------------- Cookies・HttpParts・RouteHelpers

  test("Cookies.httpOnly: HttpOnly・SameSite=Lax・path=/") {
    val c = Cookies.httpOnly("svc_session", "v", 60, secure = true)
    assert(c.httpOnly && c.secure)
    assertEquals((c.sameSite, c.path, c.maxAge), (Some(SameSite.Lax), Some("/"), Some(60L)))
    assert(!Cookies.httpOnly("a", "v", 60, secure = false).secure)
  }

  test("HttpParts: param・bearer・redirect") {
    val req = Request[IO](Method.GET, uri"/x?a=%20v%20&b=%20")
      .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, "svc_abc")))
    assertEquals(HttpParts.param(req, "a"), Some("v"))
    assertEquals(HttpParts.param(req, "b"), None)
    assertEquals(HttpParts.bearer(req), Some("svc_abc"))
    assertEquals(HttpParts.bearer(Request[IO](Method.GET, uri"/x")), None)
    val r = HttpParts.redirect("/a?b=1", Some(Cookies.httpOnly("c", "v", 1, secure = false)), noStore = true)
    assertEquals(r.status, Status.Found)
    assertEquals(location(r), "/a?b=1")
    assertEquals(header(r, "Cache-Control"), Some("no-store"))
    assertEquals(r.cookies.map(_.name), List("c"))
    assertEquals(header(HttpParts.redirect("/a"), "Cache-Control"), None)
    assertEquals(location(HttpParts.redirect("/%zz", lenient = true)), "/")
  }

  private final case class Name(name: String) derives Decoder

  private class Routes(keep: Boolean) extends RouteHelpers {
    protected def bodyInvalid = LibErrorCode.BadBody
    override protected def bodyKeepsAppError = keep
    val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case req @ POST -> Root / "names" => body[Name](req)(n => Ok(n.name))
      case GET -> Root / "items" / UuidVar(id) => Ok(id.toString)
      case req @ GET -> Root / "list" => Ok(intParam(req, "limit", 50, 200).toString)
      case GET -> Root / "go" => redirect("/there")
    }
    val app = routes.orNotFound
  }

  test("RouteHelpers: body・UuidVar・intParam・redirect") {
    val r = new Routes(keep = true)
    val id = UUID.randomUUID()
    for {
      ok <- r.app.run(Request[IO](Method.POST, uri"/names").withEntity(Json.obj("name" -> Json.fromString("a"))))
      bad <- r.app.run(Request[IO](Method.POST, uri"/names").withEntity(Json.obj("x" -> Json.fromInt(1))))
      badJson <- bad.as[Json]
      u <- r.app.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/items/$id")))
      nf <- r.app.run(Request[IO](Method.GET, uri"/items/abc"))
      l1 <- r.app.run(Request[IO](Method.GET, uri"/list?limit=1000")).flatMap(_.as[String])
      l2 <- r.app.run(Request[IO](Method.GET, uri"/list?limit=x")).flatMap(_.as[String])
      go <- r.app.run(Request[IO](Method.GET, uri"/go"))
    } yield {
      assertEquals(ok.status, Status.Ok)
      assertEquals(bad.status, Status.BadRequest)
      assertEquals(badJson.hcursor.get[String]("code"), Right("SBL-1001"))
      assert(badJson.hcursor.get[String]("error").exists(_.startsWith("リクエストの内容が正しくありません: ")))
      assertEquals(u.status, Status.Ok)
      assertEquals(nf.status, Status.NotFound)
      assertEquals((l1, l2), ("200", "50"))
      assertEquals((go.status, location(go)), (Status.Found, "/there"))
    }
  }

  test("RouteHelpers.body: 読み取りの途中の AppError（大きすぎる本文）は、そのコードの応答（bodyKeepsAppError = false なら bodyInvalid）") {
    val big = Request[IO](Method.POST, uri"/names").withBodyStream(
      fs2.Stream.raiseError[IO](AppError(LibErrorCode.TooLarge))
    ).withContentType(headers.`Content-Type`(MediaType.application.json))
    for {
      a <- new Routes(keep = true).app.run(big)
      b <- new Routes(keep = false).app.run(big)
    } yield {
      assertEquals(a.status, Status.PayloadTooLarge)
      assertEquals(b.status, Status.BadRequest)
    }
  }

  // ---------------------------------------------------------------- SessionCookie

  test("SessionCookie: 発行・取り消し・表示名") {
    val sc = new SessionCookie("svc_session", secure = true, 14.days)
    for {
      saved <- Ref.of[IO, Option[(String, Instant)]](None)
      c <- sc.issue((h, exp) => saved.set(Some(Crypto.hex(h) -> exp)))
      s <- saved.get
      req = Request[IO](Method.GET, uri"/").addCookie(RequestCookie("svc_session", c.content))
      rev <- sc.revoke(req)(h => IO.pure(Option.when(Crypto.hex(h) == s.get._1)("idtoken")))
      none <- sc.revoke(Request[IO](Method.GET, uri"/"))(_ => IO.raiseError(new Exception("呼ばれない")))
      names <- sc.appNames(Request[IO](Method.GET, uri"/"))(_ => IO.raiseError(new Exception("呼ばれない")))
    } yield {
      assertEquals(s.map(_._1), Some(Crypto.hex(Crypto.sha256(c.content))))
      assertEquals(
        (c.name, c.maxAge, c.path, c.sameSite),
        ("svc_session", Some(14L * 86400), Some("/"), Some(SameSite.Lax))
      )
      assert(c.httpOnly && c.secure)
      assertEquals(sc.token(req), Some(c.content))
      assertEquals(rev._1, Some("idtoken"))
      assertEquals((rev._2.content, rev._2.maxAge), ("", Some(0L)))
      assertEquals(none._1, None)
      assertEquals(names, Map.empty[String, String])
    }
  }

  // ---------------------------------------------------------------- OidcLoginFlow

  private final class Fixture(cookie: StateCookie = StateCookie("svc_login", secure = true),
                              configured: Boolean = true,
                              errorParam: Boolean = false,
                              auth: IO[String] = IO.pure("sub-1")) {
    val states = Ref.unsafe[IO, Map[String, (String, String, String)]](Map.empty)
    val failures = Ref.unsafe[IO, List[Failure]](Nil)
    val host: OidcLoginFlow.Host[String] = new OidcLoginFlow.Host[String] {
      val idp = Option.when(configured)(new OidcLoginFlow.Idp[String] {
        def authorizationUri(state: String, verifier: String, nonce: String) =
          IO.pure(uri"https://idp.example/authorize".withQueryParam("state", state))
        def authenticate(code: String, verifier: String, nonce: String, now: Instant) = auth
      })
      val cookie = Fixture.this.cookie
      override def idpErrorParam = errorParam
      def returnTo(req: Request[IO]) = HttpParts.param(req, "returnTo").filter(_.startsWith("/")).getOrElse("/")
      def saveState(state: String, verifier: String, nonce: String, returnTo: String) =
        states.update(_ + (state -> (verifier, nonce, returnTo)))
      def takeState(state: String) = states.modify(m => (m - state, m.get(state)))
      def redirect(to: String) = IO.pure(HttpParts.redirect(to))
      def withoutIdp(req: Request[IO], returnTo: String) = IO.pure(HttpParts.redirect(s"/login?returnTo=$returnTo"))
      def failed(req: Request[IO], f: Failure) = failures.update(f :: _).as(Response[IO](Status.BadRequest))
      def loggedIn(req: Request[IO], sub: String, returnTo: String) =
        IO.pure(
          if sub == "disabled" then Response[IO](Status.Forbidden)
          else HttpParts.redirect(returnTo, Some(Cookies.httpOnly("svc_session", sub, 60, secure = true)))
        )
    }
    def login(uri: Uri = uri"/auth/login?returnTo=/tasks") = OidcLoginFlow.login(host)(Request[IO](Method.GET, uri))
    def callback(ps: Map[String, String], cookies: List[ResponseCookie]) =
      OidcLoginFlow.callback(host)(
        cookies.foldLeft(Request[IO](Method.GET, uri"/auth/callback".withQueryParams(ps)))((r, c) =>
          r.addCookie(RequestCookie(c.name, c.content))
        )
      )
    def lastFailure: IO[Option[Failure]] = failures.get.map(_.headOption)
  }

  test("login → callback: state を Cookie と照合し、戻り先へ 302（state の Cookie は消す）") {
    val f = new Fixture
    for {
      l <- f.login()
      state = Uri.unsafeFromString(location(l)).query.params("state")
      ck = l.cookies.find(_.name == "svc_login").get
      saved <- f.states.get
      r <- f.callback(Map("code" -> "c", "state" -> state), List(ck))
      again <- f.callback(Map("code" -> "c", "state" -> state), List(ck))
      fl <- f.lastFailure
    } yield {
      assertEquals(l.status, Status.Found)
      assertEquals(ck.content, state)
      assertEquals((ck.path, ck.maxAge, ck.sameSite), (Some("/auth"), Some(600L), Some(SameSite.Lax)))
      assert(ck.httpOnly && ck.secure)
      assertEquals(saved(state)._3, "/tasks")
      assertEquals(saved(state)._1.length, 64) // 48 バイトの verifier
      assertEquals((r.status, location(r)), (Status.Found, "/tasks"))
      assertEquals(r.cookies.find(_.name == "svc_session").map(_.content), Some("sub-1"))
      assertEquals(r.cookies.find(_.name == "svc_login").map(c => (c.content, c.maxAge)), Some(("", Some(0L))))
      // state は 1 回だけ
      assertEquals(again.status, Status.BadRequest)
      assertEquals(fl, Some(Failure.Expired))
    }
  }

  test("callback: ほかのブラウザ（state の Cookie が無い・違う）では進めない. state も消費しない") {
    val f = new Fixture
    for {
      l <- f.login()
      state = Uri.unsafeFromString(location(l)).query.params("state")
      a <- f.callback(Map("code" -> "c", "state" -> state), Nil)
      fa <- f.lastFailure
      b <- f.callback(Map("code" -> "c", "state" -> state), List(ResponseCookie("svc_login", "other")))
      left <- f.states.get
    } yield {
      assertEquals((a.status, b.status), (Status.BadRequest, Status.BadRequest))
      assertEquals(fa, Some(Failure.BrowserMismatch))
      assert(left.contains(state))
    }
  }

  test("callback: hashed なら Cookie には state のハッシュを入れる") {
    val f = new Fixture(StateCookie("svc_login", secure = false, hashed = true))
    for {
      l <- f.login()
      state = Uri.unsafeFromString(location(l)).query.params("state")
      ck = l.cookies.find(_.name == "svc_login").get
      raw <- f.callback(Map("code" -> "c", "state" -> state), List(ResponseCookie("svc_login", state)))
      ok <- f.callback(Map("code" -> "c", "state" -> state), List(ck))
    } yield {
      assertEquals(ck.content, Crypto.hex(Crypto.sha256(state)))
      assertEquals(raw.status, Status.BadRequest)
      assertEquals(ok.status, Status.Found)
    }
  }

  test("callback: 失敗の理由（設定なし・パラメータなし・IdP の error・断られた・そのほか）") {
    def failure(f: Fixture, ps: Map[String, String], viaLogin: Boolean = false): IO[Option[Failure]] =
      (if viaLogin then
         f.login().flatMap { l =>
           val state = Uri.unsafeFromString(location(l)).query.params("state")
           f.callback(ps + ("state" -> state), l.cookies)
         }
       else f.callback(ps, Nil)) *> f.lastFailure
    val boom = new RuntimeException("署名が合いません")
    val denied = new LoginDenied("このアカウントは使えません")
    for {
      a <- failure(new Fixture(configured = false), Map("code" -> "c", "state" -> "s"))
      b <- failure(new Fixture, Map("code" -> "c"))
      c <- failure(new Fixture, Map("state" -> "s"))
      d <- failure(new Fixture(errorParam = true), Map("error" -> "access_denied", "error_description" -> "no"))
      e <- failure(new Fixture, Map("error" -> "access_denied"))
      g <- failure(new Fixture(auth = IO.raiseError(boom)), Map("code" -> "c"), viaLogin = true)
      h <- failure(new Fixture(auth = IO.raiseError(denied)), Map("code" -> "c"), viaLogin = true)
      // checkWithoutCode: code が無くても先に Cookie と照合する
      i <- failure(new Fixture(StateCookie("svc_login", secure = true, checkWithoutCode = true)), Map("state" -> "s"))
    } yield {
      assertEquals(a, Some(Failure.NotConfigured))
      assertEquals(b, Some(Failure.ParamsMissing))
      assertEquals(c, Some(Failure.ParamsMissing))
      assertEquals(d, Some(Failure.IdpError("access_denied", Some("no"))))
      assertEquals(e, Some(Failure.ParamsMissing))
      assertEquals(g, Some(Failure.Failed(boom)))
      assertEquals(h, Some(Failure.Denied(denied)))
      assertEquals(i, Some(Failure.BrowserMismatch))
    }
  }

  test("callback: state の Cookie を消す時機（Never・OnSuccess・Always）") {
    def run(clear: Clear, sub: String): IO[Boolean] = {
      val f = new Fixture(StateCookie("svc_login", secure = true, clear = clear), auth = IO.pure(sub))
      for {
        l <- f.login()
        state = Uri.unsafeFromString(location(l)).query.params("state")
        r <- f.callback(Map("code" -> "c", "state" -> state), l.cookies)
      } yield r.cookies.exists(c => c.name == "svc_login" && c.content.isEmpty)
    }
    for {
      a <- run(Clear.Never, "sub-1")
      b <- run(Clear.OnSuccess, "sub-1")
      c <- run(Clear.OnSuccess, "disabled")
      d <- run(Clear.Always, "disabled")
    } yield assertEquals((a, b, c, d), (false, true, false, true))
  }

  test("login: OIDC を設定していなければ withoutIdp") {
    new Fixture(configured = false).login().map(r => assertEquals(location(r), "/login?returnTo=/tasks"))
  }

  test("logoutRedirect: OIDC を設定していなければ fallback") {
    OidcLoginFlow.logoutRedirect(None, Some("t"), "https://svc.example/", "/login?loggedOut=1")
      .map(assertEquals(_, "/login?loggedOut=1"))
  }
}

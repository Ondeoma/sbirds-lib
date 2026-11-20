package sbirds.http

import cats.effect.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.typelevel.ci.CIString
import sbirds.shared.{AppError, LibErrorCode, SafePath}

class HttpPartsSpec extends CatsEffectSuite {

  private given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.noop.NoOpFactory[IO]

  private def header(r: Response[IO], name: String): Option[String] = r.headers.get(CIString(name)).map(_.head.value)

  // ---------------------------------------------------------------- Env

  test("Env: 空白だけの値は無いものとして扱う") {
    val env = new Env(
      Map(
        "A" -> " x ",
        "B" -> "  ",
        "N" -> "12",
        "T" -> "TRUE",
        "F" -> "false",
        "L" -> "a, b  c,,d",
        "BIG" -> "5000000000"
      ).get
    )
    assertEquals(env.opt("A"), Some("x"))
    assertEquals(env.opt("B"), None)
    assertEquals(env.int("N"), Some(12))
    assertEquals(env.int("A"), None)
    assertEquals(env.long("BIG"), Some(5000000000L))
    assert(env.enabled("T") && !env.enabled("F") && !env.enabled("X"))
    assert(env.disabled("F") && !env.disabled("T") && !env.disabled("X"))
    assert(env.bool("X", true) && !env.bool("F", true) && env.bool("T", false))
    assertEquals(env.required("A"), Right("x"))
    assertEquals(env.required("B"), Left("環境変数 B を設定してください"))
    assertEquals(env.list("L"), List("a", "b", "c", "d"))
    assertEquals(env.list("X"), Nil)
  }

  test("RateLimitConfig.fromEnv: 既定の値と、TRUSTED_PROXIES の誤り") {
    assertEquals(RateLimitConfig.fromEnv(new Env(_ => None)), Right(RateLimitConfig(20, 120, Nil)))
    val ok = RateLimitConfig.fromEnv(
      new Env(Map(
        "RATE_LIMIT_AUTH" -> "5",
        "RATE_LIMIT_PUBLIC" -> "0",
        "TRUSTED_PROXIES" -> "10.0.0.0/8, 192.0.2.7"
      ).get)
    )
    assertEquals(
      ok.map(c => (c.authPerMinute, c.publicPerMinute, c.trustedProxies.map(_.toString))),
      Right((5, 0, List("10.0.0.0/8", "192.0.2.7/32")))
    )
    assertEquals(
      RateLimitConfig.fromEnv(new Env(Map("TRUSTED_PROXIES" -> "proxy").get)),
      Left("TRUSTED_PROXIES の「proxy」は IP か CIDR で書いてください")
    )
  }

  // ---------------------------------------------------------------- SafePath

  test("SafePath.isLocal: 同じサイトのパスだけ") {
    List("/", "/tasks/A-1?x=1#y", "/a b", "/日本語").foreach(p => assert(SafePath.isLocal(p), p))
    List("", "tasks", "//evil.example", "/\\evil.example", "https://evil.example/", "/a\nb", "/a\tb", "/a\u007fb")
      .foreach(p => assert(!SafePath.isLocal(p), p))
    assertEquals(SafePath.orElse(Some("//evil"), "/settings"), "/settings")
    assertEquals(SafePath.orElse(Some("/x")), "/x")
    assertEquals(SafePath.orElse(None), "/")
  }

  test("SafePath.of: 使える文字を絞った版") {
    assertEquals(SafePath.of(Some(" /a/b?c=d%20e#f ")), "/a/b?c=d%20e#f")
    List("//evil", "/\\evil", "/a b", "/a\\b", "/\t/evil", "/日本語", "x").foreach(p =>
      assertEquals(SafePath.of(Some(p), "/d"), "/d", p)
    )
    assertEquals(SafePath.of(None), "/")
  }

  // ---------------------------------------------------------------- SecurityHeaders

  private val pages = HttpRoutes.of[IO] {
    case GET -> Root / "api" => Ok("{}")
    case GET -> Root / "page" =>
      Ok("<html></html>").map(_.withContentType(`Content-Type`(MediaType.text.html)))
    case GET -> Root / "own" =>
      Ok("x").map(_.putHeaders("Content-Security-Policy" -> "default-src 'self'", "X-Frame-Options" -> "SAMEORIGIN"))
  }
  private def get(app: HttpApp[IO], path: String): IO[Response[IO]] =
    app.run(Request[IO](Method.GET, Uri.unsafeFromString(path)))

  test("SecurityHeaders: 既定は厳しい CSP と nosniff・DENY・Referrer. CSP は応答が付けたものを残す") {
    val app = SecurityHeaders.app(SecurityHeaders.Policy(hsts = SecurityHeaders.hsts(true)))(pages.orNotFound)
    for {
      api <- get(app, "/api")
      own <- get(app, "/own")
      nf <- get(app, "/none")
    } yield {
      assertEquals(header(api, "Content-Security-Policy"), Some(SecurityHeaders.StrictCsp))
      assert(SecurityHeaders.StrictCsp.contains("frame-ancestors 'none'"))
      assertEquals(header(api, "X-Content-Type-Options"), Some("nosniff"))
      assertEquals(header(api, "X-Frame-Options"), Some("DENY"))
      assertEquals(header(api, "Referrer-Policy"), Some("strict-origin-when-cross-origin"))
      assertEquals(header(api, "Strict-Transport-Security"), Some("max-age=31536000"))
      assertEquals(header(own, "Content-Security-Policy"), Some("default-src 'self'"))
      assertEquals(header(own, "X-Frame-Options"), Some("DENY")) // 既定は上書き
      assertEquals(nf.status, Status.NotFound)
      assertEquals(header(nf, "X-Content-Type-Options"), Some("nosniff"))
    }
  }

  test("SecurityHeaders: http なら HSTS を付けない. keepExisting なら応答のヘッダーを残す") {
    val p = SecurityHeaders.Policy(
      hsts = SecurityHeaders.hsts(false),
      referrer = Some("same-origin"),
      csp = Some(SecurityHeaders.FrameCsp),
      extra = List("Permissions-Policy" -> "camera=()"),
      keepExisting = true
    )
    get(SecurityHeaders(p)(pages).orNotFound, "/own").map { own =>
      assertEquals(header(own, "Strict-Transport-Security"), None)
      assertEquals(header(own, "X-Frame-Options"), Some("SAMEORIGIN"))
      assertEquals(header(own, "Referrer-Policy"), Some("same-origin"))
      assertEquals(header(own, "Permissions-Policy"), Some("camera=()"))
    }
  }

  test("SecurityHeaders: HTML だけに付ける設定と、要求ごとの切り替え") {
    val html = SecurityHeaders.Policy(cspHtmlOnly = true, frameOptionsHtmlOnly = true)
    val embed = SecurityHeaders.Policy(frameOptions = None, csp = Some("frame-ancestors *"))
    val app = SecurityHeaders.routesWith(req => if req.pathInfo.renderString == "/page" then embed else html)(pages)
    for {
      api <- get(SecurityHeaders(html)(pages).orNotFound, "/api")
      page <- get(SecurityHeaders(html)(pages).orNotFound, "/page")
      emb <- get(app.orNotFound, "/page")
    } yield {
      assertEquals(header(api, "Content-Security-Policy"), None)
      assertEquals(header(api, "X-Frame-Options"), None)
      assertEquals(header(api, "X-Content-Type-Options"), Some("nosniff"))
      assertEquals(header(page, "X-Frame-Options"), Some("DENY"))
      assertEquals(header(page, "Content-Security-Policy"), Some(SecurityHeaders.StrictCsp))
      assertEquals(header(emb, "X-Frame-Options"), None)
      assertEquals(header(emb, "Content-Security-Policy"), Some("frame-ancestors *"))
    }
  }

  // ---------------------------------------------------------------- BodyLimit

  private val echo = HttpRoutes.of[IO] {
    case req @ POST -> Root / "echo" => req.as[String].flatMap(s => Ok(s.length.toString))
    case req @ POST -> Root / "caught" => req.as[String].attempt.flatMap(r => Ok(r.isLeft.toString))
    case req @ POST -> Root / "free" => req.as[String].flatMap(s => Ok(s.length.toString))
  }
  private val limited = BodyLimit(
    req => Option.unless(req.pathInfo.renderString == "/free")(10L),
    (_, max) => AppError(LibErrorCode.TooLarge, s"上限 $max")
  )(echo).orNotFound
  private def post(path: String, body: String, chunked: Boolean): IO[Response[IO]] = {
    val req = Request[IO](Method.POST, Uri.unsafeFromString(path))
    limited.run(
      if chunked then req.withBodyStream(fs2.Stream.emits(body.getBytes).chunkLimit(3).unchunks.covary[IO])
      else req.withEntity(body)
    )
  }

  test("BodyLimit: Content-Length が超えていれば読まずに 413. 超えていなければ通す") {
    for {
      ok <- post("/echo", "1234567890", chunked = false)
      ng <- post("/echo", "12345678901", chunked = false)
      free <- post("/free", "x" * 100, chunked = false)
    } yield {
      assertEquals(ok.status, Status.Ok)
      assertEquals(ng.status, Status.PayloadTooLarge)
      assertEquals(free.status, Status.Ok)
    }
  }

  test("BodyLimit: Content-Length がなくても、超えたところで止めて 413") {
    for {
      ok <- post("/echo", "1234567890", chunked = true)
      ng <- post("/echo", "12345678901", chunked = true)
      caught <- post("/caught", "12345678901", chunked = true).flatMap(_.as[String])
    } yield {
      assertEquals(ok.status, Status.Ok)
      assertEquals(ng.status, Status.PayloadTooLarge)
      assertEquals(caught, "true") // ルートが自分で受け止めたら、ルートの応答のまま
    }
  }

  test("BodyLimit.request: 1つの要求に掛ける") {
    val big = Request[IO](Method.POST).withEntity("12345678901")
    BodyLimit.request(big, 10)(new IllegalStateException("too large")).as[String].attempt.map(r =>
      assert(r.left.exists(_.getMessage == "too large"))
    )
  }

  // ---------------------------------------------------------------- Health・Spa

  test("Health: プライマリなら 200、そうでなければ 503. 例外の文は既定では出さない") {
    def call(routes: HttpRoutes[IO]) = get(routes.orNotFound, "/healthz").flatMap(r => r.as[String].map(r.status -> _))
    for {
      ok <- call(Health.routes(IO.pure(true), "ok node1"))
      ro <- call(Health.routes(IO.pure(false)))
      seen <- Ref.of[IO, Option[Throwable]](None)
      ng <- call(Health.routes(IO.raiseError(new RuntimeException("db1:5432")), onError = e => seen.set(Some(e))))
      logged <- seen.get
      detail <- call(Health.routes(IO.raiseError(new RuntimeException("db1:5432")), errorDetail = true))
    } yield {
      assertEquals(ok, Status.Ok -> "ok node1")
      assertEquals(ro, Status.ServiceUnavailable -> "database is read-only (not primary)")
      assertEquals(ng, Status.ServiceUnavailable -> "database unavailable")
      assert(logged.exists(_.getMessage == "db1:5432"))
      assertEquals(detail, Status.ServiceUnavailable -> "database unavailable: db1:5432")
    }
  }

  test("Spa: ファイルが無ければ index.html. バックエンドのパスには返さない") {
    val dir = java.nio.file.Files.createTempDirectory("spa")
    java.nio.file.Files.writeString(dir.resolve("index.html"), "<html><script>var a = 1</script></html>")
    java.nio.file.Files.createDirectory(dir.resolve("assets"))
    java.nio.file.Files.writeString(dir.resolve("assets").resolve("a.js"), "1")
    val prefix = Spa.routes(dir.toString, Spa.startsWith("api", "auth")).orNotFound
    val segment = Spa.routes(
      dir.toString,
      Spa.firstSegment("api", "auth"),
      finish = (_, r) => r.putHeaders("Content-Security-Policy" -> "x")
    ).orNotFound
    for {
      page <- get(prefix, "/tasks/1")
      asset <- get(prefix, "/assets/a.js")
      api <- get(prefix, "/api/none")
      apix1 <- get(prefix, "/apix")
      apix2 <- get(segment, "/apix")
      html <- Spa.indexHtml(dir.toString)
    } yield {
      assertEquals(page.status, Status.Ok)
      assertEquals(header(page, "Cache-Control"), Some("no-cache"))
      assertEquals(header(asset, "Cache-Control"), Some("public, max-age=31536000, immutable"))
      assertEquals(api.status, Status.NotFound)
      assertEquals(apix1.status, Status.NotFound)
      assertEquals(apix2.status, Status.Ok)
      assertEquals(header(apix2, "Content-Security-Policy"), Some("x"))
      assertEquals(Spa.inlineScriptHashes(html).map(_.take(8)), List("'sha256-"))
    }
  }
}

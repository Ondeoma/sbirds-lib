package sbirds.http

import cats.effect.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import sbirds.shared.LibErrorCode

class CsrfSpec extends CatsEffectSuite {

  private given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.noop.NoOpFactory[IO]

  private val strict = Csrf.Policy("https://app.example.com/")

  private def post(hs: (String, String)*): Request[IO] =
    Request[IO](Method.POST, uri"/api/x").putHeaders(hs.map(Header.ToRaw.keyValuesToRaw)*)

  test("originOf: scheme://host[:port] を小文字で取り出す. null や相対パスは None") {
    assertEquals(Csrf.originOf("HTTPS://App.Example.com/a/b?x=1"), Some("https://app.example.com"))
    assertEquals(Csrf.originOf("http://localhost:5173"), Some("http://localhost:5173"))
    assertEquals(Csrf.originOf("null"), None)
    assertEquals(Csrf.originOf("/path"), None)
  }

  test("既定: Origin が自分のオリジンなら通し、違えば断る（null・兄弟のサブドメイン・似た名前も）") {
    assert(Csrf.fromOwnScreen(post("Origin" -> "https://app.example.com"), strict))
    assert(Csrf.fromOwnScreen(post("Origin" -> "https://APP.example.com"), strict))
    List(
      "https://evil.example",
      "null",
      "https://other.example.com",
      "http://app.example.com",
      "https://app.example.com.evil.example",
      "https://app.example.com:8443",
      "http://localhost:5173"
    ).foreach(o => assert(Csrf.foreign(post("Origin" -> o), strict), o))
  }

  test("既定: Origin がなければ Referer を確かめる. どれもなければブラウザ以外として通す") {
    assert(Csrf.fromOwnScreen(post("Referer" -> "https://app.example.com/tasks/1"), strict))
    assert(Csrf.foreign(post("Referer" -> "https://evil.example/x"), strict))
    assert(Csrf.fromOwnScreen(post(), strict))
  }

  test("既定: Sec-Fetch-Site が same-site・cross-site なら、Origin が合っていても断る") {
    assert(Csrf.fromOwnScreen(post("Origin" -> "https://app.example.com", "Sec-Fetch-Site" -> "same-origin"), strict))
    assert(Csrf.fromOwnScreen(post("Sec-Fetch-Site" -> "none"), strict))
    assert(Csrf.foreign(post("Sec-Fetch-Site" -> "same-site"), strict))
    assert(Csrf.foreign(post("Sec-Fetch-Site" -> "cross-site"), strict))
    assert(Csrf.foreign(post("Origin" -> "https://app.example.com", "Sec-Fetch-Site" -> "cross-site"), strict))
    // Sec-Fetch-Site が same-origin でも、Origin が違えば断る（trustFetchSite でなければ）
    assert(Csrf.foreign(post("Origin" -> "https://evil.example", "Sec-Fetch-Site" -> "same-origin"), strict))
  }

  test("状態を変えないメソッドは確かめない（既定は GET・HEAD. OPTIONS は SafeWithOptions のときだけ）") {
    val get = Request[IO](Method.GET, uri"/api/x").putHeaders("Origin" -> "https://evil.example")
    val options = get.withMethod(Method.OPTIONS)
    assert(!Csrf.foreign(get, strict))
    assert(Csrf.foreign(options, strict))
    assert(!Csrf.foreign(options, strict.copy(safeMethods = Csrf.SafeWithOptions)))
  }

  test("devLocalhost: http の localhost・127.0.0.1 だけを足す") {
    val dev = Csrf.Policy("https://app.example.com", devLocalhost = true)
    assert(Csrf.fromOwnScreen(post("Origin" -> "http://localhost:5173"), dev))
    assert(Csrf.fromOwnScreen(post("Origin" -> "http://127.0.0.1:8090"), dev))
    assert(Csrf.foreign(post("Origin" -> "https://localhost:5173"), dev))
    assert(Csrf.foreign(post("Origin" -> "http://localhost.evil.example:5173"), dev))
    assert(Csrf.foreign(post("Origin" -> "http://localhost:5173@evil.example"), dev))
    assert(Csrf.foreign(post("Origin" -> "null"), dev))
  }

  test("hostFallback: Origin が Host ヘッダーと同じなら通す") {
    val p = strict.copy(hostFallback = true)
    assert(Csrf.fromOwnScreen(post("Origin" -> "https://alias.example.net", "Host" -> "alias.example.net"), p))
    assert(Csrf.foreign(post("Origin" -> "https://evil.example", "Host" -> "alias.example.net"), p))
    assert(Csrf.foreign(post("Origin" -> "https://alias.example.net", "Host" -> "alias.example.net"), strict))
  }

  test("trustFetchSite: Sec-Fetch-Site が same-origin・none なら Origin を照合しない. なければ Origin を照合する") {
    val p = strict.copy(trustFetchSite = true)
    assert(Csrf.fromOwnScreen(post("Origin" -> "https://alias.example.net", "Sec-Fetch-Site" -> "same-origin"), p))
    assert(Csrf.foreign(post("Origin" -> "https://app.example.com", "Sec-Fetch-Site" -> "same-site"), p))
    assert(Csrf.foreign(post("Origin" -> "https://evil.example"), p))
    assert(Csrf.fromOwnScreen(post("Origin" -> "https://app.example.com"), p))
  }

  test("専用のヘッダー: headerOnly はヘッダーの有無だけ. withHeader は両方を確かめる") {
    val h = Csrf.Policy.headerOnly("X-Skaka")
    assert(Csrf.fromOwnScreen(post("x-skaka" -> "1", "Origin" -> "https://evil.example"), h))
    assert(Csrf.foreign(post("Origin" -> "https://app.example.com"), h))
    assert(Csrf.foreign(post(), h))
    val both = strict.withHeader("X-Skaka")
    assert(Csrf.fromOwnScreen(post("X-Skaka" -> "1", "Origin" -> "https://app.example.com"), both))
    assert(Csrf.foreign(post("X-Skaka" -> "1", "Origin" -> "https://evil.example"), both))
    assert(Csrf.foreign(post("Origin" -> "https://app.example.com"), both))
  }

  private val app = Csrf.routes(strict, Csrf.cookieAuth("sid"), skip = _.pathInfo.renderString.startsWith("/hooks/"))(
    HttpRoutes.of[IO] { case _ => Ok("ok") }
  ).orNotFound

  test("routes: Cookie のセッションの更新系だけを確かめ、断るときは 403（SBL-1003）") {
    val evil = post("Origin" -> "https://evil.example")
    for {
      r1 <- app.run(evil.addCookie("sid", "v"))
      b1 <- r1.as[String]
      r2 <- app.run(evil) // Cookie がない
      r3 <- app.run(evil.addCookie("other", "v")) // 別の Cookie
      r4 <- app.run(evil.addCookie("sid", "v").putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, "k"))))
      r5 <- app.run(evil.addCookie("sid", "v").withMethod(Method.GET))
      r6 <- app.run(post("Origin" -> "https://app.example.com").addCookie("sid", "v"))
      r7 <- app.run(evil.addCookie("sid", "v").withUri(uri"/hooks/in"))
    } yield {
      assertEquals(r1.status, Status.Forbidden)
      assert(b1.contains(LibErrorCode.CrossSite.code), b1)
      assertEquals(List(r2, r3, r4, r5, r6, r7).map(_.status), List.fill(6)(Status.Ok))
    }
  }

  test("guard・notBearer") {
    val h = Csrf.Policy.headerOnly("X-Skaka")
    for {
      r1 <- Csrf.guard(post(), h)(Ok("ok"))
      r2 <- Csrf.guard(post("X-Skaka" -> "1"), h)(Ok("ok"))
    } yield {
      assertEquals(r1.status, Status.Forbidden)
      assertEquals(r2.status, Status.Ok)
      assert(Csrf.notBearer(post()))
      assert(!Csrf.notBearer(post().putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, "k")))))
    }
  }
}

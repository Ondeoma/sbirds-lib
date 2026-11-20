package sbirds.http

class EnvSpec extends munit.FunSuite {

  private def env(kv: (String, String)*) = new Env(Map(kv*).get)

  test("localUrl: 手元のホストだけ") {
    List(
      "http://localhost:8090",
      "http://127.0.0.1:8090/",
      "http://[::1]:8090",
      "https://app.localhost",
      "http://LOCALHOST"
    )
      .foreach(u => assert(Env.localUrl(u), u))
    List(
      "https://skea.example.com",
      "http://localhost.example.com",
      "http://127.0.0.1.example.com",
      "http://10.0.0.5:8090",
      "http://example.com/?u=http://localhost",
      "http://localhost@example.com",
      "localhost",
      ""
    ).foreach(u => assert(!Env.localUrl(u), u))
  }

  test("devLogin: 公開 URL が手元のときだけ有効. 手元でなければ起動させない") {
    assertEquals(env().devLogin("https://skea.example.com"), Right(false))
    assertEquals(env("DEV_LOGIN" -> "false").devLogin("http://localhost:8090"), Right(false))
    assertEquals(env("DEV_LOGIN" -> "true").devLogin("http://localhost:8090"), Right(true))
    assert(env("DEV_LOGIN" -> "true").devLogin("https://skea.example.com").isLeft)
    assert(env("DEV_LOGIN" -> "TRUE").devLogin("http://192.168.1.10:8090").isLeft)
  }
}

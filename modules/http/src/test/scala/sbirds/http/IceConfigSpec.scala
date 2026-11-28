package sbirds.http

import io.circe.syntax.*
import sbirds.shared.IceServer

import java.time.Instant

class IceConfigSpec extends munit.FunSuite {

  private def env(kv: (String, String)*) = new Env(kv.toMap.get)

  test("設定が無ければ公開の STUN だけ") {
    val c = IceConfig.fromEnv(env()).toOption.get
    assertEquals(c.forPeer("u1", Instant.EPOCH), IceConfig.DefaultServers)
  }

  test("TURN の一時的な認証情報（期限:名前、HMAC-SHA1）") {
    val c =
      IceConfig.fromEnv(env("TURN_URLS" -> "turn:t.example.com:3478", "TURN_SECRET" -> "s", "TURN_TTL_HOURS" -> "1"))
        .toOption.get
    val turn = c.forPeer("u1", Instant.ofEpochSecond(1000)).last
    assertEquals(turn.urls, List("turn:t.example.com:3478"))
    assertEquals(turn.username, Some("4600:u1"))
    assertEquals(turn.credential, Some(IceConfig.turnPassword("s", "4600:u1")))
  }

  test("ICE_SERVERS を読む（urls は文字列でもよい）。読めなければ Left") {
    val c = IceConfig.fromEnv(env("ICE_SERVERS" ->
      """[{"urls": "stun:a"}, {"urls": ["turn:b"], "username": "x", "credential": "y"}]"""))
    assertEquals(
      c.map(_.servers),
      Right(List(IceServer(List("stun:a")), IceServer(List("turn:b"), Some("x"), Some("y"))))
    )
    assert(IceConfig.fromEnv(env("ICE_SERVERS" -> "{")).isLeft)
  }

  test("JSON では無い項目を書かない") {
    assertEquals(IceServer(List("stun:a")).asJson.noSpaces, """{"urls":["stun:a"]}""")
  }
}

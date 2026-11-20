package sbirds.http

class IdpErrorLogSpec extends munit.FunSuite {

  test("idpErrorLog: 制御文字を除き、長さを切る（ログの行を偽造させない）") {
    assertEquals(OidcLoginFlow.idpErrorLog("access_denied", None), "IdP error: access_denied")
    assertEquals(
      OidcLoginFlow.idpErrorLog("access_denied", Some("no\r\n[SKEA-0000] 200 fake")),
      "IdP error: access_denied (no  [SKEA-0000] 200 fake)"
    )
    val long = OidcLoginFlow.idpErrorLog("e" * 500, Some("d" * 5000))
    assertEquals(long.length, "IdP error: ".length + 100 + " ()".length + 300)
  }
}

package sbirds.links

class LinksSpec extends munit.FunSuite {

  test("LinkConfig.fromEnv: 3つ揃えたときだけ読み、INTERNAL_URL を省くと URL") {
    val env = Map(
      "STUI_URL" -> "https://chat.example.com/",
      "STUI_CLIENT_ID" -> "skuaka",
      "STUI_CLIENT_SECRET" -> "secret-0123456789"
    )
    assertEquals(
      LinkConfig.fromEnv("STUI", "stui", "read write", env.get),
      Right(Some(LinkConfig(
        "stui",
        "https://chat.example.com",
        "https://chat.example.com",
        "skuaka",
        "secret-0123456789",
        "read write"
      )))
    )
    assertEquals(
      LinkConfig.fromEnv("STUI", "stui", env = (env + ("STUI_INTERNAL_URL" -> "http://stui:8098")).get).map(
        _.map(_.internalUrl)
      ),
      Right(Some("http://stui:8098"))
    )
    assertEquals(LinkConfig.fromEnv("STUI", "stui", env = Map.empty[String, String].get), Right(None))
    assert(LinkConfig.fromEnv("STUI", "stui", env = Map("STUI_URL" -> "https://x").get).isLeft)
  }

  test("受信 Webhook の URL の形と、本文の Markdown") {
    assert(StuiClient.isHookUrl("https://chat.example.com/hooks/abcDEF_123-x"))
    assert(StuiClient.isHookUrl("http://localhost:8098/stui/hooks/abc"))
    assert(!StuiClient.isHookUrl("https://chat.example.com/api/hooks"))
    assert(!StuiClient.isHookUrl("https://chat.example.com/hooks/abc?x=1"))
    assert(!StuiClient.isHookUrl("javascript:alert(1)//hooks/abc"))
    assertEquals(StuiMessage("会議", "10:00〜", "https://c/e").markdown, "**[会議](https://c/e)**\n\n10:00〜")
    assertEquals(StuiMessage("会議", "", "").markdown, "**会議**")
    assertEquals(StuiMessage("[定例] 会議", "", "https://c/e").markdown, "**[\\[定例\\] 会議](https://c/e)**")
  }

  test("StuiMessage.plain: Markdown の記号をエスケープし、改行を空白にする") {
    assertEquals(StuiMessage.plain("a*b_[x](y)\r\n#c"), "a\\*b\\_\\[x\\]\\(y\\) \\#c")
    assertEquals(StuiMessage.plain("ふつうの文"), "ふつうの文")
  }
}

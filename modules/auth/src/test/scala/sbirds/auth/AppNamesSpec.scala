package sbirds.auth

import io.circe.Json
import io.circe.syntax.*

class AppNamesSpec extends munit.FunSuite {

  test("ID トークンのクレームから表示名とロゴを読む") {
    val claims = Json.obj(
      "sbirds_apps" -> Map("skea" -> "開発の課題", "stui" -> "チャット").asJson,
      "sbirds_app" -> "開発の課題".asJson,
      "sbirds_logos" -> Map("stui" -> "https://idp/app-icons/1").asJson,
      "sbirds_app_logo" -> "https://idp/brand-logos/2".asJson
    )
    assertEquals(
      AppNames.fromClaims(claims),
      Map(
        "skea" -> "開発の課題",
        "stui" -> "チャット",
        "_self" -> "開発の課題",
        "_logo:stui" -> "https://idp/app-icons/1",
        "_logo:_self" -> "https://idp/brand-logos/2"
      )
    )
  }

  test("クレームがなければ空") {
    assertEquals(AppNames.fromClaims(Json.obj("sub" -> "x".asJson)), Map.empty[String, String])
    assertEquals(AppNames.fromIdToken("壊れた値"), Map.empty[String, String])
  }
}

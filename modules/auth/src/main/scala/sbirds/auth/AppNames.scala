package sbirds.auth

import io.circe.Json

/**
 * sbirds のアプリの表示名（sruru に登録したアプリケーションの名前. グループごとの上書きを反映済み）.
 * sruru は sbirds のクライアントの ID トークンに `sbirds_apps`（slug → 表示名）・`sbirds_app`（そのクライアント自身の表示名）・
 * `sbirds_logos`（slug → ロゴの URL）・`sbirds_app_logo`（自身のロゴの URL）を入れる.
 * ロゴは同じ Map に `_logo:<slug>`・`_logo:_self` のキーで入れる（画面の sbirds.web.AppNames が読む）.
 * 各サービスはログインのときに保存した ID トークンから読み、/api/me で画面に渡す.
 */
object AppNames {

  val AppsClaim = "sbirds_apps"
  val SelfClaim = "sbirds_app"
  val LogosClaim = "sbirds_logos"
  val SelfLogoClaim = "sbirds_app_logo"

  /** ロゴの URL のキーの頭（`_logo:stui`・`_logo:_self`） */
  val LogoPrefix = "_logo:"

  /** ID トークン（JWT）から表示名を読む（読めなければ空）. self は SelfKey の名前で入れる. */
  def fromIdToken(
    idToken: String
  ): Map[String, String] =
    IdToken.decodeClaims(idToken).toOption.fold(Map.empty[String, String])(fromClaims)

  def fromClaims(
    claims: Json
  ): Map[String, String] = {
    val c = claims.hcursor
    val apps = c.downField(AppsClaim).as[Map[String, String]].getOrElse(Map.empty)
    val logos = c.downField(LogosClaim).as[Map[String, String]].getOrElse(Map.empty).map(
      (
        k,
        v
      ) => (LogoPrefix + k) -> v
    )
    apps ++ logos ++
      c.get[String](SelfClaim).toOption.filter(_.trim.nonEmpty).map(SelfKey -> _) ++
      c.get[String](SelfLogoClaim).toOption.filter(_.trim.nonEmpty).map((LogoPrefix + SelfKey) -> _)
  }

  /** そのサービス自身の表示名のキー */
  val SelfKey = "_self"
}

package sbirds.web

import io.circe.{Decoder, Json}
import io.circe.syntax.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** API トークン（/api/tokens の 1 つ. oauthClient があれば、OAuth の認可で発行した連携のトークン）. */
final case class ApiTokenInfo(id: String,
                              name: String,
                              prefix: String,
                              scope: String,
                              createdAt: String,
                              expiresAt: Option[String],
                              lastUsedAt: Option[String],
                              oauthClient: Option[String] = None) derives Decoder

/** API トークンの一覧・作成・失効（parts/ApiTokenSettings.st が使う. 各サービスの /api/tokens）. */
object ApiTokens {

  val Read = "read"
  val Write = "write"

  def label(scope: String): String = if (scope == Write) "読み書き" else "参照のみ"

  // 401 はログイン画面へ（ApiBase の既定のまま）
  private object Api extends ApiBase

  def list(): Future[List[ApiTokenInfo]] = Api.get[List[ApiTokenInfo]]("/api/tokens")

  /** 作る（days が None なら期限なし）. 秘密の値（今だけ見られる）を返す. */
  def create(name: String, scope: String, days: Option[Int]): Future[String] =
    Api.post[Json](
      "/api/tokens",
      Json.obj("name" -> name.asJson, "scope" -> scope.asJson, "expiresInDays" -> days.asJson)
    ).map(_.hcursor.get[String]("token").fold(e => throw ApiError(0, e.getMessage), identity))

  def revoke(id: String): Future[Unit] = Api.delete(s"/api/tokens/${Api.seg(id)}")

}

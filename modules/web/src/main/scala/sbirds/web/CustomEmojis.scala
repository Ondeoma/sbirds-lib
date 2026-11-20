package sbirds.web

import io.circe.{Decoder, Json}
import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** カスタム絵文字（/api/emoji の 1 つ）. */
final case class CustomEmojiItem(name: String, url: String, createdBy: Option[String]) derives Decoder

/**
 * カスタム絵文字（リアクション用. /api/emoji から一度だけ読む. 登録・削除の後は force で読み直す）.
 * parts/EmojiPicker.st が使う. 画像は /api/emoji/{name}/image.
 */
object CustomEmojis {

  val custom: RV[List[CustomEmojiItem]] = RV(Nil)
  private var loaded = false

  // 401 はログイン画面へ（ApiBase の既定のまま）
  private object Api extends ApiBase

  def load(force: Boolean = false): Unit = if (!loaded || force) {
    loaded = true
    Api.get[List[CustomEmojiItem]]("/api/emoji").foreach(custom := _)
  }

  /** 登録する（name は半角の英小文字・数字・_ + - の 2〜32 文字）. */
  def add(name: String, file: dom.File): Future[Unit] =
    Api.postFile[Json](s"/api/emoji?name=${Api.enc(name)}", file, file.name).map(_ => ())

  def remove(name: String): Future[Unit] = Api.delete(s"/api/emoji/$name")

  /** ":name:" ならその名前. */
  def nameOf(e: String): Option[String] =
    Option(e).filter(_.matches("^:[a-z0-9_+-]{2,32}:$")).map(_.drop(1).dropRight(1))

  /** ":name:" ならカスタム絵文字の画像の URL. */
  def image(e: String): Option[String] = nameOf(e).map(n => s"/api/emoji/$n/image")

}

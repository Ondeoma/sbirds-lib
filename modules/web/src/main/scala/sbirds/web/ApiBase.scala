package sbirds.web

import io.circe.*
import io.circe.parser.parse
import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** API の呼び出しのエラー（status が 0 のときは応答の JSON が読めなかった）. */
final case class ApiError(status: Int, message: String) extends Exception(message)

/**
 * バックエンドの REST API の呼び出しの共通部分. 各サービスの `object Api extends ApiBase` に、サービスごとの API を足す.
 *  - 同じオリジンの Cookie と、[[Fetch.extraHeaders]]（CSRF 対策のヘッダーなど）を付ける.
 *  - 401 はログイン画面へ移す（[[loginUrl]]）. エラーの応答は {"error": ...} の文言の [[ApiError]] にする.
 */
trait ApiBase {

  /** 401 のときに移るログインの URL（back はログインの後に戻る場所）. */
  protected def loginUrl(back: String): String = "/auth/login?returnTo=" + enc(back)

  /** 401 でもログイン画面へ移さない画面（ログイン画面自身など）. */
  protected def stayOnUnauthorized: Boolean = dom.window.location.pathname.startsWith("/login")

  def init(method: dom.HttpMethod, body: Option[String] = None): dom.RequestInit = Fetch.init(method, body)

  protected def request(method: dom.HttpMethod, url: String, body: Option[Json] = None): Future[Option[Json]] =
    dom.fetch(url, init(method, body.map(_.noSpaces))).toFuture.flatMap(handle)

  /** fetch の応答を、本文の JSON（204・空ならNone）か ApiError にする. */
  protected def handle(res: dom.Response): Future[Option[Json]] =
    if (res.status == 401) {
      val back = dom.window.location.pathname + dom.window.location.search
      if (!stayOnUnauthorized) dom.window.location.href = loginUrl(back)
      Future.failed(ApiError(401, "ログインしてください"))
    } else if (!res.ok) {
      res.text().toFuture.recover(_ => "").flatMap { t =>
        val msg = parse(
          t
        ).toOption.flatMap(_.hcursor.get[String]("error").toOption).getOrElse(s"${res.status} ${res.statusText}")
        Future.failed(ApiError(res.status, msg))
      }
    } else if (res.status == 204) Future.successful(None)
    else res.text().toFuture.map(t => if (t.isEmpty) None else Some(parse(t).fold(throw _, identity)))

  protected def as[A: Decoder](j: Option[Json]): A =
    j.getOrElse(Json.Null).as[A].fold(e => throw ApiError(0, e.getMessage), identity)

  def get[A: Decoder](url: String): Future[A] = request(dom.HttpMethod.GET, url).map(as[A])
  def post[A: Decoder](url: String, body: Json = Json.obj()): Future[A] =
    request(dom.HttpMethod.POST, url, Some(body)).map(as[A])
  def put[A: Decoder](url: String, body: Json): Future[A] = request(dom.HttpMethod.PUT, url, Some(body)).map(as[A])
  def patch[A: Decoder](url: String, body: Json): Future[A] = request(dom.HttpMethod.PATCH, url, Some(body)).map(as[A])
  def send(method: dom.HttpMethod, url: String, body: Option[Json] = None): Future[Unit] =
    request(method, url, body).map(_ => ())
  def delete(url: String): Future[Unit] = send(dom.HttpMethod.DELETE, url)
  def deleteWith(url: String, body: Json): Future[Unit] = send(dom.HttpMethod.DELETE, url, Some(body))
  def postUnit(url: String, body: Json = Json.obj()): Future[Unit] = send(dom.HttpMethod.POST, url, Some(body))
  def putUnit(url: String, body: Json): Future[Unit] = send(dom.HttpMethod.PUT, url, Some(body))
  def patchUnit(url: String, body: Json): Future[Unit] = send(dom.HttpMethod.PATCH, url, Some(body))

  /**
   * 本文をそのまま送る（画像・ファイルなど. contentType を指定しなければブラウザに任せる. FormData は指定しない）.
   * 応答の JSON を A として読む.
   */
  def sendRaw[A: Decoder](method: dom.HttpMethod,
                          url: String,
                          body: dom.BodyInit,
                          contentType: Option[String] = None): Future[A] = {
    val i = init(method)
    contentType.foreach(ct => i.headers.asInstanceOf[js.Dictionary[String]]("Content-Type") = ct)
    i.body = body
    dom.fetch(url, i).toFuture.flatMap(handle).map(as[A])
  }

  def postRaw[A: Decoder](url: String, body: dom.BodyInit, contentType: Option[String] = None): Future[A] =
    sendRaw[A](dom.HttpMethod.POST, url, body, contentType)

  def putRaw[A: Decoder](url: String, body: dom.BodyInit, contentType: Option[String] = None): Future[A] =
    sendRaw[A](dom.HttpMethod.PUT, url, body, contentType)

  /** ファイルを multipart/form-data（field の欄. 既定は file）で POST する. 応答の JSON を A として読む. */
  def postFile[A: Decoder](url: String, file: dom.Blob, name: String, field: String = "file"): Future[A] = {
    val fd = new dom.FormData()
    fd.append(field, file, name)
    postRaw[A](url, fd)
  }

  def enc(s: String): String = js.URIUtils.encodeURIComponent(s)

  /** パスの1区切り（UUID・名前など）を URL に埋め込む形にする. */
  def seg(s: Any): String = enc(String.valueOf(s))

  /** クエリ文字列（値が空・None のものは付けない）. 例: query("q" -> Some("猫"), "page" -> None) → "?q=%E7%8C%AB" */
  def query(params: (String, Option[String])*): String = {
    val q = params.collect { case (k, Some(v)) if v.nonEmpty => s"$k=${enc(v)}" }
    if (q.isEmpty) "" else q.mkString("?", "&", "")
  }

}

package sbirds.web

import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 共通部品（Pwa・Observe）からの API の呼び出し. 各サービスの Api とは別に、最小限だけを持つ. */
object Fetch {

  /** すべての要求に付けるヘッダー（例: CSRF 対策の X-Skaka）. 画面の起動時に設定する. */
  var extraHeaders: Map[String, String] = Map.empty

  /** fetch の設定（同じオリジンの Cookie を付ける. body は JSON の文字列）. */
  def init(
    method: dom.HttpMethod,
    body: Option[String] = None
  ): dom.RequestInit = {
    val i = new dom.RequestInit {}
    val hs = js.Dictionary[String](extraHeaders.toSeq*)
    i.method = method
    i.credentials = dom.RequestCredentials.`same-origin`
    body.foreach { b =>
      hs("Content-Type") = "application/json"
      i.body = b
    }
    i.headers = hs
    i
  }

  /** JSON を読む（失敗したら Future の失敗）. */
  def getJson(
    url: String
  ): Future[io.circe.Json] =
    dom.fetch(url, init(dom.HttpMethod.GET)).toFuture.flatMap { res =>
      if (!res.ok) Future.failed(new Exception(s"${res.status} ${res.statusText}"))
      else res.text().toFuture.map(t => io.circe.parser.parse(t).fold(throw _, identity))
    }
}

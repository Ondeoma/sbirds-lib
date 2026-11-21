package sbirds.links

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import sbirds.db.OutboxResult

import java.util.UUID
import scala.concurrent.duration.*

/** stui に投稿する通知. title は見出し（url があればリンクになる）、text は Markdown. */
final case class StuiMessage(title: String, text: String, url: String) {

  /**
   * 受信 Webhook の本文の text（見出しを太字のリンクにして、本文を続ける）. 見出しはここで plain にする.
   * 通知の API（StuiClient.notify）には title をそのまま送る（stui の側でエスケープする）.
   */
  def markdown: String = {
    val t = StuiMessage.plain(title)
    (if url.nonEmpty then s"**[$t]($url)**" else s"**$t**") + (if text.isEmpty then "" else "\n\n" + text)
  }
}

object StuiMessage {

  /** 差出人・件名など、利用者が書いた文字を Markdown の本文に埋めるときに無害にする（記号をバックスラッシュでエスケープし、改行を空白にする）. */
  def plain(s: String): String =
    s.replaceAll("[\\r\\n]+", " ").replaceAll("([\\\\`*_\\[\\]()<>#!|~])", "\\\\$1")
}

/** 投稿先に選べる stui のチャンネル（admin はチャンネルの管理者か）. */
final case class StuiChannel(id: String, name: String, kind: String, admin: Boolean)

/**
 * stui（チャット）への投稿（docs は stui の docs/integrations.md）.
 *  - notify: 本人が連携したトークンで、stui のボット（このサービスの名前）から本人への DM、または本人が参加しているチャンネルへ投稿する
 *  - channels: 投稿先に選べるチャンネル（本人が参加している公開・非公開のもの）
 * link の provider は "stui".
 */
final class StuiClient(val link: LinkClient) {

  /** 送れたか（Sent）・送り直すか（Retry）・送り直しても無駄か（Skip. 理由）と、stui の応答の状態. */
  def notify(uid: UUID, m: StuiMessage, channel: Option[String] = None): IO[(OutboxResult, Option[Int])] = {
    val body = Json.obj(
      "title" -> m.title.take(300).asJson,
      "text" -> m.text.take(StuiClient.MaxText).asJson,
      "url" -> m.url.asJson,
      "channel" -> channel.asJson
    ).dropNullValues
    link.call(uid, Request[IO](Method.POST, link.api("/api/apps/notify")).withEntity(body))(r =>
      IO.pure(r.status.code)
    ).map {
      case Right(code) => (OutboxResult.Sent, Some(code))
      case Left(LinkFailure.NotConnected) => (OutboxResult.Skip("stui と連携していません"), None)
      case Left(LinkFailure.Expired) => (OutboxResult.Skip("stui との連携が切れています（連携し直してください）"), Some(401))
      case Left(LinkFailure.Rejected(403)) =>
        (OutboxResult.Skip("このチャンネルには投稿できません（チャンネルを抜けたか、アーカイブされたかもしれません）"), Some(403))
      case Left(LinkFailure.Rejected(404)) => (OutboxResult.Skip("チャンネルが見つかりません"), Some(404))
      case Left(LinkFailure.Rejected(code)) => (OutboxResult.Retry(s"HTTP $code"), Some(code))
      case Left(LinkFailure.Unreachable(e)) =>
        (OutboxResult.Retry(s"stui に接続できませんでした（${e.getClass.getSimpleName}）"), None)
    }
  }

  /** 投稿先に選べるチャンネル（参加している公開・非公開のもの. アーカイブしたものは除く. 名前の順）. */
  def channels(uid: UUID): IO[Either[LinkFailure, List[StuiChannel]]] =
    link.call(uid, Request[IO](Method.GET, link.api("/api/channels")))(_.as[Json].map(j =>
      j.asArray.getOrElse(Vector.empty).toList.flatMap { x =>
        val h = x.hcursor
        for {
          id <- h.get[String]("id").toOption
          name <- h.get[String]("name").toOption
          kind <- h.get[String]("kind").toOption if kind == "public" || kind == "private"
          if !h.get[Boolean]("archived").getOrElse(false) && h.get[Boolean]("member").getOrElse(true)
        } yield StuiChannel(id, name, kind, h.get[String]("role").toOption.contains("admin"))
      }.sortBy(_.name.toLowerCase)
    ))
}

object StuiClient {

  /** 本文の長さの上限（stui のメッセージに収まるように）. */
  val MaxText = 2000

  /** 受信 Webhook の URL の形（{stui の URL}/hooks/{トークン}）. */
  private val hookRe = "^https?://[^/?#]+(/[^?#]*)?/hooks/[A-Za-z0-9_=-]+$".r

  def isHookUrl(url: String): Boolean = hookRe.matches(url.trim)

  /**
   * stui の受信 Webhook に送る（トークンは使わない. URL が鍵）. username は投稿する名前.
   * http は送り先を確かめるクライアント（sbirds.net.NetGuard.client）を渡す.
   */
  def postHook(http: Client[IO], url: String, m: StuiMessage, username: String): IO[(OutboxResult, Option[Int])] =
    Uri.fromString(url.trim) match {
      case Left(_) => IO.pure((OutboxResult.Skip("受信 Webhook の URL が正しくありません"), None))
      case Right(u) =>
        val body = Json.obj("text" -> m.markdown.take(MaxText + 400).asJson, "username" -> username.asJson)
        http.run(Request[IO](Method.POST, u).withEntity(body)).use(r => IO.pure(r.status.code))
          .timeout(15.seconds).attempt.map {
            case Right(code) if code >= 200 && code < 300 => (OutboxResult.Sent, Some(code))
            case Right(404) =>
              (OutboxResult.Skip("stui の受信 Webhook が見つかりません（消されたかもしれません. URL を設定し直してください）"), Some(404))
            case Right(403) => (OutboxResult.Skip("このチャンネルには投稿できません（アーカイブされたかもしれません）"), Some(403))
            case Right(code) => (OutboxResult.Retry(s"HTTP $code"), Some(code))
            // 例外の文（内部のホスト名など）は残さない
            case Left(e) => (OutboxResult.Retry(s"stui に接続できませんでした（${e.getClass.getSimpleName}）"), None)
          }
    }
}

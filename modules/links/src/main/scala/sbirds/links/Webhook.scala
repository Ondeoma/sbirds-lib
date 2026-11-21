package sbirds.links

import cats.effect.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import sbirds.db.OutboxResult
import sbirds.net.NetGuard

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * 利用者が決めた URL への Webhook の送信（本文は JSON. HMAC-SHA256 の署名付き）.
 * ヘッダー（service が "Skereru" なら）:
 *  - X-Skereru-Event: イベントの名前
 *  - X-Skereru-Delivery: 送信の ID（送り直しても同じ. 受け手が重複を捨てるのに使う）
 *  - X-Skereru-Signature: "sha256=" + hex(HMAC-SHA256(secret, 本文))
 * 宛先は送るたびに policy で確かめる. http は NetGuard.client（確かめた IP にそのままつなぐ）を渡す.
 */
object Webhook {

  /** 送る中身. body は JSON の文字列（署名はこの文字列そのもので計算する）. */
  final case class Send(url: String, event: String, deliveryId: String, body: String, secret: String)

  def signature(secret: String, body: String): String =
    "sha256=" + sbirds.auth.Crypto.hmacHex(secret, body.getBytes(StandardCharsets.UTF_8))

  /** 送る要求（service は X-{service}-… のヘッダーの名前と User-Agent に使う）. */
  def request(service: String, s: Send): Either[String, Request[IO]] =
    Uri.fromString(s.url.trim).left.map(_ => "URL が正しくありません").map(u =>
      Request[IO](Method.POST, u)
        .withEntity(s.body)(using EntityEncoder.stringEncoder)
        .withContentType(`Content-Type`(MediaType.application.json))
        .putHeaders(
          s"X-$service-Event" -> s.event,
          s"X-$service-Delivery" -> s.deliveryId,
          s"X-$service-Signature" -> signature(s.secret, s.body),
          "User-Agent" -> s"${service.toLowerCase}-webhook"
        )
    )

  /**
   * 送る. 2xx は Sent、410（もう要らない）と宛先を断ったときは Skip、そのほかは Retry. 例外の文（内部のホスト名など）は残さない.
   * 応答の状態も返す（記録用）.
   */
  def post(http: Client[IO],
           policy: NetGuard.Policy,
           service: String,
           s: Send,
           timeout: FiniteDuration = 15.seconds): IO[(OutboxResult, Option[Int])] =
    request(service, s) match {
      case Left(m) => IO.pure((OutboxResult.Skip(m), None))
      case Right(req) =>
        NetGuard.check(req.uri, policy).flatMap {
          case Left(r) => IO.pure((OutboxResult.Skip(r.message), None))
          case Right(_) =>
            http.run(req).use(r => IO.pure(r.status.code)).timeout(timeout).attempt.map {
              case Right(c) if c >= 200 && c < 300 => (OutboxResult.Sent, Some(c))
              case Right(410) => (OutboxResult.Skip("HTTP 410（送り先がもう受け付けていません）"), Some(410))
              case Right(c) => (OutboxResult.Retry(s"HTTP $c"), Some(c))
              case Left(r: NetGuard.Rejected) => (OutboxResult.Skip(r.message), None)
              case Left(e) => (OutboxResult.Retry(s"接続できませんでした（${e.getClass.getSimpleName}）"), None)
            }
        }
    }
}

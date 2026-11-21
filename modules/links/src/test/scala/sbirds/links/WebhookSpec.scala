package sbirds.links

import cats.effect.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.typelevel.ci.CIString
import sbirds.db.OutboxResult
import sbirds.net.NetGuard

/** 署名付きの Webhook の送信（署名・ヘッダー・応答の状態の扱い・宛先の確認）. */
class WebhookSpec extends CatsEffectSuite {

  private val body = """{"event":"test"}"""
  private val send = Webhook.Send("https://hooks.example/in", "test", "d-1", body, "secret-key")

  /** 送った要求と本文を覚えて、status を返す相手. */
  private def target(status: Status) = {
    val got = new java.util.concurrent.atomic.AtomicReference[(Request[IO], String)]()
    val http = Client.fromHttpApp(HttpApp[IO](r =>
      r.bodyText.compile.string.map(b => { got.set(r -> b); Response[IO](status) })
    ))
    (http, got)
  }

  test("署名は本文の HMAC-SHA256（sha256=…）") {
    // python3: hmac.new(b'secret-key', b'{"event":"test"}', hashlib.sha256).hexdigest()
    assertEquals(
      Webhook.signature("secret-key", body),
      "sha256=c0d139d0f6de6e9a974ec16b616146bb98ee1badeb07dc6a55f12032eca15267"
    )
  }

  test("本文をそのまま送り、X-{サービス}-Event・Delivery・Signature を付ける. 2xx は Sent") {
    val (http, got) = target(Status.NoContent)
    Webhook.post(http, NetGuard.Policy.allowAll, "Skereru", send).map { r =>
      assertEquals(r, (OutboxResult.Sent, Some(204)))
      val (req, b) = got.get()
      assertEquals(b, body)
      def h(n: String) = req.headers.get(CIString(n)).map(_.head.value)
      assertEquals(h("X-Skereru-Event"), Some("test"))
      assertEquals(h("X-Skereru-Delivery"), Some("d-1"))
      assertEquals(h("X-Skereru-Signature"), Some(Webhook.signature("secret-key", body)))
      assertEquals(req.contentType.map(_.mediaType), Some(MediaType.application.json))
    }
  }

  test("410 は送り直さない（Skip）、ほかの失敗は送り直す（Retry）") {
    for {
      gone <- Webhook.post(target(Status.Gone)._1, NetGuard.Policy.allowAll, "Skereru", send)
      down <- Webhook.post(target(Status.ServiceUnavailable)._1, NetGuard.Policy.allowAll, "Skereru", send)
    } yield {
      assert(gone._1.isInstanceOf[OutboxResult.Skip], gone.toString)
      assertEquals(down, (OutboxResult.Retry("HTTP 503"), Some(503)))
    }
  }

  test("内部のアドレスへは送らない（Skip）") {
    val (http, got) = target(Status.Ok)
    Webhook.post(http, NetGuard.Policy.publicOnly, "Skereru", send.copy(url = "http://127.0.0.1:8080/in")).map { r =>
      assert(r._1.isInstanceOf[OutboxResult.Skip], r.toString)
      assertEquals(got.get(), null)
    }
  }
}

package sbirds.push

import cats.effect.*
import io.circe.{Decoder, Json}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci.CIString
import sbirds.http.Errors
import sbirds.shared.ErrorCode

import java.util.UUID

/**
 * プッシュ通知（PWA）の購読.
 *  - `GET /api/push`: VAPID の公開鍵と、自分の購読の数
 *  - `POST /api/push/subscriptions`（PushSubscription.toJSON() の形）: この端末で受け取る
 *  - `DELETE /api/push/subscriptions`（{"endpoint": …}）: この端末で受け取るのをやめる
 *
 * host は各サービスの認証の入口. notAllowed は許していないプッシュのサービスの endpoint、invalid は鍵・endpoint が長すぎるときのコード.
 * deviceLimit は「1人の端末の数の上限と、超えたときのコード」（None なら数えない. 古いものから消す方式は PushStore の maxPerUser）.
 * 別のユーザーの端末を上書きしない設定は PushStore の keepOwner.
 * 2 つ目の引数は VAPID の公開鍵（自前の送信の仕組みを持つサービス向け）か、lib の PushService.
 */
final class PushRoutes(store: PushStore,
                       publicKey: String,
                       host: PushRoutes.Host,
                       notAllowed: ErrorCode,
                       invalid: ErrorCode,
                       deviceLimit: Option[(Int, ErrorCode)] = None) extends Http4sDsl[IO] {

  def this(store: PushStore,
           push: PushService,
           host: PushRoutes.Host,
           notAllowed: ErrorCode,
           invalid: ErrorCode,
           deviceLimit: Option[(Int, ErrorCode)]) = this(store, push.publicKey, host, notAllowed, invalid, deviceLimit)

  def this(store: PushStore, push: PushService, host: PushRoutes.Host, notAllowed: ErrorCode, invalid: ErrorCode) =
    this(store, push.publicKey, host, notAllowed, invalid, None)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "push" => getPush(req)
    case req @ POST -> Root / "api" / "push" / "subscriptions" => postPushSubscriptions(req)
    case req @ DELETE -> Root / "api" / "push" / "subscriptions" => deletePushSubscriptions(req)
  }

  private final case class Keys(p256dh: String, auth: String) derives Decoder
  private final case class SubscribeReq(endpoint: String, keys: Keys) derives Decoder
  private final case class EndpointReq(endpoint: String) derives Decoder

  private def getPush(req: Request[IO]): IO[Response[IO]] =
    host.authed(req)(user =>
      store.count(user).flatMap(n =>
        Ok(Json.obj("publicKey" -> Json.fromString(publicKey), "subscriptions" -> Json.fromInt(n)))
      )
    )

  private def postPushSubscriptions(req: Request[IO]): IO[Response[IO]] =
    host.interactive(req)(user =>
      host.body[SubscribeReq](req) { r =>
        if !WebPush.allowedEndpoint(r.endpoint) then Errors.respond(notAllowed)
        else if r.keys.p256dh.length > 200 || r.keys.auth.length > 100 || r.endpoint.length > 2000 then
          Errors.respond(invalid)
        else {
          val subscribe = store.subscribe(
            user,
            Subscription(r.endpoint, r.keys.p256dh, r.keys.auth),
            req.headers.get(CIString("User-Agent")).map(_.head.value.take(300))
          ) *> NoContent()
          deviceLimit.fold(subscribe)((max, code) =>
            store.count(user).flatMap(n => if n >= max then Errors.respond(code) else subscribe)
          )
        }
      }
    )

  private def deletePushSubscriptions(req: Request[IO]): IO[Response[IO]] =
    host.authed(req)(user => host.body[EndpointReq](req)(r => store.unsubscribe(user, r.endpoint) *> NoContent()))
}

object PushRoutes {

  /** 各サービスの認証と本文の読み取り（RouteSupport の authed・body などをそのまま渡す）. */
  trait Host {

    /** ログインしていれば、そのユーザーの ID で f を動かす（APIキーでもよい）. */
    def authed(req: Request[IO])(f: UUID => IO[Response[IO]]): IO[Response[IO]]

    /** 端末の登録に使う入口（画面のログインだけに絞るサービスは、ここで絞る）. */
    def interactive(req: Request[IO])(f: UUID => IO[Response[IO]]): IO[Response[IO]]

    /** 本文（JSON）を読む. 読めなければ、そのサービスのエラーの応答. */
    def body[A: Decoder](req: Request[IO])(f: A => IO[Response[IO]]): IO[Response[IO]]
  }
}

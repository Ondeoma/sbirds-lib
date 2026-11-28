package sbirds.http

import sbirds.shared.IceServer

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.duration.*

/**
 * WebRTC の ICE サーバーの設定（skaka の会議・stui のボイスチャンネル）. servers は STUN（と固定の TURN）.
 * turnUrls と turnSecret があれば、coturn の use-auth-secret（TURN REST API）の一時的な認証情報（ttl の間有効）を参加者ごとに作る.
 */
final case class IceConfig(servers: List[IceServer] = IceConfig.DefaultServers,
                           turnUrls: List[String] = Nil,
                           turnSecret: Option[String] = None,
                           ttl: FiniteDuration = 12.hours) {

  /** 参加者（name は利用者の ID など. TURN のユーザー名に入る）に渡す ICE サーバー. */
  def forPeer(name: String, now: Instant): List[IceServer] =
    servers ++ turnSecret.filter(_ => turnUrls.nonEmpty).toList.map { secret =>
      val user = s"${now.getEpochSecond + ttl.toSeconds}:$name"
      IceServer(turnUrls, Some(user), Some(IceConfig.turnPassword(secret, user)))
    }
}

object IceConfig {
  val DefaultServers: List[IceServer] = List(IceServer(List("stun:stun.l.google.com:19302")))

  /** TURN REST API のパスワード（base64(HMAC-SHA1(secret, user))）. */
  def turnPassword(secret: String, user: String): String = {
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"))
    Base64.getEncoder.encodeToString(mac.doFinal(user.getBytes(StandardCharsets.UTF_8)))
  }

  /**
   * 環境変数から読む: ICE_SERVERS（JSON の配列 [{"urls": [...]}]. 無ければ公開の STUN）、TURN_URLS（カンマ区切り）、
   * TURN_SECRET（coturn の static-auth-secret）、TURN_TTL_HOURS（既定 12）. ICE_SERVERS が読めなければ Left（設定の誤りの文言）.
   */
  def fromEnv(env: Env = Env): Either[String, IceConfig] =
    env.opt("ICE_SERVERS").fold(Right(DefaultServers))(j =>
      io.circe.parser.decode[List[IceServer]](j).left.map(e =>
        s"ICE_SERVERS を読めません（JSON の配列 [{\"urls\": [...]}]）: ${e.getMessage}"
      )
    ).map(servers =>
      IceConfig(servers, env.list("TURN_URLS"), env.opt("TURN_SECRET"), env.int("TURN_TTL_HOURS").getOrElse(12).hours)
    )
}

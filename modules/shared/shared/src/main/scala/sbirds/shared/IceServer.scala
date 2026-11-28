package sbirds.shared

import io.circe.*

/**
 * WebRTC の ICE サーバー（RTCPeerConnection の iceServers の1件）. STUN は urls だけ、TURN は username・credential も付ける.
 * JSON では、無い項目（None）を書かない.
 */
final case class IceServer(urls: List[String], username: Option[String] = None, credential: Option[String] = None)

object IceServer {
  given Encoder.AsObject[IceServer] = Encoder.AsObject.instance(s =>
    JsonObject.fromIterable(
      List("urls" -> Json.fromValues(s.urls.map(Json.fromString))) ++
        s.username.map(u => "username" -> Json.fromString(u)) ++
        s.credential.map(c => "credential" -> Json.fromString(c))
    )
  )
  given Decoder[IceServer] = Decoder.instance(c =>
    for {
      urls <- c.downField("urls").as[List[String]].orElse(c.downField("urls").as[String].map(List(_)))
      user <- c.downField("username").as[Option[String]]
      cred <- c.downField("credential").as[Option[String]]
    } yield IceServer(urls, user, cred)
  )
}

package sbirds.shared

import io.circe.*

/** API のエラーの応答. error は利用者に見せる文言、code はエラーコード（例: SKEA-1001. ログと照らし合わせる）. */
final case class ErrorDto(
  error: String,
  code: Option[String] = None) derives Encoder.AsObject, Decoder

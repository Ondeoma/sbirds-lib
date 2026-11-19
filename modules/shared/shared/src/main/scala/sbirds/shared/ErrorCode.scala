package sbirds.shared

/**
 * エラーコード. 各サービスは ErrorCode.scala の1ファイルに enum でまとめて定義する.
 * {{{
 * enum ErrorCode(val code: String, val status: Int, val message: String) extends sbirds.shared.ErrorCode {
 *   case TaskNotFound extends ErrorCode("SKEA-2001", 404, "タスクが見つかりません")
 * }
 * }}}
 * code は「サービス名の大文字-4桁の数字」. 1桁目で分類する（1: 認証・共通, 2〜8: 機能ごと, 9: サーバー内部・バックグラウンドの処理）.
 * status は HTTP の応答のステータス（ログだけに出るものは 500）.
 */
trait ErrorCode {

  /** 例: SKEA-2001 */
  def code: String

  def status: Int

  /** 既定の文言（利用者に見せる） */
  def message: String
}

/** sbirds-lib の中で使うエラーコード（SBL-xxxx）. */
enum LibErrorCode(
  val code: String,
  val status: Int,
  val message: String) extends ErrorCode {
  case BadBody extends LibErrorCode("SBL-1001", 400, "リクエストの内容が正しくありません")
  case NotLoggedIn extends LibErrorCode("SBL-1002", 401, "ログインしてください")
  case CrossSite extends LibErrorCode("SBL-1003", 403, "別のサイトからの要求は受け付けません")
  case TooLarge extends LibErrorCode("SBL-1005", 413, "送られたデータが大きすぎます")
  case TooManyRequests extends LibErrorCode("SBL-1004", 429, "しばらく待ってから、もう一度お試しください")
  case Unexpected extends LibErrorCode("SBL-9001", 500, "サーバーでエラーが起きました")
}

/**
 * 発生したエラー（コードと、その場の文言）. 処理の結果（Either の Left）や例外として運ぶ.
 * detail を省くとコードの既定の文言になる.
 */
final case class AppError(
  code: ErrorCode,
  detail: Option[String] = None,
  cause: Option[Throwable] = None) extends Exception(detail.getOrElse(code.message), cause.orNull) {
  def message: String = detail.getOrElse(code.message)
  def status: Int = code.status
}

object AppError {
  def apply(
    code: ErrorCode,
    detail: String
  ): AppError = AppError(code, Some(detail))
}

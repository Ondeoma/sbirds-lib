package sbirds.observe

import cats.effect.IO

/**
 * アプリのログ（logback）. 標準出力（LOG_FORMAT=text・json）と OTLP（skorimako）に、トレースの ID・操作したユーザー付きで出る.
 * `IO.println("[サービス名] …")` の代わりに Log.println を使う（先頭の [サービス名] を外し、文言から水準を決める）.
 */
object Log {

  private val logger = org.slf4j.LoggerFactory.getLogger("app")
  private val Prefix = "^\\[[A-Za-z0-9_-]+\\]\\s*".r
  private val Warn = "^(?i)warning:\\s*".r

  def info(msg: => String): IO[Unit] = IO(logger.info(msg))
  def warn(msg: => String): IO[Unit] = IO(logger.warn(msg))
  def error(msg: => String, e: Throwable | Null = null): IO[Unit] = IO(logger.error(msg, e))

  /**
   * エラーコード付きのログ: [SKEA-9101] 500 期日の確認に失敗しました: 詳細.
   * 水準はコードのステータスで決める（5xx は ERROR、401 は INFO、ほかは WARN）.
   */
  def report(code: sbirds.shared.ErrorCode, detail: String = "", cause: Throwable | Null = null): IO[Unit] =
    IO(reportNow(code, detail, cause))

  /** report の同期版（スレッドで動く処理・ログの関数 `String => Unit` を受け取るところ向け）. */
  def reportNow(code: sbirds.shared.ErrorCode, detail: String = "", cause: Throwable | Null = null): Unit = {
    val line = s"[${code.code}] ${code.status} ${code.message}" + (if (detail.isEmpty) "" else s": $detail")
    if (code.status >= 500) logger.error(line, cause)
    else if (code.status == 401) logger.info(line)
    else logger.warn(line)
  }

  /** 文言から水準を決める: WARNING で始まれば warn、failed・error・exception を含めば error、ほかは info. */
  def println(msg: Any): IO[Unit] = IO(line(msg))

  /** println の同期版（スレッドで動く処理・ログの関数 `String => Unit` を受け取るところ向け）. */
  def line(msg: Any): Unit = {
    val m = Prefix.replaceFirstIn(String.valueOf(msg), "")
    val low = m.toLowerCase
    if Warn.findPrefixOf(m).isDefined then logger.warn(Warn.replaceFirstIn(m, ""))
    else if low.contains("failed") || low.contains("error") || low.contains("exception") then logger.error(m)
    else logger.info(m)
  }
}

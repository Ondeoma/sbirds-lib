package sbirds.db

import cats.effect.*
import sbirds.shared.ErrorCode

import scala.concurrent.duration.*

/** 定期的に動かす処理（キャンセルされるまで終わらない）。 */
object Periodic {

  /** initialDelay 待ってから action を動かし、以後 every ごとに繰り返す。失敗しても止めず、code を付けてログに出す。 */
  def run(initialDelay: FiniteDuration, every: FiniteDuration, code: ErrorCode)(action: IO[Any]): IO[Nothing] =
    IO.sleep(initialDelay) *>
      (action.void.handleErrorWith(e => sbirds.observe.Log.report(code, e.toString, e)) *> IO.sleep(every)).foreverM
}

/** 送信待ちの表（メール・Webhook など）の処理結果。 */
enum OutboxResult {
  case Sent
  case Skip(
    reason: String)
  case Retry(
    error: String)
}

object Outbox {

  /** 失敗 n 回目の再試行までの秒数（30秒から倍々、最大1時間）。 */
  def backoffSeconds(n: Int): Int = math.min(3600, 30 * (1 << math.min(n, 7)))
}

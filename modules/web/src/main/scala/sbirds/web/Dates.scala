package sbirds.web

import sbirds.shared.Timestamp

import scala.scalajs.js

/** 日時の表示（日本語）. 各サービスの Fmt から `export sbirds.web.Dates.{ago, dateTime}` で使う. */
object Dates {

  private def opts(o: (String, String)*): js.Dynamic = js.Dynamic.literal(o.map((k, v) => k -> (v: js.Any))*)

  /** 2026/11/15 09:30. */
  def dateTime(t: Timestamp): String =
    t.toDate.asInstanceOf[js.Dynamic].toLocaleString(
      "ja-JP",
      opts(
        "year" -> "numeric",
        "month" -> "2-digit",
        "day" -> "2-digit",
        "hour" -> "2-digit",
        "minute" -> "2-digit"
      )
    ).asInstanceOf[String]

  /** 「たった今」「3分前」「2時間前」「5日前」. 7 日を超えたら日付（2026/11/15）. */
  def ago(t: Timestamp): String = {
    val diff = (js.Date.now() - t.toEpochMilli) / 1000
    if (diff < 60) "たった今"
    else if (diff < 3600) s"${(diff / 60).toInt}分前"
    else if (diff < 86400) s"${(diff / 3600).toInt}時間前"
    else if (diff < 86400 * 7) s"${(diff / 86400).toInt}日前"
    else dateTime(t).take(10)
  }

}

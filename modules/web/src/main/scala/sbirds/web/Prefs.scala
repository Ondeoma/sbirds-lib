package sbirds.web

import io.github.ondeoma.scalactive.reactive.*
import org.scalajs.dom

/**
 * この端末だけの表示の設定（localStorage. 使えないときは既定値）. 鍵は「<サービス名>.<key>」.
 * 各サービスの Store.scala などで `object Prefs extends sbirds.web.LocalPrefs("stui")` として使う.
 */
class LocalPrefs(service: String) {

  private def name(key: String): String = service + "." + key

  def get(key: String, default: String): String =
    scala.util.Try(Option(dom.window.localStorage.getItem(name(key)))).toOption.flatten.getOrElse(default)

  def set(key: String, value: String): Unit = scala.util.Try(dom.window.localStorage.setItem(name(key), value))

}

/**
 * 画面の色（自動 = OS の設定、ライト、ダーク）. この端末に記憶する（鍵は「<サービス名>.theme」. index.html・theme.js が描く前に同じ鍵を読む）.
 * `object Theme extends sbirds.web.ThemeSwitch(Prefs)`（選択肢に絵文字を付けないサービスは `ThemeSwitch(Prefs, ThemeSwitch.Plain)`）.
 */
class ThemeSwitch(prefs: LocalPrefs, val options: List[(String, String)] = ThemeSwitch.Emoji) {

  val current: RV[String] = RV(prefs.get("theme", "system"))

  /** 今の設定を画面に反映する（html の data-theme）. */
  def apply(): Unit = {
    val el = dom.document.documentElement
    if (current.v == "system") el.removeAttribute("data-theme") else el.setAttribute("data-theme", current.v)
  }

  def set(v: String): Unit = { current := v; prefs.set("theme", v); apply() }

}

object ThemeSwitch {
  val Emoji: List[(String, String)] = List("system" -> "🖥 自動（OS に合わせる）", "light" -> "☀️ ライト", "dark" -> "🌙 ダーク")
  val Plain: List[(String, String)] = List("system" -> "自動（OS に合わせる）", "light" -> "ライト", "dark" -> "ダーク")
}

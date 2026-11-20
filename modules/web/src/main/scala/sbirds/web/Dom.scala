package sbirds.web

import org.scalajs.dom

import scala.scalajs.js

/** DOM のイベント・クリップボード・ファイルの補助. */
object Dom {

  /** 入力欄（input・select・textarea）の値. */
  def value(e: dom.Event): String = e.target.asInstanceOf[js.Dynamic].value.asInstanceOf[String]

  def checked(e: dom.Event): Boolean = e.target.asInstanceOf[dom.HTMLInputElement].checked

  /** 入力欄（input・textarea・select・contenteditable）で打っている最中か（ショートカットを無視するため）. */
  def isTyping(e: dom.Event): Boolean = e.target match {
    case el: dom.HTMLElement =>
      val t = el.tagName.toLowerCase
      t == "input" || t == "textarea" || t == "select" || el.isContentEditable
    case _ => false
  }

  /** 押した要素の下に、スクロールする枠（なければ画面）の端までどれだけ余白があるか（px）. メニューを上下どちらに開くかに使う. */
  def spaceBelow(e: dom.Event): Double = {
    val el = e.currentTarget.asInstanceOf[dom.HTMLElement]
    val bottom = el.getBoundingClientRect().bottom
    def scroller(n: dom.HTMLElement): Option[dom.HTMLElement] =
      if (n == null) None
      else {
        val oy = dom.window.getComputedStyle(n).overflowY
        if (oy == "auto" || oy == "scroll") Some(n) else scroller(n.parentElement)
      }
    val limit =
      scroller(el.parentElement).map(_.getBoundingClientRect().bottom).getOrElse(dom.window.innerHeight.toDouble)
    limit - bottom
  }

  /** クリップボードに写し、お知らせを出す. */
  def copy(text: String, message: String = "コピーしました"): Unit = {
    dom.window.navigator.clipboard.writeText(text)
    if (message.nonEmpty) Toasts.show(message)
  }

  /** ファイルとして保存させる（Blob か URL）. */
  def download(blob: dom.Blob, fileName: String): Unit = {
    val url = dom.URL.createObjectURL(blob)
    downloadUrl(url, fileName)
    dom.window.setTimeout(() => dom.URL.revokeObjectURL(url), 1000)
  }

  def downloadUrl(url: String, fileName: String): Unit = {
    val a = dom.document.createElement("a").asInstanceOf[dom.HTMLAnchorElement]
    a.href = url
    a.setAttribute("download", fileName)
    a.style.display = "none"
    dom.document.body.appendChild(a)
    a.click()
    a.remove()
  }

  /** ファイルの選択の input から選んだファイルを取り出し、同じファイルを選び直せるよう空に戻す. */
  def takeFiles(e: dom.Event): List[dom.File] = {
    val input = e.target.asInstanceOf[dom.HTMLInputElement]
    val fs = Option(input.files).map(l => (0 until l.length).map(l(_)).toList).getOrElse(Nil)
    input.value = ""
    fs
  }

  /** ドロップしたファイル（dragover で preventDefault しておく）. */
  def droppedFiles(e: dom.DragEvent): List[dom.File] =
    Option(e.dataTransfer).flatMap(dt => Option(dt.files)).map(l => (0 until l.length).map(l(_)).toList).getOrElse(Nil)

}

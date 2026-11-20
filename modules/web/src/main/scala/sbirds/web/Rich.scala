package sbirds.web

import io.github.ondeoma.scalactive.components.ComponentManager.Directive
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.timers.{SetTimeoutHandle, setTimeout}

/**
 * v-html で描いた HTML（.rich）の後処理: コードブロックの色付け（[[Highlight]]）と、連携したサービスのリンクの名前（[[LinkTitles]]）.
 * 各サービスで `val rich: Directive[Any] = Rich.directive("stuiLowlight", Languages.labels)` のように作り、
 * `<div class="rich" v-html="html" v-rich="html"></div>` のように付けると、描いたとき・中身が変わったときに、その要素の中だけを処理する.
 * 値は描き直しのきっかけ（v-html と同じ値を渡す）.
 */
object Rich {

  /**
   * @param lowlight  frontend の main.js が lowlight を置いた window のプロパティの名前（{ highlight, load, ready }）
   * @param languages 言語の id → 表示名（コードブロックの data-lang に出す）
   */
  def directive(lowlight: String, languages: => Map[String, String]): Directive[Any] = Directive((el, _) => {
    Highlight.highlightIn(el, lowlight, languages)
    LinkTitles.scan(el)
  })

}

/**
 * サーバーが組み立てた HTML（.rich）のコードブロックのシンタックスハイライト.
 * 描き直しのたびに未処理のコードブロックだけに付ける（言語定義はそのとき読み込む）. ハイライトそのものは frontend の lowlight.
 */
object Highlight {

  // main.js が Scala.js の読み込みの後に置くので、window のプロパティとして読む（js.Dynamic.global だと ReferenceError になる）
  private def lib(name: String): js.UndefOr[js.Dynamic] =
    dom.window.asInstanceOf[js.Dynamic].selectDynamic(name).asInstanceOf[js.UndefOr[js.Dynamic]]

  private val LangRe = js.RegExp("language-([A-Za-z0-9_+-]+)")

  private def truthy(v: js.Any): Boolean = js.DynamicImplicits.truthValue(v.asInstanceOf[js.Dynamic])

  /** lowlight の結果（hast）を DOM にする. */
  private def toNode(n: js.Dynamic): dom.Node =
    if (n.`type`.toString == "text") dom.document.createTextNode(n.value.toString)
    else {
      val el = dom.document.createElement("span").asInstanceOf[dom.HTMLElement]
      val cls = if (truthy(n.properties)) n.properties.className else js.undefined
      if (truthy(cls))
        el.className = if (js.Array.isArray(cls)) cls.asInstanceOf[js.Array[js.Any]].mkString(" ") else cls.toString
      n.children.asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]].foreach(_.foreach(c => el.appendChild(toNode(c))))
      el
    }

  /** root の中の .rich のコードブロックに色を付ける（lowlight は window のプロパティの名前、languages は言語の表示名）. */
  def highlightIn(root: dom.Element, lowlight: String, languages: => Map[String, String]): Unit =
    lib(lowlight).foreach {
      hl =>
        val codes = root.querySelectorAll(".rich pre code:not([data-hl])")
        if (codes.length > 0) {
          if (!hl.ready().asInstanceOf[Boolean])
            hl.load().asInstanceOf[js.Promise[js.Any]].toFuture.foreach(_ => highlightIn(root, lowlight, languages))
          else codes.foreach { node =>
            val el = node.asInstanceOf[dom.HTMLElement]
            el.dataset("hl") = "1"
            val lang = Option(LangRe.exec(el.className)).flatMap(_(1).toOption)
            lang.foreach(l => Option(el.parentElement).foreach(_.dataset("lang") = languages.getOrElse(l, l)))
            val text = el.textContent
            try {
              val tree = hl.highlight(lang.orNull, text)
              el.asInstanceOf[js.Dynamic].replaceChildren(tree.children.asInstanceOf[js.Array[js.Dynamic]].toSeq.map(
                c =>
                  toNode(c): js.Any
              )*)
              el.classList.add("hljs")
            } catch { case _: Throwable => () } // ハイライトできないときはそのまま
          }
        }
    }

}

/**
 * 外部サービス（skiwi・skawau・skea・skorimako）へのリンクに、リンク先の名前を出す（連携したサービスだけ動かす）.
 * v-rich（[[Rich.directive]]）を付けた要素（本文・コメントなど）からサービスの URL のリンクを探し、
 * まとめて /api/integrations/{provider}/titles に名前を問い合わせる.
 * 連携を確かめる前に描いたものは、動かし始めた（[[start]]）ときに画面全体から探す. 編集中のエディタ（contenteditable）の中は書き換えない.
 */
object LinkTitles {

  private val Uuid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"

  // サービスごとの、URL の後ろの形（最後の () が問い合わせる ID）
  private val paths = Map(
    "skiwi" -> s"/w/$Uuid/p/($Uuid)", // ページ
    "skawau" -> s"/(?:view|office|files)/($Uuid)", // ファイル・フォルダ
    "skea" -> "/(?:p/[A-Za-z0-9]+/)?tasks/([A-Za-z][A-Za-z0-9]{1,9}-[0-9]+)", // タスク（STUI-12）
    "skorimako" -> s"/(traces/[0-9a-fA-F]{1,64}|(?:alerts|dashboards)/$Uuid)" // トレース・アラート・ダッシュボード（ID は traces/… の形）
  )

  // ID の大文字・小文字をそろえる（タスクIDは大文字、UUID は小文字）
  private def norm(provider: String, id: String): String = if (provider == "skea") id.toUpperCase else id.toLowerCase

  private def escape(s: String): String = s.replaceAll("""[.*+?^${}()|\[\]\\]""", """\\$0""")

  /** 1つのサービスのリンクを見張る. 状態は data-{provider}（pending・ok・none・off）に持つ. */
  private final class Watcher(provider: String, base: String, path: String) {

    private val re = js.RegExp("^" + escape(base) + path + "(?:[/?#]|$)")
    private val key = provider
    private var enabled = true
    private var timer: Option[SetTimeoutHandle] = None
    private val waiting = mutable.LinkedHashMap.empty[String, List[dom.HTMLAnchorElement]] // id -> リンク

    private def apply(a: dom.HTMLAnchorElement, item: Option[js.Dynamic]): Unit = item match {
      case None => a.dataset(key) = "none"
      case Some(it) =>
        a.dataset(key) = "ok"
        a.classList.add("ext-link")
        val title = it.title.toString
        a.title = s"$provider: $title"
        val icon = Option(it.icon).filterNot(js.isUndefined).map(_.toString).filter(_.nonEmpty).getOrElse("📄")
        val label = s"$icon $title"
        val text = Option(a.textContent).getOrElse("").trim
        // 文字が URL のままのリンクは名前に置き換え、文字を書いたリンクは名前を横に添える
        if (text.isEmpty || text == a.getAttribute("href") || text.startsWith(base)) a.textContent = label
        else if (Option(a.nextElementSibling).forall(!_.classList.contains("ext-title"))) {
          val s = dom.document.createElement("span").asInstanceOf[dom.HTMLElement]
          s.className = "ext-title"
          s.textContent = label
          a.asInstanceOf[js.Dynamic].after(s)
        }
    }

    private def clear(a: dom.Element): Unit = js.special.delete(a.asInstanceOf[dom.HTMLElement].dataset, key)

    private def schedule(ms: Int): Unit = timer = Some(setTimeout(ms.toDouble)(flush()))

    private def flush(): Unit = {
      timer = None
      val batch = waiting.take(100).toList
      batch.foreach((id, _) => waiting.remove(id))
      if (batch.nonEmpty) {
        val body = js.JSON.stringify(js.Dynamic.literal(ids = js.Array(batch.map(_._1)*)))
        // 失敗してもログイン画面には移らない
        dom.fetch(s"/api/integrations/$provider/titles", Fetch.init(dom.HttpMethod.POST, Some(body))).toFuture
          .flatMap(r => if (r.ok) r.json().toFuture else Future.failed(new Exception(r.status.toString)))
          .map { j =>
            val res = j.asInstanceOf[js.Dynamic]
            if (!js.DynamicImplicits.truthValue(res.connected)) {
              enabled = false
              batch.foreach((_, as) => as.foreach(_.dataset(key) = "off"))
            } else {
              val byId = res.items.asInstanceOf[js.Array[js.Dynamic]].map(p => norm(provider, p.id.toString) -> p).toMap
              batch.foreach((id, as) => as.foreach(apply(_, byId.get(id))))
            }
          }
          // 問い合わせできなかったリンクは、次に描き直されたときにもう一度試す
          .recover { case _ =>
            batch.foreach((_, as) => as.foreach(clear))
          }
          .foreach(_ => if (waiting.nonEmpty) schedule(50))
      }
    }

    def scan(root: dom.Element): Unit = if (enabled) {
      val self = root match {
        case a: dom.HTMLAnchorElement if a.hasAttribute("href") && !a.hasAttribute(s"data-$key") => List(a)
        case _ => Nil
      }
      (self ++ root.querySelectorAll(s"a[href]:not([data-$key])").map(_.asInstanceOf[dom.HTMLAnchorElement])).foreach {
        a =>
          if (a.closest("[contenteditable=\"true\"]") == null) {
            Option(re.exec(Option(a.getAttribute("href")).getOrElse(""))).flatMap(_(1).toOption).foreach { m =>
              a.dataset(key) = "pending"
              val id = norm(provider, m)
              waiting(id) = waiting.getOrElse(id, Nil) :+ a
            }
          }
      }
      if (waiting.nonEmpty && timer.isEmpty) schedule(100)
    }

    scan(dom.document.documentElement)

    def reset(): Unit = {
      enabled = true
      dom.document.querySelectorAll(s"""a[data-$key="off"]""").foreach(clear)
      scan(dom.document.documentElement)
    }
  }

  private var watchers = Map.empty[String, Watcher]

  /** root の中（root がリンクならそれ自身も）の、連携したサービスのリンクに名前を出す. */
  def scan(root: dom.Element): Unit = watchers.values.foreach(_.scan(root))

  /** 連携したサービス（/api/integrations/links の 1 つ. url はサービスの URL）のリンクに名前を出し始める. */
  def start(provider: String, url: String, connected: Boolean): Unit = if (connected) watchers.get(provider) match {
    case Some(w) => w.reset()
    case None => paths.get(provider).foreach(p => watchers += provider -> new Watcher(provider, url, p))
  }

}

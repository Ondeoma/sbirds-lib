package sbirds.web

import io.github.ondeoma.scalactive.ScalactiveConfig
import io.github.ondeoma.scalactive.components.ComponentManager.*
import io.github.ondeoma.scalactive.reactive.CRV
import org.scalajs.dom

/**
 * sbirds のアプリの表示名（sruru に登録したアプリケーションの名前. グループごとの上書きを反映済み）.
 * /api/me の appNames を set で入れる. 設定が無いときは、それぞれのサービスの名前（slug）のまま.
 * 画面では `{{ AppNames.self }}`・`{{ AppNames.of("stui") }}`（変わると描き直される）、Scala では `AppNames.nameOf("stui")`.
 * ロゴ（sruru のアプリケーションのアイコン、またはグループごとの上書き）は `:src="AppNames.logo(&quot;/logo.svg&quot;).v"`
 * のように、設定が無いときのサービスのロゴを渡して使う.
 */
object AppNames {

  private val names: RV[Map[String, String]] = RV(Map.empty)
  private var service: String = ""

  /** 起動時にサービス自身の名前（slug. 例: "skea"）を決める. */
  def init(slug: String): Unit = { service = slug }

  /** /api/me の appNames を入れる. タブの題名も自身の表示名にする. */
  def set(m: Map[String, String]): Unit = {
    names := m
    val s = selfOf(m)
    ScalactiveConfig.titleTemplate = "%s | " + s
    ScalactiveConfig.defaultTitle = Some(s)
    if (service.nonEmpty && s != service) dom.document.title = dom.document.title.replace(service, s)
  }

  private def selfOf(m: Map[String, String]): String =
    m.get("_self").orElse(m.get(service)).filter(_.trim.nonEmpty).getOrElse(service)

  /** このサービス自身の表示名（ヘッダーなど）. */
  def self: CRV[String] = names.mapC(selfOf)

  /** slug のアプリの表示名（例: of("stui")）. */
  def of(slug: String): CRV[String] = names.mapC(m => nameIn(m, slug))

  /** いまの値（Scala のコードで文言に入れるとき）. */
  def nameOf(slug: String): String = nameIn(names.v, slug)

  def selfName: String = selfOf(names.v)

  /** このサービス自身のロゴの URL（なければ default. 例: logo("/logo.svg")）. */
  def logo(default: String): CRV[String] =
    names.mapC(m => m.get("_logo:_self").orElse(m.get("_logo:" + service)).getOrElse(default))

  /** slug のアプリのロゴの URL（なければ default）. */
  def logoOf(slug: String, default: String): CRV[String] = names.mapC(_.getOrElse("_logo:" + slug, default))

  private def nameIn(m: Map[String, String], slug: String): String = m.get(slug).filter(_.trim.nonEmpty).getOrElse(slug)
}

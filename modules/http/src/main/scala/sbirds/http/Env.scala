package sbirds.http

/**
 * 環境変数からの設定の読み取り（各サービスの Config.scala で使う）. 値は前後の空白を除き、空なら「無い」として扱う.
 * {{{
 * import sbirds.http.Env.*
 * val port = int("HTTP_PORT").getOrElse(8080)
 * }}}
 * テストでは `new Env(Map("A" -> "1").get)` のように読み取り元を差し替えられる.
 */
class Env(read: String => Option[String]) {
  def opt(name: String): Option[String] = read(name).map(_.trim).filter(_.nonEmpty)
  def int(name: String): Option[Int] = opt(name).flatMap(_.toIntOption)
  def long(name: String): Option[Long] = opt(name).flatMap(_.toLongOption)

  /** true（大文字・小文字は問わない）のときだけ true. */
  def enabled(name: String): Boolean = opt(name).exists(_.equalsIgnoreCase("true"))

  /** false と書いたときだけ true（既定が有効な設定を切るとき）. */
  def disabled(name: String): Boolean = opt(name).exists(_.equalsIgnoreCase("false"))

  /** true / false. 無ければ default（true でも false でもない値は false）. */
  def bool(name: String, default: Boolean): Boolean = opt(name).map(_.equalsIgnoreCase("true")).getOrElse(default)

  def required(name: String): Either[String, String] = opt(name).toRight(s"環境変数 $name を設定してください")

  /** カンマか空白で区切った一覧. */
  def list(name: String): List[String] = opt(name).toList.flatMap(_.split("[,\\s]+").map(_.trim).filter(_.nonEmpty))

  /**
   * DEV_LOGIN（開発用のログイン）が有効か. 名前だけでだれにでも（管理者にも）なれるので、公開 URL が手元（Env.localUrl）のときだけ使える.
   * true なのに公開 URL が手元でなければ Left（設定の誤りとして起動させない）.
   */
  def devLogin(publicUrl: String): Either[String, Boolean] =
    if !enabled("DEV_LOGIN") then Right(false)
    else Either.cond(Env.localUrl(publicUrl), true, "DEV_LOGIN は PUBLIC_URL が localhost のときだけ使えます")
}

object Env extends Env(sys.env.get) {

  /** 手元の URL か（ホストが localhost・127.0.0.1・[::1]・*.localhost）. */
  def localUrl(url: String): Boolean =
    scala.util.Try(java.net.URI.create(url.trim).getHost).toOption.flatMap(Option(_)).map(_.toLowerCase)
      .exists(h => Set("localhost", "127.0.0.1", "[::1]").contains(h) || h.endsWith(".localhost"))
}

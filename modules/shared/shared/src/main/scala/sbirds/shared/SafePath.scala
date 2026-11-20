package sbirds.shared

/**
 * ログインや連携の後に戻る先として使ってよいパスか（同じサイトのパスだけ. ほかのサイトへ飛ばさせない）.
 * サーバーと画面（Scala.js）で同じ確認をする.
 */
object SafePath {

  /** 「/」で始まり、「//…」「/\…」（ブラウザが別のサイトと解釈する）でなく、制御文字を含まない. */
  def isLocal(p: String): Boolean =
    p.startsWith("/") && !p.startsWith("//") && !p.startsWith("/\\") && !p.exists(_.isControl)

  /** isLocal なら そのまま、そうでなければ default. */
  def orElse(p: Option[String], default: String = "/"): String = p.filter(isLocal).getOrElse(default)

  // ブラウザが読み飛ばすタブ・改行を挟んだ「/\t/evil」も通さないよう、使える文字を絞る
  private val strictRe = "^/(?![/\\\\])[A-Za-z0-9\\-._~!$&'()*+,;=:@%/?#]*$".r

  /** 使える文字を URL の文字だけに絞った版（空白・バックスラッシュ・日本語などを含むものも断る. % は通す）. */
  def isStrict(p: String): Boolean = strictRe.matches(p)

  /** 前後の空白を除いて isStrict ならその値、そうでなければ default. */
  def of(s: Option[String], default: String = "/"): String = s.map(_.trim).filter(isStrict).getOrElse(default)
}

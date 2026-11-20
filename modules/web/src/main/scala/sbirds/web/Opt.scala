package sbirds.web

/** アイコンに出す人（parts/Avatar.st）. id は色を決めるのに使う. */
final case class Person(id: String, name: String, avatarUrl: Option[String] = None)

/** オートコンプリート（parts/Combo.st）の選択肢. icon は前に付ける文字、user はアイコン、color はラベルの色、hint は右の補足. */
final case class Opt(value: String,
                     label: String,
                     icon: String = "",
                     user: Option[Person] = None,
                     color: String = "",
                     hint: String = "")

object Opt {

  def of(xs: List[(String, String)]): List[Opt] = xs.map((v, l) => Opt(v, l))

  /** 値と表示が同じ選択肢. */
  def names(xs: List[String]): List[Opt] = xs.map(x => Opt(x, x))

  /** 人の選択肢（値は id）. */
  def people(ps: List[Person]): List[Opt] = ps.map(p => Opt(p.id, p.name, user = Some(p)))

}

/** 表示の書式（各サービスで同じもの）. */
object Formats {

  /** 名前の頭文字（アイコンの画像がないときに出す）. */
  def initials(name: String): String = name.trim.take(1)

  /** バイト数（1.2 KB など）. */
  def bytes(n: Long): String =
    if (n < 1024) s"$n B"
    else if (n < 1024L * 1024) f"${n / 1024.0}%.1f KB"
    else if (n < 1024L * 1024 * 1024) f"${n / 1024.0 / 1024.0}%.1f MB"
    else f"${n / 1024.0 / 1024.0 / 1024.0}%.2f GB"

}

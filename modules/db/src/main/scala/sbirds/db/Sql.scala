package sbirds.db

import doobie.*
import doobie.implicits.*

/** SQL の部品（doobie の Fragments を List で使うため）。 */
object Sql {

  /** set a = 1, b = 2（空のリストは渡さないこと）。 */
  def set(fs: List[Fragment]): Fragment = fr"set" ++ fs.reduce(_ ++ fr"," ++ _)

  /** where a and b（空なら何も付けない）。 */
  def whereAnd(fs: List[Fragment]): Fragment =
    if fs.isEmpty then Fragment.empty else fr"where" ++ fs.map(f => fr"(" ++ f ++ fr")").reduce(_ ++ fr"and" ++ _)
  def and(fs: List[Fragment]): Fragment =
    if fs.isEmpty then fr"true" else fs.map(f => fr"(" ++ f ++ fr")").reduce(_ ++ fr"and" ++ _)
  def or(fs: List[Fragment]): Fragment =
    if fs.isEmpty then fr"false" else fs.map(f => fr"(" ++ f ++ fr")").reduce(_ ++ fr"or" ++ _)
  def commas(fs: List[Fragment]): Fragment = fs.reduce(_ ++ fr"," ++ _)

  /** like のワイルドカード（% _）とエスケープの文字（\）を、ただの文字として扱うようにする。 */
  def likeEscape(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

  /** like / ilike で「s を含む」を探す値（s はそのまま。前後の空白も探す文字に含める）。 */
  def containing(s: String): String = "%" + likeEscape(s) + "%"

  /** 部分一致の LIKE のパターン（前後の空白は除く。% と _ はそのままの文字として探す）。 */
  def like(q: String): String = containing(q.trim)
}

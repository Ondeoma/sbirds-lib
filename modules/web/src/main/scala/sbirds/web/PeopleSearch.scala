package sbirds.web

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/**
 * parts/UserPicker.st に渡す、人の検索. find の結果を覚えておき、選んだ [[Person]] から元の値（UserBrief など）を引けるようにする.
 * 例: `object UserSearch extends PeopleSearch[UserBrief](Api.users, _.person, _.email.getOrElse(""))`
 *
 * @param find     検索（入力した文字 → 見つかったもの）
 * @param toPerson アイコン・名前に出す形にする
 * @param describe 候補の右に添える補足（メールアドレスなど）
 */
class PeopleSearch[A](find: String => Future[List[A]], toPerson: A => Person, describe: A => String = (_: A) => "") {

  private var seen: Map[String, A] = Map.empty

  /** 検索して、見つかったものを覚える. */
  val search: String => Future[List[Person]] = q =>
    find(q).map { xs =>
      val ps = xs.map(a => toPerson(a) -> a)
      seen = seen ++ ps.map((p, a) => p.id -> a)
      ps.map(_._1)
    }

  /** 候補の補足. */
  val hint: Person => String = p => seen.get(p.id).map(describe).getOrElse("")

  /** 選んだ人の元の値. */
  def apply(p: Person): Option[A] = seen.get(p.id)

}

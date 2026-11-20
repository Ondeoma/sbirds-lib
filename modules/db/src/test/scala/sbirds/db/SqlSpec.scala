package sbirds.db

import doobie.implicits.*

class SqlSpec extends munit.FunSuite {

  private def text(f: doobie.Fragment): String = f.internals.sql.trim.replaceAll("\\s+", " ")

  test("where・and・or は空のときの形が決まっている") {
    assertEquals(text(Sql.whereAnd(Nil)), "")
    assertEquals(text(Sql.and(Nil)), "true")
    assertEquals(text(Sql.or(Nil)), "false")
    assertEquals(text(Sql.whereAnd(List(fr"a = 1", fr"b = 2"))), "where ( a = 1 ) and ( b = 2 )")
    assertEquals(text(Sql.set(List(fr"a = 1", fr"b = 2"))), "set a = 1 , b = 2")
  }

  test("like のワイルドカードは、ただの文字として探す") {
    assertEquals(Sql.likeEscape("50%_a\\b"), "50\\%\\_a\\\\b")
    assertEquals(Sql.containing(" a "), "% a %")
    assertEquals(Sql.like(" a%"), "%a\\%%")
  }

  test("再試行までの時間は 30 秒から倍々で、1時間まで") {
    assertEquals(List(0, 1, 2, 7, 8, 100).map(Outbox.backoffSeconds), List(30, 60, 120, 3600, 3600, 3600))
  }
}

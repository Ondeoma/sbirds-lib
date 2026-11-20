package sbirds.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

/**
 * 画面の隅に出すお知らせ（parts/ToastHost.st が描く）. link があれば押すとその画面へ移る.
 * action があれば、その文言のボタン（「元に戻す」など）を出し、押すと実行して消す.
 */
final case class Toast(id: Int,
                       text: String,
                       link: Option[String] = None,
                       error: Boolean = false,
                       action: Option[(String, () => Unit)] = None)

object Toasts {

  val list: RV[List[Toast]] = RV(Nil)

  private var seq = 0

  /** 同時に出す数. 古いものから消す. */
  var max: Int = 5

  /** 消えるまでの時間（ミリ秒）. */
  var duration: Int = 6000
  var errorDuration: Int = 9000

  /** お知らせを出す（既定では、エラーは 9 秒、それ以外は 6 秒で消える）. ms で時間を指定できる. */
  def show(text: String,
           link: Option[String] = None,
           error: Boolean = false,
           action: Option[(String, () => Unit)] = None,
           ms: Option[Int] = None): Unit = {
    seq += 1
    val t = Toast(seq, text, link, error, action)
    list := (list.v :+ t).takeRight(max)
    dom.window.setTimeout(() => dismiss(t.id), ms.getOrElse(if (error) errorDuration else duration).toDouble)
  }

  /** ボタン付きのお知らせ（例: 「削除しました」＋「元に戻す」）. */
  def withAction(text: String, label: String, ms: Option[Int] = None)(f: => Unit): Unit =
    show(text, action = Some((label, () => f)), ms = ms)

  /** ボタンを押したとき. */
  def act(t: Toast): Unit = {
    dismiss(t.id)
    t.action.foreach(_._2())
  }

  def dismiss(id: Int): Unit = list := list.v.filterNot(_.id == id)

  /** 失敗を知らせる（401 はログイン画面へ移るので出さない）. */
  def error(e: Throwable): Unit = e match {
    case ApiError(401, _) => ()
    case other => show(Option(other.getMessage).filter(_.nonEmpty).getOrElse(other.toString), error = true)
  }

  /** Future の失敗をお知らせで知らせる. 成功したら ok. */
  def report[A](f: Future[A])(ok: A => Unit): Unit = f.onComplete {
    case Success(a) => ok(a)
    case Failure(e) => error(e)
  }

}

package sbirds.web

import io.github.ondeoma.scalactive.components.ComponentManager.*

import scala.scalajs.js

/** 画面内のダイアログの要求（parts/DialogHost.st が描く）. radio なら選択肢をラジオボタンで並べる（既定はコンボボックス）. */
final case class DialogReq(message: String,
                           okLabel: String,
                           danger: Boolean,
                           input: Option[RV[String]],
                           choices: List[(String, String)],
                           cancel: Boolean,
                           onOk: String => Unit,
                           onCancel: () => Unit,
                           placeholder: String = "",
                           radio: Boolean = false)

/** 確認・入力・選択・お知らせのダイアログ（ブラウザの confirm・prompt の代わり）. */
object Dialog {

  val current: RV[Option[DialogReq]] = RV(None)

  private def open(r: DialogReq): Unit = current := Some(r)

  /** 確認（OK を押したら f）. */
  def confirm(message: String,
              ok: String = "OK",
              danger: Boolean = true,
              onCancel: () => Unit = () => ())(f: => Unit): Unit =
    open(DialogReq(message, ok, danger, None, Nil, cancel = true, _ => f, onCancel))

  /** 文字の入力（OK を押したら入力した値で f）. */
  def prompt(message: String,
             default: String = "",
             ok: String = "OK",
             danger: Boolean = false,
             placeholder: String = "",
             onCancel: () => Unit = () => ())(f: String => Unit): Unit =
    open(DialogReq(message, ok, danger, Some(RV(default)), Nil, cancel = true, f, onCancel, placeholder))

  /** 選択肢から選ぶ（choices は 値 → 表示名. 最初のものを選んだ状態で開く）. radio ならラジオボタンで並べる. */
  def choose(message: String,
             choices: List[(String, String)],
             ok: String = "OK",
             danger: Boolean = false,
             radio: Boolean = false,
             onCancel: () => Unit = () => ())(f: String => Unit): Unit =
    open(DialogReq(
      message,
      ok,
      danger,
      Some(RV(choices.headOption.map(_._1).getOrElse(""))),
      choices,
      cancel = true,
      f,
      onCancel,
      radio = radio
    ))

  def alert(message: String): Unit =
    open(DialogReq(message, "OK", danger = false, None, Nil, cancel = false, _ => (), () => ()))

  def close(ok: Boolean): Unit = current.v.foreach { r =>
    current := None
    if (ok) r.onOk(r.input.map(_.v).getOrElse("")) else r.onCancel()
  }

  /** 文字の入力（JS のエディタ用）. OK なら入力した値、キャンセルなら null の Promise. */
  def promptJs(message: String, default: String): js.Promise[js.Any] =
    new js.Promise[js.Any]((resolve, _) =>
      open(DialogReq(
        message,
        "OK",
        danger = false,
        Some(RV(Option(default).getOrElse(""))),
        Nil,
        cancel = true,
        v => resolve(v),
        () => resolve(null)
      ))
    )

}

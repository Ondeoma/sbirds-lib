package sbirds.web

import org.scalajs.dom

import scala.concurrent.{Future, Promise}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 画像の加工（アイコンの切り抜きなど）. */
object Images {

  /** 画像の中央を正方形に切り抜き、px×px にする（WebP. 対応していないブラウザでは PNG）. */
  def square(file: dom.File, px: Int = 256): Future[dom.Blob] =
    js.Dynamic.global.createImageBitmap(file).asInstanceOf[js.Promise[js.Dynamic]].toFuture.flatMap { img =>
      val (w, h) = (img.width.asInstanceOf[Double], img.height.asInstanceOf[Double])
      val side = math.min(w, h)
      val canvas = dom.document.createElement("canvas").asInstanceOf[dom.HTMLCanvasElement]
      canvas.width = px
      canvas.height = px
      val ctx = canvas.getContext("2d").asInstanceOf[js.Dynamic]
      ctx.imageSmoothingQuality = "high"
      ctx.drawImage(img, (w - side) / 2, (h - side) / 2, side, side, 0, 0, px, px)
      val p = Promise[dom.Blob]()
      canvas.asInstanceOf[js.Dynamic].toBlob(
        (b: dom.Blob) => if (b == null) p.failure(new Exception("画像を作れませんでした")) else p.success(b),
        "image/webp",
        0.9
      )
      p.future
    }

  /**
   * アイコンの画像を選んだときの処理（input type=file の @change に渡す）.
   * 画像なら正方形に切り抜いて upload に渡し、成功したら done. 画像でなければお知らせを出す.
   */
  def pickAvatar[A](e: dom.Event)(upload: dom.Blob => Future[A])(done: A => Unit): Unit =
    Dom.takeFiles(e).headOption.foreach { f =>
      if (!f.`type`.startsWith("image/")) Toasts.show("画像のファイルを選んでください", error = true)
      else Toasts.report(square(f).flatMap(upload))(done)
    }

}

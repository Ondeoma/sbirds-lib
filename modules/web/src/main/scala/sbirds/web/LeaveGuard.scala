package sbirds.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import io.github.ondeoma.scalactive.reactive.Reactive
import org.scalajs.dom

/**
 * 保存していない変更があるときに、ほかの画面へ移る前（Router）とページを閉じる・読み直す前（beforeunload）に確かめる.
 * 画面の mounted で install すると、画面が片付けられたときに自動で外れる（戻り値の関数で先に外すこともできる）.
 */
object LeaveGuard {

  import io.github.ondeoma.scalactive.routes.Router

  // 片付けのときに呼ばれる後始末（実行中の mounted のコンポーネントに預ける）
  private final class Cleanup(f: () => Unit) extends Reactive[Unit] {
    private var done = false
    def v: Unit = ()
    override def abort(): Unit = {
      if (!done) { done = true; f() }
      super.abort()
    }
  }

  /**
   * @param dirty   保存していない変更があれば true
   * @param message 確かめるときの文言
   */
  def install(dirty: () => Boolean,
              message: String = "保存していない変更があります。変更を捨てて移りますか？",
              ok: String = "捨てて移る"): () => Unit = {
    var leaving = false
    val removeGuard = Router.beforeEach {
      (to, from) =>
        if (leaving || from.forall(f => f.path == to.path && f.search == to.search) || !dirty()) Router.Continue
        else {
          Dialog.confirm(message, ok) {
            leaving = true
            Router.go(to.path + to.search + to.hash)
          }
          Router.Cancel
        }
    }
    val unload = useEventListener[dom.BeforeUnloadEvent](dom.window, "beforeunload") { e =>
      if (dirty()) { e.preventDefault(); e.returnValue = "" }
    }
    val cleanup = Cleanup(() => { removeGuard(); unload.stop() })
    Reactive.adoptByRunningHook(cleanup)
    () => cleanup.abort()
  }

}

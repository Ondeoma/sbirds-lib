<script lang="scala">
import org.scalajs.dom
import sbirds.web.Backdrop

// モーダルの枠（body の直下に描く。背景のクリック（押し始めも背景のとき）か Esc キーで onClose を呼ぶ）
case class Props(onClose: Unit => Unit, wide: Boolean = false, xwide: Boolean = false, slot: Slot = Slot.empty)

def mounted(props: Props): Unit = {
  // エディタの候補の一覧などが Esc を使ったときは閉じない. 片付けると自動で外れる
  useEventListener[dom.KeyboardEvent](dom.window, "keydown") { e =>
    if (e.key == "Escape" && !e.defaultPrevented) props.onClose(())
  }
  ()
}

def cls(props: Props): String = if (props.xwide) "modal xwide" else if (props.wide) "modal wide" else "modal"
</script>

<template>
  <Teleport to="body">
    <div class="modal-bg" @mousedown="Backdrop.down(event)" @mouseup="Backdrop.up(event)" @click="Backdrop.click(event)(props.onClose(()))">
      <div :class="cls(props)"><slot></slot></div>
    </div>
  </Teleport>
</template>

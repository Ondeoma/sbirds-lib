<script lang="scala">
import sbirds.web.{CustomEmojiItem, CustomEmojis, Dialog, Dom, Toasts}
import org.scalajs.dom

// リアクションの絵文字を選ぶ（分類ごと・カスタム絵文字）. カスタム絵文字はここで登録できる（/api/emoji）.
// categories は（分類の名前, 絵文字）. down なら下に、right なら右にそろえて開く.
case class Props(onPick: String => Unit,
                 onClose: Unit => Unit,
                 categories: List[(String, List[String])],
                 down: Boolean = false,
                 right: Boolean = false)

case class State(act: ActionState, tab: RV[String], q: RV[String] = RV(""), adding: RV[Boolean] = RV(false), name: RV[String] = RV(""))

// 最初は 1 つ目の分類（よく使う）を開く
def initState(props: Props) = State(useAction(), RV(props.categories.headOption.map(_._1).getOrElse(Custom)))

def mounted(s: State): Unit = CustomEmojis.load()

val Custom = "カスタム"
def tabs(props: Props): List[String] = props.categories.map(_._1) :+ Custom
def tabClass(s: State, t: String) = s.tab.mapC(x => if (x == t) "tab active" else "tab")
def emojis(s: State, props: Props) = s.tab.mapC(t => props.categories.find(_._1 == t).map(_._2).getOrElse(Nil))
def customs(s: State) = computed { val q = s.q.v.trim.toLowerCase; CustomEmojis.custom.v.filter(e => q.isEmpty || e.name.contains(q)) }
def isCustom(s: State) = s.tab.mapC(_ == Custom)

def upload(s: State, e: dom.Event): Unit = {
  val name = s.name.v.trim.toLowerCase
  Dom.takeFiles(e).headOption.foreach { file =>
    if (!name.matches("^[a-z0-9_+-]{2,32}$")) Toasts.show("名前は半角の英小文字・数字・_ + - の 2〜32 文字にしてください", error = true)
    else Toasts.report(s.act.run(CustomEmojis.add(name, file))) { _ =>
      Toasts.show(s"「:$name:」を登録しました"); s.name := ""; s.adding := false; CustomEmojis.load(force = true)
    }
  }
}

def remove(s: State, c: CustomEmojiItem): Unit = Dialog.confirm(s"カスタム絵文字「:${c.name}:」を削除しますか？", "削除する") {
  Toasts.report(CustomEmojis.remove(c.name))(_ => CustomEmojis.load(force = true))
}
</script>

<template>
  <div :class='"picker" + (if (props.down) " down" else "") + (if (props.right) " right" else "")' @mousedown.stop="()">
    <div class="tabs-row">
      <button v-for="t in tabs(props)" type="button" :class="tabClass(s, t)" @click="s.tab := t">{{ t }}</button>
      <span class="grow"></span>
      <button type="button" class="ghost close" @click="props.onClose(())">✕</button>
    </div>
    <div v-if="isCustom(s).mapC(!_)" class="grid">
      <button v-for="e in emojis(s, props)" type="button" class="em" @click="props.onPick(e)">{{ e }}</button>
    </div>
    <div v-if="isCustom(s)" class="custom">
      <div class="row"><input type="search" v-model="s.q" placeholder="名前で絞り込み" class="grow small-in">
        <button type="button" class="small" @click="s.adding := !s.adding.v">＋ 登録</button></div>
      <div v-if="s.adding" class="add row">
        <input type="text" v-model="s.name" placeholder="名前（例: lgtm）" class="small-in">
        <label class="btn small">画像を選ぶ<input type="file" accept="image/png,image/jpeg,image/gif,image/webp" class="hidden" @change="upload(s, event)"></label>
        <span v-if="s.act.busy" class="muted small">登録中…</span>
      </div>
      <div class="grid">
        <span v-for="c in customs(s)" :key="c.name" class="cwrap">
          <button type="button" class="em" :title='":" + c.name + ":（" + c.createdBy.getOrElse("") + "）"' @click='props.onPick(":" + c.name + ":")'>
            <img :src="c.url" alt="" class="cemoji"></button>
          <button type="button" class="del" title="削除" @click="remove(s, c)">×</button>
        </span>
      </div>
      <p v-if="customs(s).mapC(_.isEmpty)" class="muted small">カスタム絵文字はまだありません（PNG・GIF など。512KB まで）</p>
    </div>
  </div>
</template>

<style scoped>
.picker { position: absolute; bottom: 1.9rem; left: 0; z-index: 30; width: 320px; background: var(--surface); border: 1px solid var(--line);
  border-radius: 10px; padding: 6px; box-shadow: 0 8px 10px -5px rgba(0, 0, 0, .2), 0 16px 24px 2px rgba(0, 0, 0, .14), 0 6px 30px 5px rgba(0, 0, 0, .12); }
.picker.down { bottom: auto; top: 1.9rem; left: auto; right: 0; }
.picker.right { left: auto; right: 0; }
.tabs-row { display: flex; gap: 2px; overflow-x: auto; scrollbar-width: none; border-bottom: 1px solid var(--line); margin-bottom: 4px; }
.tab { border: none; background: none; font-size: .72rem; padding: .25rem .45rem; border-radius: 6px 6px 0 0; color: var(--muted); }
.tab.active { color: var(--accent); box-shadow: inset 0 -2px 0 var(--accent); }
.close { font-size: .75rem; padding: 0 .35rem; }
.grid { display: grid; grid-template-columns: repeat(8, 1fr); gap: 1px; max-height: 210px; overflow-y: auto; }
.em { border: none; background: none; font-size: 1.2rem; padding: .2rem; justify-content: center; border-radius: 6px; }
.em:hover { background: var(--hover); }
.cemoji { width: 22px; height: 22px; object-fit: contain; }
.cwrap { position: relative; }
.del { position: absolute; top: -2px; right: -2px; border: none; background: var(--danger); color: #fff; font-size: .6rem; border-radius: 50%;
  width: 14px; height: 14px; padding: 0; justify-content: center; display: none; }
.cwrap:hover .del { display: inline-flex; }
.custom .row { margin-bottom: 4px; }
.small-in { font-size: .8rem; padding: .2rem .4rem; }
.add { gap: .3rem; }
</style>

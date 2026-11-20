<script lang="scala">
import sbirds.web.{Dom, Opt, Person}
import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 人を名前などで探して選ぶ（入力すると search で探して候補を出す. 上下キー・Enter で選ぶ）. 選ぶと onPick を呼び、欄を空にする.
// search・hint は PeopleSearch から渡すと、選んだ Person から元の値を引ける. exclude の id は候補に出さない.
// combo なら、入力欄を Combo（枠と ▾ の付いたもの）で出す.
case class Props(search: String => Future[List[Person]],
                 onPick: Person => Unit,
                 exclude: List[String] = Nil,
                 hint: Person => String = _ => "",
                 placeholder: String = "名前・メールアドレスで探す",
                 autofocus: Boolean = false,
                 delay: Int = 200,
                 combo: Boolean = false,
                 small: Boolean = false)

case class State(q: RV[String], found: RV[List[Person]], open: RV[Boolean], active: RV[Int], search: DebouncedFn[String], latest: LatestFn)

def initState(props: Props) = {
  val found = RV(List.empty[Person])
  val open = RV(false)
  val active = RV(-1)
  val latest = useLatest()
  // 打ち終えてから探す（古い応答は捨てる）
  val search = useDebounce[String](props.delay)(q => latest(props.search(q)).foreach { ps => found := ps; active := -1; open := true })
  State(RV(""), found, open, active, search, latest)
}

def typed(s: State, v: String): Unit = {
  s.q := v
  if (v.trim.isEmpty) {
    s.search.cancel()
    s.latest.cancel()
    s.found := Nil
  } else s.search(v.trim)
}

def candidates(s: State, props: Props) = s.found.mapC(_.filterNot(p => props.exclude.contains(p.id)))

def pick(s: State, props: Props, p: Person): Unit = {
  props.onPick(p)
  s.q := ""
  s.found := Nil
  s.open := false
}

def key(s: State, props: Props, e: dom.Event): Unit = {
  val k = e.asInstanceOf[dom.KeyboardEvent]
  val list = candidates(s, props).v
  k.key match {
    case "ArrowDown" if list.nonEmpty => k.preventDefault(); s.open := true; s.active := math.min(s.active.v + 1, list.size - 1)
    case "ArrowUp" if list.nonEmpty   => k.preventDefault(); s.active := math.max(s.active.v - 1, 0)
    // 上下キーで選んでいなければ、最初の候補
    case "Enter" if s.open.v && list.nonEmpty => k.preventDefault(); list.lift(math.max(s.active.v, 0)).foreach(pick(s, props, _))
    case _ => ()
  }
}

def items(s: State, props: Props) = computed { val a = s.active.v; candidates(s, props).v.zipWithIndex.map((p, i) => (p, i == a)) }

// Combo で出すとき
def opts(s: State, props: Props) = candidates(s, props).mapC(_.map(p => Opt(p.id, p.name, user = Some(p), hint = props.hint(p))))
def query(s: State, q: String): Unit = s.search(q.trim)
def chosen(s: State, props: Props, id: String): Unit = s.found.v.find(_.id == id).foreach(pick(s, props, _))
</script>

<template>
  <Combo v-if="props.combo" :live="Some(opts(s, props))" :keep="false" :filter="false" :small="props.small" :placeholder="props.placeholder"
    :onQuery="q => query(s, q)" :onChange="v => chosen(s, props, v)" />
  <div v-else class="picker">
    <input type="text" :value="s.q" :placeholder="props.placeholder" autocomplete="off" v-focus="props.autofocus"
      @input="typed(s, Dom.value(event))" @focus="s.open := true" @blur="s.open := false" @keydown="key(s, props, event)">
    <div v-if="s.open" class="list">
      <a v-for="x in items(s, props)" :key="x._1.id" href="#" :class='if (x._2) "item on" else "item"' @mousedown.prevent="pick(s, props, x._1)">
        <Avatar :user="Some(x._1)" :size="22" /><span class="grow">{{ x._1.name }}</span><span class="muted small">{{ props.hint(x._1) }}</span>
      </a>
    </div>
  </div>
</template>

<style scoped>
.picker { position: relative; }
.picker input { width: 100%; }
.list { position: absolute; left: 0; right: 0; top: calc(100% + 2px); background: var(--surface); border: 1px solid var(--line); border-radius: 8px;
  box-shadow: var(--shadow); z-index: 20; max-height: 240px; overflow-y: auto; }
.list:empty { display: none; }
.item { display: flex; gap: .5rem; align-items: center; padding: .4rem .6rem; color: var(--fg); }
.item:hover, .item.on { background: var(--hover); text-decoration: none; }
</style>

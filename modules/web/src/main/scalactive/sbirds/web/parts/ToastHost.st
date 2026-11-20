<script lang="scala">
import sbirds.web.Toasts

// 画面の隅のお知らせ（Toasts.show で出す）. 見た目は各サービスの .toasts・.toast の CSS.
def open(t: sbirds.web.Toast): Unit = {
  t.link.foreach(l => Router.go(l))
  Toasts.dismiss(t.id)
}
</script>

<template>
  <div class="toasts">
    <div v-for="t in Toasts.list" :key="t.id" :class='if (t.error) "toast error" else "toast"'>
      <span class="grow" @click="open(t)">{{ t.text }}</span>
      <button v-for="a in t.action.toList" class="act" @click="Toasts.act(t)">{{ a._1 }}</button>
      <button title="閉じる" @click="Toasts.dismiss(t.id)">×</button>
    </div>
  </div>
</template>

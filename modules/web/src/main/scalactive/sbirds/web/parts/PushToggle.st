<script lang="scala">
import sbirds.web.Pwa

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// プッシュ通知（PWA）のオン・オフ（この端末で受け取る）. 設定の画面に置く.
case class State(state: RV[String] = RV(""), act: ActionState = useAction())

def initState() = State()

def mounted(s: State): Unit = Pwa.state().foreach(s.state := _)

def text(st: String): String = st match {
  case "on"      => "この端末でプッシュ通知を受け取っています（ブラウザを閉じていても届きます）"
  case "off"     => "この端末でプッシュ通知を受け取る（ブラウザを閉じていても届きます）"
  case "denied"  => "ブラウザの設定で通知が拒否されています。サイトの設定から通知を許可してください"
  case "install" => "iPhone・iPad では、共有ボタンの「ホーム画面に追加」で追加したアプリから受け取れます"
  case ""        => ""
  case _         => "このブラウザはプッシュ通知に対応していません"
}

def toggle(s: State): Unit =
  s.act.run(if (s.state.v == "on") Pwa.disable() else Pwa.enable()).foreach(s.state := _)

def label = (st: String) => if (st == "on") "受け取らない" else "受け取る"
def canToggle(s: State) = s.state.mapC(st => st == "on" || st == "off")
</script>

<template>
  <span class="push-toggle">
    <span class="muted small">{{ s.state.mapC(text) }} </span>
    <button v-if="canToggle(s)" type="button" class="small" :disabled="s.act.busy" @click="toggle(s)">{{ s.state.mapC(label) }}</button>
    <span v-if="s.act.failed" class="error small"> {{ s.act.errorMessage }}</span>
  </span>
</template>

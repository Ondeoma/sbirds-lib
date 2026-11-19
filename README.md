# sbirds-lib

sbirds シリーズ（skea・stui・sruru など）で共通に使う部品です。Scala 3・http4s・doobie・Scala.js（Scalactive）。

## 使い方

各サービスの `build.sbt` から、GitHub のリポジトリを直接参照します（タグで版を決める）。

```scala
lazy val sbirdsLib = sys.env
  .get("SBIRDS_LIB")
  .map(p => file(p).getCanonicalFile.toURI)
  .getOrElse(uri("https://github.com/Ondeoma/sbirds-lib.git#v0.1.0"))
lazy val libObserve = ProjectRef(sbirdsLib, "observe")
```

手元の sbirds-lib を変えながら試すときは、`SBIRDS_LIB=../sbirds-lib sbt …` のように手元のディレクトリを指します。

## モジュール

| ProjectRef の名前 | パッケージ | 中身 |
|---|---|---|
| `sharedJVM` / `sharedJS` | `sbirds.shared` | 日時（Timestamp）、エラーの応答（ErrorDto）、エラーコード（ErrorCode・AppError・LibErrorCode） |
| `observe` | `sbirds.observe` | OpenTelemetry（ログ・メトリクス・トレース）、コード付きのログ（`Log.report`） |
| `http` | `sbirds.http` | エラーの応答とログ（`Errors`）、IP ごとの回数の制限（`RateLimit`） |
| `auth` | `sbirds.auth` | OIDC のクライアント（署名の検証あり）、暗号（Crypto・ApiKeys・SecretBox）、アプリの表示名（`AppNames`） |
| `push` | `sbirds.push` | Web Push（VAPID）による通知 |
| `mail` | `sbirds.mail` | SMTP でのメールの送信 |
| `storage` | `sbirds.storage` | S3 互換のオブジェクトストレージ |
| `links` | `sbirds.links` | 兄弟のサービスとの OAuth 連携のトークンの保存 |
| `devidp` | `sbirds.devidp` | 開発用の OIDC プロバイダ |
| `web`（Scala.js） | `sbirds.web` | PWA・観測（RUM）・モーダル・ルートの補助・アプリの表示名（`AppNames`） |

## エラーコード

各サービスは `ErrorCode.scala` の1ファイルに `enum ErrorCode(code, status, message) extends sbirds.shared.ErrorCode` でまとめます。
`Errors.respond` / `Log.report` を通すと、`[SKEA-2005] 404 タスクが見つかりません` の形でログに出ます。lib 自身のコードは `SBL-xxxx` です。

## アプリの表示名

sruru に登録したアプリケーションの名前（グループごとの上書きを反映したもの）を、ID トークンの `sbirds_apps`・`sbirds_app` で受け取ります。
バックエンドは `sbirds.auth.AppNames.fromIdToken`、画面は `sbirds.web.AppNames`（`self`・`of("stui")`）で使います。

## 共通スクリプトと道具

- `scripts/`: 各サービスの `scripts/` に配る共通スクリプト（`lib.sh`・`dev-restart.sh`・`dist.sh`）。プロジェクトごとの設定は各サービスの `scripts/dev-env.sh` に書く。
  直したら `tools/sbirds sync-scripts` で配り、`tools/sbirds check-scripts` で揃っているか確かめる。
- `tools/sbirds`: sbirds の全リポジトリをまとめて扱う道具（`status`・`pull`・`test`・`fmt-check`・`lib-bump` など。`tools/sbirds help` で一覧）。

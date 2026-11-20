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
| `sharedJVM` / `sharedJS` | `sbirds.shared` | 日時（Timestamp）、エラーの応答（ErrorDto）、エラーコード（ErrorCode・AppError・LibErrorCode）、戻り先のパスの確認（SafePath） |
| `observe` | `sbirds.observe` | OpenTelemetry（ログ・メトリクス・トレース）、コード付きのログ（`Log.report`） |
| `http` | `sbirds.http` | エラーの応答とログ（`Errors`）、IP ごとの回数の制限（`RateLimit`・`RateLimitConfig`）、環境変数（`Env`）、画面の配信（`Spa`）、ヘルスチェック（`Health`）、セキュリティのヘッダー（`SecurityHeaders`）、本文の上限（`BodyLimit`）、CSRF の確認（`Csrf`）、Routes の共通部分（`RouteHelpers`・`HttpParts`・`Cookies`）、セッションの Cookie（`SessionCookie`）、OIDC のログインの流れ（`OidcLoginFlow`） |
| `db` | `sbirds.db` | 接続のプールとマイグレーション（`Database`・`DbConfig`）、定期的な処理（`Periodic`）、送信待ちの表（`OutboxResult`・`Outbox`）、リース（`JobLock`）、SQL の部品（`Sql`）、ストアの共通部分（`TxStore`） |
| `net` | `sbirds.net` | SSRF 対策: アドレスの判定表（`NetAddress`）、宛先の確認と、確かめた IP にそのままつなぐクライアント・ソケット（`NetGuard`） |
| `auth` | `sbirds.auth` | OIDC のクライアント（署名の検証あり）、暗号（Crypto・ApiKeys・SecretBox）、アプリの表示名（`AppNames`） |
| `oauth` | `sbirds.oauth` | OAuth の提供側（認可コード + PKCE）: 許可の画面・トークン・失効（`OAuthProvider`）、クライアントの設定（`OAuthClient`）、認可コードと発行した API キーの保存（`OAuthCodeStore`・`OAuthKeyStore`） |
| `push` | `sbirds.push` | Web Push（VAPID）による通知、購読の API（`PushRoutes`） |
| `mail` | `sbirds.mail` | SMTP でのメールの送信 |
| `storage` | `sbirds.storage` | S3 互換のオブジェクトストレージ |
| `links` | `sbirds.links` | 兄弟のサービスとの OAuth 連携のトークンの保存 |
| `mcp` | `sbirds.mcp` | MCP のサーバーの枠: JSON-RPC の振り分け（`McpServer`）、ツールの定義（`McpTool`・`McpSchema`）、REST API の内部呼び出し（`McpCaller`） |
| `devidp` | `sbirds.devidp` | 開発用の OIDC プロバイダ |
| `web`（Scala.js） | `sbirds.web` | PWA・観測（RUM）・モーダル・ルートの補助・アプリの表示名（`AppNames`）、この端末の設定と画面の色（`LocalPrefs`・`ThemeSwitch`）、日時の表示（`Dates`）、`.st` の部品（`sbirds.web.parts`: `Modal`・`PushToggle`・`Combo`・`DialogHost`・`ToastHost`・`UserPicker` など） |

## 共通の部品の使い方

サービスの `build.sbt` には次を足します（`db`・`net` を使うとき）。

```scala
lazy val libDb = ProjectRef(sbirdsLib, "db")
lazy val libNet = ProjectRef(sbirdsLib, "net")
// db: .dependsOn(core, libObserve, libDb)   api: .dependsOn(…, libNet)
```

### DB（`sbirds.db`）

各サービスの `modules/db` にあった `Database.scala`・`JobLock.scala`・`Sql.scala`・`TxStore.scala` の代わりです。使う側は `import sbirds.db.*` を足します。
`job_lock` の表のマイグレーションは各サービスに置きます。サービス固有の SQL の部品（`AliveWindow` など）と JSON の列の型（`Codecs`）は各サービスに残します。

```scala
import sbirds.db.*

Database.migrate(cfg.db)                                  // IO[Int]
Database.transactor(cfg.db)                               // 既定: SQL ごとのトレースあり
Database.transactor(cfg.db, telemetry = false)            // skorimako（自分が観測の受け口）
Database.transactor(cfg.db, startWithoutDb = true)        // skereru（DB が無くても起動を続ける）
Periodic.run(2.minutes, 1.hour, ErrorCode.PurgeFailed)(lock.exclusive("purge", 50.minutes)(purge))
final class TaskStore(xa: Transactor[IO]) extends TxStore(xa) { … tx(…) … Sql.whereAnd(conds) … Sql.like(q) … }
```

### プッシュ通知の購読の API（`sbirds.push.PushRoutes`）

認証と本文の読み取りは、各サービスの `RouteSupport` のものを `Host` として渡します。

```scala
final class PushRoutes(store: PushStore, push: PushService, protected val sessions: SessionAuth) extends RouteSupport { self =>
  private val host = new sbirds.push.PushRoutes.Host {
    def authed(req: Request[IO])(f: UUID => IO[Response[IO]]) = self.authed(req)(s => f(s.user.id))
    def interactive(req: Request[IO])(f: UUID => IO[Response[IO]]) = self.sessionOnly(req)(s => f(s.user.id))
    def body[A: Decoder](req: Request[IO])(f: A => IO[Response[IO]]) = self.body[A](req)(f)
  }
  val routes = new sbirds.push.PushRoutes(store, push, host,
    notAllowed = ErrorCode.PushServiceNotAllowed, invalid = ErrorCode.PushSubscriptionInvalid,
    deviceLimit = Some(20 -> ErrorCode.PushDeviceLimit)   // 端末の数で断るサービスだけ（skakapo・skotare）
  ).routes
}
```

「別のユーザーの端末を上書きしない」（`keepOwner`）と「古いものから消す」（`maxPerUser`）は `PushStore` の引数です。
2 つ目の引数は、lib の `PushService` か、VAPID の公開鍵の文字列です（自前の送信の仕組みを持つ skorimako は `push.publicKey` を渡す）。

### 環境変数（`sbirds.http.Env`）

`Config.scala` の `opt`・`int`・`enabled`・`required` の定義を消して、`import sbirds.http.Env.*` に置き換えます（ほかに `long`・`disabled`・`bool(name, default)`・`list`）。
`list` はカンマと空白のどちらでも区切ります。

### 画面の配信とヘルスチェック（`Spa`・`Health`）

```scala
// バックエンドのパスの決め方は2通り: startsWith（/api で始まる）と firstSegment（最初の区切りが api）
Spa.routes(dir, Spa.startsWith("api", "auth", "hooks", "mcp", "oauth"))
Spa.routes(dir, Spa.firstSegment("api", "auth", "mcp", "healthz"),
  notFound = Errors.respond(ErrorCode.PageNotFound),
  finish = (req, r) => r.putHeaders("Content-Security-Policy" -> csp))      // 画面の CSP を付けるサービス
Spa.indexHtml(dir).map(Spa.inlineScriptHashes)                               // CSP に載せる 'sha256-…'

Health.routes(Database.isPrimary(xa), s"ok ${cfg.nodeId}", e => Log.report(ErrorCode.HealthCheckFailed, "", e))
Health.routes(Database.ping(xa))                                             // プライマリかは見ないサービス
```

### セキュリティのヘッダーと本文の上限（`SecurityHeaders`・`BodyLimit`）

既定（`Policy()`）は nosniff・`X-Frame-Options: DENY`・`Referrer-Policy: strict-origin-when-cross-origin` を付け、応答に CSP が無ければ
何も読み込ませない CSP（`StrictCsp`）を付けます。サービスごとの今の値は引数で渡します。

```scala
// skakapo・skotare・skuaka
SecurityHeaders.app(SecurityHeaders.Policy(hsts = SecurityHeaders.hsts(cfg.api.cookieSecure)))(httpApp)
// 応答が付けたヘッダーを残す・値が違うサービス（stui の例）
SecurityHeaders(SecurityHeaders.Policy(hsts = SecurityHeaders.hsts(https), referrer = Some("same-origin"),
  csp = Some(SecurityHeaders.FrameCsp), keepExisting = true))(routes)
// 要求ごとに変える（smohua の埋め込み用の /f/…）
SecurityHeaders.routesWith(req => if embeddable(req) then embedPolicy else policy)(routes)

// 本文の上限: 要求ごとの上限（None なら掛けない）と、超えたときのエラー
BodyLimit(req => Some(if isUpload(req) then maxUpload else 2L * 1024 * 1024), (_, _) => AppError(ErrorCode.RequestTooLarge))(routes)
BodyLimit.request(req, max)(new TooLarge(max))                               // 1つのルートの中で掛ける（skawau）
```

`BodyLimit` は、読んでいる途中で超えると `tooLarge` の `AppError` を投げます。ルートが受け止めなければその応答（413 など）になるので、
`EntityLimiter.EntityTooLarge` を外側で読み替える処理は要りません。

### 回数の制限の設定（`RateLimitConfig`）

```scala
rate <- RateLimitConfig.fromEnv()   // RATE_LIMIT_AUTH（20）・RATE_LIMIT_PUBLIC（120）・TRUSTED_PROXIES. 誤りは Left(文言)
// 全サービス共通の数え方（IP ごと・1分あたり・ノードごと）. サービスは、追加で数えるパスと対象から外すパスだけを渡す
RateLimit.standard(routes, rate, ErrorCode.TooManyRequests)()
RateLimit.standard(routes, rate, ErrorCode.TooManyRequests, clock) { b =>
  val submit = b.counter(submitPerMinute)                                          // サービス独自の枠
  {
    case RateLimit.Path("hooks" :: _) => b.skip                                    // 対象から外す
    case RateLimit.Path("api" :: "rooms" :: _ :: "join" :: Nil) => b.public        // 追加で数える
    case req @ RateLimit.Path("api" :: "public" :: "forms" :: _ :: "responses" :: Nil) if req.method == Method.POST =>
      b.public ++ submit                                                           // 枠を重ねる
  }
}
```

`standard` の数え方（ログインしなくても届く入口だけを数える）:

| 枠 | パス |
|---|---|
| auth（`RATE_LIMIT_AUTH`。既定 20） | `/auth/…`・`/oauth/authorize` |
| public（`RATE_LIMIT_PUBLIC`。既定 120） | `/oauth/token`・`/oauth/revoke`・`/api/public/…` |
| 数えない | それ以外（ログインした人・API キーの API、`/mcp` など） |

`extra`（2つめの引数）は共通の数え方より先に見ます。上限を超えたら 429（`code`）と `Retry-After` を返します。
数え方が別のサービス（sruru）は、`RateLimit(routes, trusted, code)(rules)`・`RateLimit.keyed` に、どのカウンタで数えるかを全部渡します。

### 戻り先のパスの確認（`sbirds.shared.SafePath`）

画面（Scala.js）からも使えます。`isLocal`・`orElse(p, default)` は「/ で始まり //・/\ でなく制御文字がない」、
`isStrict`・`of(s, default)` は使える文字を URL の文字だけに絞った版です（サービスごとに、今使っているほうを選びます）。

### SSRF 対策（`sbirds.net`）

「内部のアドレスを許す」設定の環境変数は各サービスが読み、`Policy` にして渡します。

```scala
import sbirds.net.NetGuard
import sbirds.net.NetGuard.{Allow, Policy, Rejected}

NetGuard.check(url, Policy.publicOnly)                         // IO[Either[Rejected, Unit]]（文言は r.message か自分で）
NetGuard.isAllowed(url, Policy.of(cfg.webhookAllowPrivate))    // IO[Boolean]. true ならどこでも許す
Policy(Allow.Private, hosts = Set("git.example.internal"))     // 社内は許すが loopback・メタデータは許さない（skea）
Policy.allowList(Env.list("PROBE_ALLOW_INTERNAL"))             // Either[String, Policy]. 内部のうち、書いたホスト名・IP・CIDR だけ許す（skotare）
NetGuard.client(Policy.of(cfg.webhookAllowPrivate))            // Resource[IO, Client[IO]]. 確かめた IP にそのままつなぐ. リダイレクトは追わない
NetGuard.client(policy, timeout = 30.seconds, maxResponseHeaderSize = Some(64 * 1024))   // 応答のヘッダーの上限（ほかは configure = _.with…）
NetGuard.pick(host, addrs, policy).map(NetGuard.socketFactory(_))  // 名前を自分で引くとき（JavaMail の mail.smtp.socketFactory など）
NetAddress.isInternal(ip)                                      // 判定表だけ使うとき
```

### CSRF の確認（`sbirds.http.Csrf`）

Cookie のセッションで状態を変える要求が、自分の画面から送られたものかを確かめます。確かめ方は `Csrf.Policy` で選びます。
既定（`Policy(publicUrl)`）がいちばん厳しく、ほかの引数は各サービスの今の挙動を保つための差です。

| 引数 | 既定 | 意味 |
|---|---|---|
| `origin` | （必須。`headerOnly` では None） | 自分のオリジン（PUBLIC_URL）。Sec-Fetch-Site が same-site・cross-site なら断る。Origin（なければ Referer）が自分のオリジンでなければ断る（`null` も断る）。どれもなければブラウザ以外として通す |
| `header` | None | 画面が付ける専用のヘッダー（`X-Skaka` など）。Some なら、それがない要求を断る |
| `devLocalhost` | false | 開発中（DEV_LOGIN）だけ true: `http://localhost`・`http://127.0.0.1`（ポートは何でも）も自分の画面として扱う |
| `hostFallback` | false | Origin が要求の Host ヘッダーと同じなら通す（skiwi） |
| `trustFetchSite` | false | Sec-Fetch-Site が same-origin・none なら Origin・Referer を照合しない（skawau） |
| `safeMethods` | `Csrf.Safe`（GET・HEAD） | 確かめないメソッド。OPTIONS も確かめないサービスは `Csrf.SafeWithOptions` |

「どの要求を確かめるか」（Cookie のセッションのときだけ・Bearer でなければいつも、など）は認証の作りで決まるので、各サービスが決めます。

```scala
// オリジンの照合を全体に掛ける（Authorization がなく、セッションの Cookie がある更新系だけ. stui・skorimako）
val csrf = Csrf.Policy(cfg.publicUrl, devLocalhost = cfg.devLogin).copy(safeMethods = Csrf.SafeWithOptions)
Csrf.routes(csrf, Csrf.cookieAuth(cfg.cookieName), ErrorCode.CrossSiteRequest, skip = req => isIngest(req))(routes)
// 認証の中で確かめる（skea・skawau）
if s.isSession && Csrf.foreign(req, csrf) then err(ErrorCode.CrossSiteRequest) else f(s)
// 専用のヘッダー（skaka・skakapo・skotare・skuaka・skereru・sruru）
private val csrf = Csrf.Policy.headerOnly("X-Skakapo")
if !bearer && Csrf.foreign(req, csrf) then err(ErrorCode.CrossSiteRequest) else …
Csrf.fromOwnScreen(req, csrf)        // メソッドを見ない版（ログアウト・開発用のログインなど、ログインしていない人の更新にも使う）
Csrf.Policy(cfg.publicUrl).withHeader("X-Skaka")   // 両方を確かめる（ヘッダーのサービスを厳しくするとき）
```

### MCP のサーバーの枠（`sbirds.mcp`）

各サービスの `McpRoutes.scala` に共通していた部分です。`build.sbt` に `lazy val libMcp = ProjectRef(sbirdsLib, "mcp")` を足し、`api` の `dependsOn` に加えます。

枠（`McpServer`）が受け持つこと: Bearer の確認の流れ（401 と `WWW-Authenticate: Bearer realm="<name>"`）、本文の解析（読めなければ 400・-32700）、
まとめての呼び出しの上限（既定 20 件。超えたら 400・-32600。一部だけ処理することはしない）、`initialize`・`ping`・`tools/list`・`tools/call`・
`resources/list`・`prompts/list` の振り分け（知らないメソッドは -32601、知らないツールは -32602）、通知（id なし）には返さない（202）、
参照のみのキーで変更するツールを呼んだら `isError` の結果、ツールの例外は `toolFailed` のコード付きでログに出し、利用者には「内部エラー（コード）」だけを返す（例外の文は返さない）。
ツールの中身・説明の文・キーの確認はサービスに残ります。

```scala
import sbirds.mcp.{McpCaller, McpServer, McpTool}
import sbirds.mcp.McpSchema.*          // schema・str・int・bool・strs・obj・need・s・withArgs・asText

final class McpRoutes(api: HttpRoutes[IO], sessions: SessionAuth, publicUrl: String) extends Http4sDsl[IO] {
  // POST /mcp と GET・DELETE /mcp（405）は McpServer. 固有のパスは <+> で足す（host がツールの一覧を使うので lazy val）
  lazy val routes: HttpRoutes[IO] = McpServer.routes(host) <+> HttpRoutes.of[IO] {
    case GET -> Root / "api" / "endpoints" => getEndpoints()
  }

  private type Caller = McpCaller          // REST API を、呼び出し元のキーで内部から呼ぶ（Right は JSON、Left は「403 文」の形）
  private type Tool = McpTool[Caller]
  private def Tool(name: String, description: String, schema: Json, readOnly: Boolean, destructive: Boolean = false)(
    run: (Caller, ACursor) => McpTool.Result
  ): Tool = McpTool(name, description, schema, readOnly, destructive)(run)

  private val tools: List[Tool] = List(
    Tool("get_me", "自分の情報。", schema(Nil), readOnly = true)((call, _) => call(Method.GET, McpCaller.path("api", "me")).asText),
    …
  )

  private lazy val host = new McpServer.Host[AuthSession, Caller] {
    val settings = McpServer.Settings("stui", toolFailed = ErrorCode.McpToolFailed)
    val tools = McpRoutes.this.tools
    def session(req: Request[IO], auth: Authorization) = sessions.fromRequest(Request[IO](Method.GET, Uri.unsafeFromString("/mcp")).putHeaders(auth))
    def unauthorized = Errors.respond(ErrorCode.McpApiKeyRequired)          // WWW-Authenticate は枠が付ける
    def context(req: Request[IO], auth: Authorization, s: AuthSession) = new McpCaller(api, auth, List("X-Stui-Mcp" -> "1"))
    def canWrite(s: AuthSession) = s.canWrite
    def instructions(s: AuthSession) = "stui（チームのチャット）の…"
  }
}
```

`Settings` で渡す差: `version`（serverInfo）、`maxBatch`・`batchTooLarge`（上限と文）、`maxBodyBytes`（/mcp だけの本文の上限）、
`readOnlyDenied`（参照のみのキーのときの文）、`hideDenied`（tools/list に使えないツールを載せない。既定 true）、`failure`（例外のときに返す文）、
`parallelReads`（変更するツールを含まないバッチを並行して処理する数）。
`Host` で差し替えられるもの: `refuse(s)`（キーは有効だが MCP は使わせない。skorimako の取り込み専用のキー）、
`denied(s, tool)`（スコープで決めるサービス。`McpTool` の `scope` を使う）。ツールに持ち主の情報も渡すサービスは、文脈の型 C を自分の `Ctx(call, me)` にします。
`McpCaller` の引数: 足すヘッダー・`timeout`（終わらない応答を区切る）・`build`（要求の組み立て。文字列をそのまま本文にするサービス）。

### 画面の共通部品（`sbirds.web`・`sbirds.web.parts`）

lib の web モジュールは Scalactive のプラグインを有効にしていて、`.st` の部品を `modules/web/src/main/scalactive/sbirds/web/parts/` に置けます
（生成される object は `sbirds.web.parts.<名前>`）。タグは Scala の名前として解決されるので、サービス側は自分の `parts` パッケージから export すれば、
既存の `.st`（`import <svc>.web.parts.*`）を変えずに `<Modal>`・`<PushToggle />` と書けます。見た目（クラス名）は各サービスの `frontend/style.css` のままです。

```scala
// modules/web/src/main/scala/<svc>/web/parts/Lib.scala
package stui.web.parts
export sbirds.web.parts.{Modal, PushToggle}

// Store.scala など（呼び出し側の Prefs.get・Theme.set・Theme() はそのまま）
object Prefs extends sbirds.web.LocalPrefs("stui")                       // localStorage の鍵は "stui.<key>"
object Theme extends sbirds.web.ThemeSwitch(Prefs)                       // 選択肢に絵文字を付けないサービスは ThemeSwitch(Prefs, ThemeSwitch.Plain)

// Fmt.scala（Fmt.dateTime(t)・Fmt.ago(t) はそのまま使える. t は sbirds.shared.Timestamp）
object Fmt { export sbirds.web.Dates.{ago, dateTime}; … }
```

| 部品 | 中身 |
|---|---|
| `parts.Modal` | モーダルの枠（`Props(onClose, wide, xwide, slot)`。背景のクリックか Esc で閉じる。クラスは `modal-bg`・`modal`・`modal wide`・`modal xwide`） |
| `parts.PushToggle` | プッシュ通知のオン・オフ（`Pwa` を使う。クラスは `push-toggle`・`muted small`・`error small`） |
| `LocalPrefs(service)` | この端末だけの設定（localStorage。`get(key, default)`・`set(key, value)`） |
| `ThemeSwitch(prefs, options)` | 画面の色（`current`・`apply()`・`set(v)`・`options`。html の `data-theme` を付け外しする） |
| `Dates` | `dateTime(t)`（2026/11/15 09:30）・`ago(t)`（「3分前」。7 日を超えたら日付） |
| `ApiBase` | `object Api extends ApiBase` の共通部分（get・post・put・patch・delete・`deleteWith`（本文つき）・`postRaw`・`putRaw`（画像などをそのまま）・`postFile`（multipart の file）・query・seg）。401 はログインへ |
| `Toasts`・`parts.ToastHost` | お知らせ（`show(text, link, error, action, ms)`・`withAction(text, label)(f)`・`error`・`report`）。`duration`・`errorDuration`・`max` を起動時に変えられる。ボタンのクラスは `act` |
| `Dialog`・`parts.DialogHost` | 確認・入力・選択（`confirm`・`prompt(danger, placeholder)`・`choose(radio, onCancel)`・`alert`） |
| `LeaveGuard.install(dirty)` | 保存していない変更があるときに、画面を移る・閉じる前に確かめる |
| `parts.Avatar`・`parts.Combo`・`Person`・`Opt` | 人のアイコン、オートコンプリート（`onQuery`・`filter = false` でサーバーの検索にも使える） |
| `parts.UserPicker`・`PeopleSearch` | 人を探して選ぶ（`search: String => Future[List[Person]]`。`PeopleSearch` を使うと、選んだ Person から元の値を引ける。`combo` で Combo の見た目） |
| `parts.EmojiPicker`・`CustomEmojis` | リアクションの絵文字（分類は `categories` で渡す）とカスタム絵文字（/api/emoji） |
| `parts.ApiTokenSettings`・`ApiTokens` | API トークンの作成・失効と MCP のつなぎ方（/api/tokens。文言は props） |
| `Rich.directive(lowlight, languages)` | v-html の後処理（コードブロックの色付け・連携したサービスのリンクの名前。`LinkTitles.start` で動かす） |
| `Dom`・`Images`・`Formats` | DOM のイベント・コピー・ダウンロード・ファイル、アイコンの切り抜き、頭文字・バイト数 |

### OAuth の提供側（`sbirds.oauth`）

各サービスの `OAuthRoutes.scala`・`OAuthStore.scala`・`Config.scala` の `oauthClients`・`OAuthClient` の代わりです。
`build.sbt` に `lazy val libOauth = ProjectRef(sbirdsLib, "oauth")` を足し、`api`（ストアを `db` に置くなら `db` も）の `dependsOn` に加えます。

`OAuthProvider` は `/oauth/authorize`（GET・POST）・`/oauth/token`・`/oauth/revoke` を持ちます。次のことは lib が決めていて、サービスからは変えられません:
許可は画面のログイン（Cookie のセッション）だけ、redirect_uri は完全一致、PKCE（S256）は必須、コードは 1 回限り、クライアントの秘密は定数時間で比べる、
フォームとして読めない本文は 500 にせずエラーの画面（トークン・失効では `invalid_request`）。
サービスごとの違いは `OAuthProvider.Host[S]`（S は画面のセッションの型）と `OAuthProvider.Settings` で差し込みます。

```scala
import sbirds.oauth.{OAuthClient, OAuthCode, OAuthKeyStore, OAuthProvider}

// 設定: OAUTH_CLIENTS（無ければ空）. redirectUri は https:// か、手元のホスト（localhost・127.0.0.1・[::1]・*.localhost）の http://
clients <- OAuthClient.fromEnv()
clients <- OAuthClient.fromEnv(OAuthClient.Rules(                       // 知らないスコープ・id の重なりをエラーにするサービス
  scopes = List("read", "write", "send"),
  scopeError = Some(id => s"OAUTH_CLIENTS の $id の scopes は read・write・send から選んでください"),
  duplicateError = Some("OAUTH_CLIENTS の id が重なっています")))

// 保存: oauth_code と api_key（text[] の scopes・oauth_client）の形なら OAuthKeyStore だけでよい（表のマイグレーションは各サービス）
val oauth = new OAuthKeyStore(xa, revokeOnReuse = true)                 // 使用済みのコードが再び出されたら、そのコードで発行したキーを失効させる
// 発行先の表が違うサービスは、認可コードだけ lib に任せる（onReuse は同じトランザクションで動く ConnectionIO）
final class OAuthStore(xa: Transactor[IO]) extends sbirds.oauth.OAuthCodeStore(xa, hash => sql"…".update.run.void) { … grant・refresh … }

final class OAuthRoutes(oauth: OAuthKeyStore, activity: ActivityStore, sessions: SessionAuth, cfg: ApiConfig) {
  private val host = new OAuthProvider.Host[AuthSession] {
    val settings = OAuthProvider.Settings("stui", cfg.cookieSecure)     // 名前は Cookie（stui_oauth_csrf）・realm・画面の題に使う
    val codes = oauth
    def client(id: String) = IO.pure(cfg.oauthClients.find(_.id == id))
    def browserSession(req: Request[IO]) = sessions.fromRequest(req).map(_.filter(_.isSession))   // API キーの Bearer は None にする
    def userId(s: AuthSession) = s.user.id
    def consent(req: Request[IO], c: OAuthClient, s: AuthSession, scopes: List[String]) =        // フォームの手前までの本文
      IO.pure(OAuthProvider.Consent(s"${c.name} との連携", s"<h1>…</h1><ul class=\"muted\">…</ul>"))
    override def authorized(req: Request[IO], s: AuthSession, c: OAuthClient, scopes: List[String]) =
      activity.audit(Some(s.user.id), "web", "oauth.authorize", Some(c.id), Json.obj())
    def issue(req: Request[IO], c: OAuthClient, oc: OAuthCode) = {       // 認可コードを確かめた後に呼ばれる
      val (key, prefix) = ApiKeys.generate()
      oauth.replaceKey(oc.userId, c.id, s"${c.name} 連携", prefix, Crypto.sha256(key), oc.scope.split(' ').toList)
        .as(OAuthProvider.Token(key, oc.scope))                         // refresh のあるサービスは expiresIn・refreshToken も
    }
    def revoke(req: Request[IO], c: OAuthClient, token: String) = oauth.revokeKey(Crypto.sha256(token), c.id)
  }
  val routes: HttpRoutes[IO] = new OAuthProvider(host).routes           // サービス固有のパスは <+> HttpRoutes.of[IO] { … } で足す
}
```

`Host` で差し替えられるもの（どれも既定がある）:

| メンバー | 既定 | 差し替えるサービスの例 |
|---|---|---|
| `scopes(c, requested)` | 空白区切り・省くと read・クライアントに許したものだけ（`OAuthProvider.requestedScopes`） | 並べ直す（skereru）・集合で扱う（skotare） |
| `codeScope(c, scopes)` | 空白区切り | トークンのスコープが 1 つの値（read / write）のサービス（skaka・skuaka・skotare） |
| `page(title, body, status)` | sbirds 共通の枠（`Settings.accent`・`extraCss` で色と CSS を足せる） | ロゴの画像・CSP が違う（skaka・skereru・skiwi）. `OAuthProvider.htmlResponse` で応答にする |
| `refresh` | None（`refresh_token` は unsupported_grant_type） | skereru・skuaka・skotare: `Some((req, c, refreshToken, form) => IO[Either[Failure, Token]])` |
| `secretMatches(c, secret)` | 平文を定数時間で比べる | 秘密をハッシュで持つ（skiwi） |
| `decisionSession(req)` | `browserSession` が無ければ「ログインが切れました…」 | 文言を分ける（skiwi） |
| `limit(req)` | そのまま | フォームを読む前に本文の上限を掛ける（skawau） |
| `authorized(…)` | 何もしない | 監査の記録 |

`Settings` のそのほかの項目: `loginRedirectNoStore`・`backNoStore`・`revokeNoStore`（その応答に `Cache-Control: no-store` を付ける）、
`invalidScopeDescription`、`grantTypeMissing`、`formValue`（トークン・失効のフォームの値の扱い。skotare は `v => Some(v.trim).filter(_.nonEmpty)`）、
`unreadableForm`・`loginExpired`・`csrfExpired`（エラーの画面の文言）、`loginPath`、`codeTtlSeconds`。
メタデータ（RFC 8414）が要るサービスは `OAuthProvider.metadata(publicUrl, scopes, refresh)` を足します（既定では付けません）。

### Routes の共通部分・セッション・ログイン（`sbirds.http`）

各サービスの `RouteSupport`・`SessionAuth`・`AuthRoutes` に共通していた部分です。主体の型（AuthSession・Principal）と認証（`authed` など）は各サービスに残します。

```scala
// RouteSupport: body・param・intParam・UuidVar・redirect は RouteHelpers のものを使う
trait RouteSupport extends sbirds.http.RouteHelpers {
  protected def sessions: SessionAuth
  protected def bodyInvalid: sbirds.shared.ErrorCode = ErrorCode.RequestBodyInvalid
  // 本文の読み取り中の AppError（BodyLimit の「大きすぎる」）を bodyInvalid にまとめていたサービスだけ:
  // override protected def bodyKeepsAppError = false
  …err・authed・sessionOnly などはサービスごと…
}

// SessionAuth: Cookie の組み立て・発行・取り消し・表示名
private val cookie = new SessionCookie(cfg.cookieName, cfg.cookieSecure, cfg.sessionTtl)
def fromRequest(req: Request[IO]) = HttpParts.bearer(req).filter(ApiKeys.looksLike) match {
  case Some(key) => …
  case None => cookie.hash(req) match { case Some(h) => users.findSession(h, Instant.now())…; case None => IO.pure(None) }
}
def issue(user: User, idToken: Option[String] = None) = cookie.issue((hash, expires) => users.createSession(hash, user.id, expires, idToken))
def appNames(req: Request[IO]) = cookie.appNames(req)(users.sessionIdToken)
def revoke(req: Request[IO]) = cookie.revoke(req)(users.deleteSession)

// AuthRoutes: login・callback・logout
private def getAuthLogin(req: Request[IO]) = OidcLoginFlow.login(flow)(req)
private def getAuthCallback(req: Request[IO]) = OidcLoginFlow.callback(flow)(req)
private val flow = new OidcLoginFlow.Host[LoginResult] {
  val idp = oidc.map(OidcLoginFlow.Idp(_))                               // 自前の OIDC クライアントは Idp[R] を実装して渡す
  val cookie = OidcLoginFlow.StateCookie("skotare_login", cfg.cookieSecure, maxAge = 600, hashed = true)
  override def idpErrorParam = true                                      // IdP の error パラメータを見るサービス
  def returnTo(req: Request[IO]) = SafePath.of(param(req, "returnTo"))
  def saveState(state: String, verifier: String, nonce: String, back: String) = users.saveLoginState(state, verifier, nonce, back)
  def takeState(state: String) = users.takeLoginState(state)
  def redirect(to: String) = AuthRoutes.this.redirect(to)
  def withoutIdp(req: Request[IO], back: String) = if cfg.devLogin then redirect(s"/login?returnTo=…") else err(ErrorCode.OidcNotConfigured)
  def failed(req: Request[IO], f: OidcLoginFlow.Failure) = f match {     // 理由ごとの応答（エラーコード・画面）はサービスごと
    case Failure.Denied(e) => err(ErrorCode.LoginDenied, e.getMessage)   // LoginDenied の文言だけは利用者に見せてよい
    case Failure.Failed(e) => Errors.respondLogged(ErrorCode.LoginFailed, String.valueOf(e.getMessage), Some(req), Some(e))  // 詳細はログにだけ
    case …
  }
  def loggedIn(req: Request[IO], res: LoginResult, back: String) = …upsert → 無効の確認 → sessions.issue → redirect(back, Some(cookie))…
}
OidcLoginFlow.logoutRedirect(oidc, idToken, s"${cfg.publicUrl}/", fallback = "/")   // IdP の end_session の URL（なければ fallback）
```

`StateCookie` の引数でサービスごとの今の値を渡します: `maxAge`（600 か 900）、`path`（既定 `/auth`）、`hashed`（Cookie には state の SHA-256 を入れる）、
`clear`（`Never`・`OnSuccess`〈既定。`loggedIn` が 302 を返したとき〉・`Always`）、`checkWithoutCode`（code が無くても先に Cookie と照合する）。
`Errors.respondLogged(code, logDetail, req, cause)` は、詳細をログにだけ出して、利用者には code の既定の文言を返します。

## エラーコード

各サービスは `ErrorCode.scala` の1ファイルに `enum ErrorCode(code, status, message) extends sbirds.shared.ErrorCode` でまとめます。
`Errors.respond` / `Log.report` を通すと、`[SKEA-2005] 404 タスクが見つかりません` の形でログに出ます。lib 自身のコードは `SBL-xxxx` です。

## アプリの表示名

sruru に登録したアプリケーションの名前（グループごとの上書きを反映したもの）を、ID トークンの `sbirds_apps`・`sbirds_app` で受け取ります。
バックエンドは `sbirds.auth.AppNames.fromIdToken`、画面は `sbirds.web.AppNames`（`self`・`of("stui")`）で使います。

## 共通スクリプトと道具

- `scripts/`: 各サービスの `scripts/` に配る共通スクリプト（`lib.sh`・`dev-restart.sh`・`dist.sh`）。プロジェクトごとの設定は各サービスの `scripts/dev-env.sh` に書く。
  直したら `tools/sbirds sync-scripts` で配り、`tools/sbirds check-scripts` で揃っているか確かめる。
- `templates/`: 同じ仕組みで配る、スクリプト以外の共通ファイル（`logback.xml`・`frontend/public/sw.js`・`frontend/vite-scalajs.js`）。
  配る先・サービスごとに残す行（sw.js の `const SKIP = `・`const CACHE = `）・配らないリポジトリ（logback.xml は skorimako）は `tools/sbirds` の `SHARED_FILES` に書く。
  ひな形の中の `@SERVICE@` はリポジトリの名前に置き換わる。
- `tools/sbirds`: sbirds の全リポジトリをまとめて扱う道具（`status`・`pull`・`test`・`fmt-check`・`lib-bump` など。`tools/sbirds help` で一覧）。

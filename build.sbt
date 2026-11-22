// sbirds シリーズ（skea・stui・sruru など）で共通に使う部品
// 各サービスからは GitHub のリポジトリを直接参照する（ProjectRef(uri("https://github.com/Ondeoma/sbirds-lib.git#<タグ>"), "observe") など）
ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "io.github.ondeoma"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / licenses := List("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / homepage := Some(url("https://github.com/Ondeoma/sbirds-lib"))
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:imports", "-no-indent")

val http4sV = "1.0.0-M49"
val circeV = "0.14.16"
val doobieV = "1.0.0-RC12"
val ceV = "3.7.1"
val fs2V = "3.14.0"
val munitV = "1.3.6"
val otel4sV = "1.2.0"
val otelV = "1.68.0"
val otelInstV = "2.33.1-alpha"
val awsV = "2.30.38"
val flywayV = "11.3.0"

lazy val commonTest = Seq(
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit" % munitV % Test,
    "org.typelevel" %% "munit-cats-effect" % "2.2.1" % Test
  ),
  Test / fork := true
)

// バックエンドと画面（Scala.js）で共有する型（日時・エラーの応答・アプリの表示名）
lazy val shared = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("modules/shared"))
  .settings(name := "sbirds-shared")
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core" % circeV,
      "io.circe" %%% "circe-parser" % circeV
    )
  )

// 観測（OpenTelemetry: otel4s-oteljava）. ログ・メトリクス・トレースを OTLP で skorimako に送る
lazy val observe = (project in file("modules/observe"))
  .settings(name := "sbirds-observe")
  .dependsOn(shared.jvm)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "otel4s-oteljava" % otel4sV,
      "org.typelevel" %% "otel4s-oteljava-context-storage" % otel4sV,
      "org.typelevel" %% "otel4s-instrumentation-metrics" % otel4sV,
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % otelV,
      "io.opentelemetry.instrumentation" % "opentelemetry-logback-appender-1.0" % otelInstV,
      "io.opentelemetry.instrumentation" % "opentelemetry-logback-mdc-1.0" % otelInstV,
      "io.opentelemetry.instrumentation" % "opentelemetry-runtime-telemetry" % otelInstV,
      "org.http4s" %% "http4s-server" % http4sV,
      "org.http4s" %% "http4s-client" % http4sV,
      "org.http4s" %% "http4s-circe" % http4sV,
      "org.http4s" %% "http4s-dsl" % http4sV,
      "ch.qos.logback" % "logback-classic" % "1.5.18",
      "org.tpolecat" %% "doobie-core" % doobieV
    )
  )

// 認証の部品（OIDC のクライアント・暗号・APIキー）
lazy val auth = (project in file("modules/auth"))
  .settings(name := "sbirds-auth")
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-client" % http4sV,
      "org.http4s" %% "http4s-circe" % http4sV,
      "io.circe" %% "circe-parser" % circeV,
      "org.typelevel" %% "cats-effect" % ceV
    )
  )

// HTTP の API の共通部分（エラーコードと応答・ログ）
lazy val http = (project in file("modules/http"))
  .settings(name := "sbirds-http")
  .dependsOn(shared.jvm, observe, auth)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % http4sV,
      "org.http4s" %% "http4s-circe" % http4sV,
      "org.http4s" %% "http4s-server" % http4sV,
      "org.typelevel" %% "log4cats-noop" % "2.8.0" % Test
    )
  )

// プッシュ通知（PWA. Web Push の暗号化と VAPID は JDK の暗号の機能だけで実装）
lazy val push = (project in file("modules/push"))
  .settings(name := "sbirds-push")
  .dependsOn(observe, http)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-client" % http4sV,
      "org.http4s" %% "http4s-dsl" % http4sV,
      "org.http4s" %% "http4s-circe" % http4sV,
      "io.circe" %% "circe-core" % circeV,
      "org.tpolecat" %% "doobie-core" % doobieV,
      "org.tpolecat" %% "doobie-postgres" % doobieV
    )
  )

// 画面（Scala.js）の共通部分（API の呼び出し・PWA・観測・モーダルなど）
lazy val web = (project in file("modules/web"))
  .settings(name := "sbirds-web")
  .enablePlugins(ScalaJSPlugin, ScalactivePlugin)
  .dependsOn(shared.js)
  .settings(
    libraryDependencies ++= Seq(
      "io.github.ondeoma" %%% "scalactive" % "0.7.0"
    )
  )

// メールの送信（SMTP）
lazy val mail = (project in file("modules/mail"))
  .settings(name := "sbirds-mail")
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.eclipse.angus" % "angus-mail" % "2.0.3",
      "org.typelevel" %% "cats-effect" % ceV
    )
  )

// ファイルの保存先（S3 互換のオブジェクトストレージ）
lazy val storage = (project in file("modules/storage"))
  .settings(name := "sbirds-storage")
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      // netty-nio-client は依存ごとに外す（プロジェクト全体で外すと下流の解決で AWS SDK 自体が落ちることがある）
      ("software.amazon.awssdk" % "s3" % awsV).exclude("software.amazon.awssdk", "netty-nio-client"),
      "software.amazon.awssdk" % "apache-client" % awsV,
      "co.fs2" %% "fs2-io" % fs2V,
      "org.typelevel" %% "cats-effect" % ceV
    )
  )

// 兄弟のサービスとの OAuth 連携（トークンの保存・認可コードとの交換・失効）と、stui（チャット）への投稿
lazy val links = (project in file("modules/links"))
  .settings(name := "sbirds-links")
  .dependsOn(auth, db, net)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "doobie-core" % doobieV,
      "org.tpolecat" %% "doobie-postgres" % doobieV
    )
  )

// DB の共通部分（接続のプール・マイグレーション・定期的な処理・リース・SQL の部品）
lazy val db = (project in file("modules/db"))
  .settings(name := "sbirds-db")
  .dependsOn(shared.jvm, observe)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "doobie-core" % doobieV,
      "org.tpolecat" %% "doobie-hikari" % doobieV,
      "org.tpolecat" %% "doobie-postgres" % doobieV,
      "org.postgresql" % "postgresql" % "42.7.14",
      "org.flywaydb" % "flyway-core" % flywayV,
      "org.flywaydb" % "flyway-database-postgresql" % flywayV % Runtime
    )
  )

// 外への接続の確認（SSRF 対策: 内部のアドレスの判定・確かめた IP にそのままつなぐクライアント）
lazy val net = (project in file("modules/net"))
  .settings(name := "sbirds-net")
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-client" % http4sV,
      "org.typelevel" %% "cats-effect" % ceV,
      "org.typelevel" %% "log4cats-noop" % "2.8.0"
    )
  )

// OAuth の提供側（認可コード + PKCE. 許可の画面・トークン・失効・クライアントの設定・認可コードの保存）
lazy val oauth = (project in file("modules/oauth"))
  .settings(name := "sbirds-oauth")
  .dependsOn(shared.jvm, auth, http, db)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % http4sV,
      "org.http4s" %% "http4s-circe" % http4sV,
      "io.circe" %% "circe-parser" % circeV,
      "org.tpolecat" %% "doobie-core" % doobieV,
      "org.tpolecat" %% "doobie-postgres" % doobieV
    )
  )

// MCP（Model Context Protocol）のサーバーの枠（JSON-RPC の振り分け・ツールの定義・REST API の内部呼び出し）
lazy val mcp = (project in file("modules/mcp"))
  .settings(name := "sbirds-mcp")
  .dependsOn(shared.jvm, observe, http)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % http4sV,
      "org.http4s" %% "http4s-circe" % http4sV,
      "io.circe" %% "circe-parser" % circeV,
      "org.typelevel" %% "log4cats-noop" % "2.8.0" % Test
    )
  )

// 開発用の OIDC プロバイダ
lazy val devidp = (project in file("modules/devidp"))
  .settings(name := "sbirds-devidp")
  .dependsOn(auth)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % http4sV,
      "org.http4s" %% "http4s-server" % http4sV,
      "org.http4s" %% "http4s-circe" % http4sV,
      "org.http4s" %% "http4s-ember-server" % http4sV,
      "org.typelevel" %% "cats-effect" % ceV,
      "org.typelevel" %% "log4cats-noop" % "2.8.0"
    )
  )

lazy val root = (project in file("."))
  .aggregate(shared.jvm, shared.js, observe, auth, http, push, web, mail, storage, links, devidp, db, net, oauth, mcp)
  .settings(name := "sbirds-lib", publish / skip := true)

// JDK 25 以上でビルドする（本番も JDK 25 で動かす）
Global / onLoad := (Global / onLoad).value.andThen { s =>
  val v = java.lang.Runtime.version.feature
  if (v < 25) sys.error(s"JDK 25 以上が必要です（いまは JDK $v）")
  s
}

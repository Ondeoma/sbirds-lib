package sbirds.mcp

import cats.effect.IO
import io.circe.{ACursor, Json}
import io.circe.syntax.*

/**
 * MCP のツールの定義（名前・説明・入力スキーマ）と中身. C は呼び出しの文脈（REST API を内部から呼ぶ McpCaller や、それに持ち主の情報を足したもの）.
 * run の結果は Right が成功の文、Left が利用者（エージェント）に見せてよい失敗の文. 例外は枠（McpServer）がコード付きでログに出し、中身は返さない.
 * {{{
 * McpTool[McpCaller]("get_me", "自分の情報。", McpSchema.schema(Nil), readOnly = true)((call, _) => call(Method.GET, McpCaller.path("api", "me")).asText)
 * }}}
 *
 * @param readOnly    状態を変えない（参照のみのキーでも使える）. annotations の readOnlyHint になる.
 * @param destructive 消す・上書きするなど、取り消せない変更（destructiveHint）.
 * @param openWorld   外の世界に働きかける（メールの送信など. openWorldHint）.
 * @param scope       スコープで使えるツールを決めるサービス用（skereru の read / write / send など）. 枠は見ない（Host.denied で使う）.
 */
final case class McpTool[C](name: String,
                            description: String,
                            schema: Json,
                            readOnly: Boolean,
                            destructive: Boolean = false,
                            openWorld: Boolean = false,
                            scope: String = "")(val run: (C, ACursor) => McpTool.Result) {

  /** tools/list に載せる形. */
  def json: Json = Json.obj(
    "name" -> name.asJson,
    "description" -> description.asJson,
    "inputSchema" -> schema,
    "annotations" -> Json.obj(
      "readOnlyHint" -> readOnly.asJson,
      "destructiveHint" -> destructive.asJson,
      "openWorldHint" -> openWorld.asJson
    )
  )
}

object McpTool {
  type Result = IO[Either[String, String]]
}

/** 入力スキーマと引数の読み取りの部品（各サービスの McpRoutes で `import sbirds.mcp.McpSchema.*` して使う）. */
object McpSchema {

  def schema(required: List[String], props: (String, Json)*): Json =
    Json.obj("type" -> "object".asJson, "properties" -> Json.obj(props*), "required" -> required.asJson)
  def str(d: String): Json = Json.obj("type" -> "string".asJson, "description" -> d.asJson)
  def int(d: String): Json = Json.obj("type" -> "integer".asJson, "description" -> d.asJson)
  def bool(d: String): Json = Json.obj("type" -> "boolean".asJson, "description" -> d.asJson)
  def strs(d: String): Json =
    Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson), "description" -> d.asJson)
  def obj(d: String): Json = Json.obj("type" -> "object".asJson, "description" -> d.asJson)

  /** 必須の文字列の引数（前後の空白を除く. 空なら Left）. */
  def need(a: ACursor, k: String): Either[String, String] =
    a.get[String](k).toOption.map(_.trim).filter(_.nonEmpty).toRight(s"$k を指定してください")

  /** JSON の文字列の項目（なければ空文字）. */
  def s(j: Json, k: String): String = j.hcursor.get[String](k).getOrElse("")

  extension [A](e: Either[String, A]) {

    /** 引数の確認が通ったときだけ f を動かす. */
    def withArgs(f: A => McpTool.Result): McpTool.Result = e.fold(err => IO.pure(Left(err)), f)
  }

  extension (r: IO[Either[String, Json]]) {

    /** REST API の応答の JSON を、そのまま文にする. */
    def asText: McpTool.Result = r.map(_.map(_.spaces2))
  }
}

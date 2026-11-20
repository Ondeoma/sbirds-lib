package sbirds.mcp

import cats.effect.IO
import cats.effect.syntax.all.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{Allow, Authorization}
import sbirds.http.BodyLimit
import sbirds.observe.Log
import sbirds.shared.{AppError, ErrorCode, LibErrorCode}

/**
 * MCP（Model Context Protocol）のサーバーの枠. POST /mcp に JSON-RPC を送る（Streamable HTTP. 応答は JSON、SSE は使わない）.
 * 枠が受け持つこと: Bearer の確認の流れ・本文の解析・まとめての呼び出し（バッチ）の上限・initialize / ping / tools/list / tools/call などの振り分け・
 * JSON-RPC のエラーの形・参照のみのキーの確認・ツールの例外をコード付きでログに出すこと（例外の中身は返さない）.
 * サービスが受け持つこと（Host）: キーからセッションを引く・ツールの一覧と中身・説明の文.
 * {{{
 * final class McpRoutes(api: HttpRoutes[IO], sessions: SessionAuth) {
 *   private val host = new McpServer.Host[AuthSession, McpCaller] {
 *     val settings = McpServer.Settings("stui", toolFailed = ErrorCode.McpToolFailed)
 *     val tools = List(McpTool[McpCaller]("get_me", "自分の情報。", schema(Nil), readOnly = true)((call, _) => …))
 *     def session(req: Request[IO], auth: Authorization) = sessions.fromRequest(Request[IO](Method.GET, uri"/mcp").putHeaders(auth))
 *     def unauthorized = Errors.respond(ErrorCode.McpApiKeyRequired)
 *     def context(req: Request[IO], auth: Authorization, s: AuthSession) = new McpCaller(api, auth, List("X-Stui-Mcp" -> "1"))
 *     def canWrite(s: AuthSession) = s.canWrite
 *     def instructions(s: AuthSession) = "stui（チームのチャット）の…"
 *   }
 *   val routes: HttpRoutes[IO] = McpServer.routes(host) <+> HttpRoutes.of[IO] { …サービス固有のパス（/api/endpoints など）… }
 * }
 * }}}
 */
object McpServer {

  /** 対応するプロトコルの版（新しい順）. クライアントが求めた版に対応していればそれを、なければ最新を返す. */
  val Versions: List[String] = List("2025-06-18", "2025-03-26", "2024-11-05")

  /**
   * @param name            serverInfo の名前と、401 の WWW-Authenticate の realm.
   * @param toolFailed      ツールが例外で失敗したときにログに出すコード（各サービスの ErrorCode.McpToolFailed. 9xxx）.
   * @param version         serverInfo の版.
   * @param maxBatch        まとめての呼び出しの上限（超えたら 400・-32600. 一部だけ処理することはしない）.
   * @param batchTooLarge   上限を超えたときの文.
   * @param maxBodyBytes    本文の上限（None なら全体の BodyLimit に任せる）. 超えた本文は読めない本文と同じ 400・-32700.
   * @param readOnlyDenied  参照のみのキーで変更するツールを呼んだときの文（Host.denied の既定が使う）.
   * @param hideDenied      tools/list に、そのキーで使えないツールを載せない.
   * @param failure         ツールが例外で失敗したときに返す文（例外の中身は入れない）.
   * @param parallelReads   バッチが変更するツールを含まないとき、並行して処理する数（1 なら順に）.
   */
  final case class Settings(name: String,
                            toolFailed: ErrorCode,
                            version: String = "0.1.0",
                            maxBatch: Int = 20,
                            batchTooLarge: Int => String = n => s"一度に送れる要求は $n 件までです",
                            maxBodyBytes: Option[Long] = None,
                            readOnlyDenied: String = "参照のみの API トークンでは変更できません",
                            hideDenied: Boolean = true,
                            failure: ErrorCode => String = c => s"内部エラー（${c.code}）",
                            parallelReads: Int = 1,
                            versions: List[String] = Versions)

  /** サービスごとの部分. S はキーの持ち主（AuthSession・Principal）、C はツールに渡す文脈. */
  trait Host[S, C] {
    def settings: Settings
    def tools: List[McpTool[C]]

    /** Bearer のキーの持ち主（無効なら None → unauthorized）. req は外から届いた要求（転送のヘッダーを見るサービス用）. */
    def session(req: Request[IO], auth: Authorization): IO[Option[S]]

    /** キーがない・無効なときの応答（401 の本文. WWW-Authenticate は枠が付ける）. */
    def unauthorized: IO[Response[IO]]

    /** キーは有効だが MCP は使わせないとき（取り込み専用のキーなど）の応答. */
    def refuse(s: S): Option[IO[Response[IO]]] = None

    /** ツールに渡す文脈. */
    def context(req: Request[IO], auth: Authorization, s: S): C

    /** 変更するツールを使えるか（参照のみのキーなら false）. */
    def canWrite(s: S): Boolean

    /** そのツールを使えないなら、その理由（isError の結果として返す）. 既定は「参照のみのキーで変更するツール」. */
    def denied(s: S, t: McpTool[C]): Option[String] =
      Option.when(!canWrite(s) && !t.readOnly)(settings.readOnlyDenied)

    /** initialize で返す説明の文. */
    def instructions(s: S): String
  }

  // ------------------------------------------------------------------ JSON-RPC の形

  def ok(id: Json, result: Json): Json = Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id, "result" -> result)

  def rpcFail(id: Json, code: Int, msg: String): Json =
    Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id, "error" -> Json.obj("code" -> code.asJson, "message" -> msg.asJson))

  def toolResult(r: Either[String, String]): Json =
    Json.obj(
      "content" -> Json.arr(Json.obj("type" -> "text".asJson, "text" -> r.merge.asJson)),
      "isError" -> r.isLeft.asJson
    )

  val ParseError = -32700
  val InvalidRequest = -32600
  val MethodNotFound = -32601
  val InvalidParams = -32602

  // ------------------------------------------------------------------ 入口

  private object dsl extends Http4sDsl[IO]
  import dsl.*

  /** POST /mcp（JSON-RPC）と、GET・DELETE /mcp（405）. */
  def routes[S, C](host: Host[S, C]): HttpRoutes[IO] = {
    val server = new McpServer(host)
    HttpRoutes.of[IO] {
      case req @ POST -> Root / "mcp" => server.post(req)
      case (GET | DELETE) -> Root / "mcp" => MethodNotAllowed(Allow(Method.POST))
    }
  }
}

final class McpServer[S, C](host: McpServer.Host[S, C]) {
  import McpServer.*
  import McpServer.dsl.*

  private val settings = host.settings
  private val byName = host.tools.map(t => t.name -> t).toMap

  private def unauthorized: IO[Response[IO]] =
    host.unauthorized.map(_.putHeaders(headers.`WWW-Authenticate`(Challenge("Bearer", settings.name))))

  def post(req: Request[IO]): IO[Response[IO]] =
    req.headers.get[Authorization] match {
      case Some(auth @ Authorization(Credentials.Token(AuthScheme.Bearer, _))) =>
        host.session(req, auth).flatMap {
          case None => unauthorized
          case Some(s) => host.refuse(s).getOrElse(read(req, auth, s))
        }
      case _ => unauthorized
    }

  private def read(req0: Request[IO], auth: Authorization, s: S): IO[Response[IO]] = {
    val req = settings.maxBodyBytes.fold(req0)(max => BodyLimit.request(req0, max)(AppError(LibErrorCode.TooLarge)))
    val ctx = host.context(req0, auth, s)
    req.as[Json].attempt.flatMap {
      case Left(_) => BadRequest(rpcFail(Json.Null, ParseError, "parse error"))
      case Right(j) =>
        j.asArray match {
          case Some(batch) if batch.size > settings.maxBatch =>
            BadRequest(rpcFail(Json.Null, InvalidRequest, settings.batchTooLarge(settings.maxBatch)))
          case Some(batch) =>
            // 変更するツールを含むときは順序に意味があり得るので 1 件ずつ. 含まなければ parallelReads まで並行
            val each = (m: Json) => handle(ctx, s, m)
            val all =
              if settings.parallelReads > 1 && batch.forall(!changes(_)) then
                batch.toList.parTraverseN(settings.parallelReads)(each)
              else batch.toList.traverse(each)
            all.map(_.flatten).flatMap(rs => if rs.isEmpty then Accepted() else Ok(rs.asJson))
          case None => handle(ctx, s, j).flatMap(_.fold(Accepted())(Ok(_)))
        }
    }
  }

  /** 変更するツールの呼び出しか. */
  private def changes(msg: Json): Boolean = {
    val c = msg.hcursor
    c.get[String]("method").contains("tools/call") &&
    c.downField("params").get[String]("name").toOption.flatMap(byName.get).exists(!_.readOnly)
  }

  /** 1 件の要求を処理する. 通知（id なし. notifications/initialized など）には返さない（None）. */
  private def handle(ctx: C, s: S, msg: Json): IO[Option[Json]] = {
    val c = msg.hcursor
    val params = c.downField("params")
    c.downField("id").focus match {
      case None => IO.pure(None)
      case Some(id) =>
        c.get[String]("method").getOrElse("") match {
          case "initialize" =>
            val asked = params.get[String]("protocolVersion").toOption
            IO.pure(Some(ok(
              id,
              Json.obj(
                "protocolVersion" -> asked.filter(settings.versions.contains).getOrElse(settings.versions.head).asJson,
                "capabilities" -> Json.obj("tools" -> Json.obj("listChanged" -> false.asJson)),
                "serverInfo" -> Json.obj("name" -> settings.name.asJson, "version" -> settings.version.asJson),
                "instructions" -> host.instructions(s).asJson
              )
            )))
          case "ping" => IO.pure(Some(ok(id, Json.obj())))
          case "tools/list" =>
            val listed = if settings.hideDenied then host.tools.filter(host.denied(s, _).isEmpty) else host.tools
            IO.pure(Some(ok(id, Json.obj("tools" -> listed.map(_.json).asJson))))
          case "tools/call" =>
            val name = params.get[String]("name").getOrElse("")
            byName.get(name) match {
              case None => IO.pure(Some(rpcFail(id, InvalidParams, s"unknown tool: $name")))
              case Some(t) =>
                host.denied(s, t) match {
                  case Some(why) => IO.pure(Some(ok(id, toolResult(Left(why)))))
                  case None =>
                    val args = params.downField("arguments").focus.filter(_.isObject).getOrElse(Json.obj()).hcursor
                    // 例外の中身（SQL や内部のパスを含み得る）は返さず、コード付きでログに出す
                    IO.defer(t.run(ctx, args)).handleErrorWith(e =>
                      Log.report(settings.toolFailed, s"$name: $e", e).as(Left(settings.failure(settings.toolFailed)))
                    ).map(r => Some(ok(id, toolResult(r))))
                }
            }
          case "resources/list" => IO.pure(Some(ok(id, Json.obj("resources" -> Json.arr()))))
          case "prompts/list" => IO.pure(Some(ok(id, Json.obj("prompts" -> Json.arr()))))
          case other => IO.pure(Some(rpcFail(id, MethodNotFound, s"method not found: $other")))
        }
    }
  }
}

package sbirds.mcp

import cats.effect.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.typelevel.ci.CIString
import sbirds.mcp.McpSchema.*
import sbirds.shared.LibErrorCode

class McpServerSpec extends CatsEffectSuite {

  private final case class Who(name: String, write: Boolean)

  /** 内部から呼ばれる REST API（Authorization と足したヘッダーをそのまま返す）. */
  private val api: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "me" =>
      Ok(Json.obj(
        "auth" -> req.headers.get[Authorization].map(_.credentials.renderString).asJson,
        "mcp" -> req.headers.get(CIString("X-Test-Mcp")).map(_.head.value).asJson
      ))
    case req @ POST -> Root / "api" / "echo" => req.as[Json].flatMap(Ok(_))
    case GET -> Root / "api" / "forbidden" => Forbidden(Json.obj("error" -> "権限がありません".asJson))
    case GET -> Root / "api" / "empty" => NoContent()
  }

  private def host(cfg: McpServer.Settings => McpServer.Settings = identity): McpServer.Host[Who, McpCaller] =
    new McpServer.Host[Who, McpCaller] {
      val settings = cfg(McpServer.Settings("test", toolFailed = LibErrorCode.Unexpected, version = "1.2.3"))
      val tools = List(
        McpTool[McpCaller]("get_me", "自分の情報。", schema(Nil), readOnly = true)((call, _) =>
          call(Method.GET, McpCaller.path("api", "me")).asText
        ),
        McpTool[McpCaller]("echo", "そのまま返す。", schema(List("text"), "text" -> str("文")), readOnly = false)((call, a) =>
          need(a, "text").withArgs(t =>
            call(Method.POST, McpCaller.path("api", "echo"), Some(Json.obj("text" -> t.asJson))).map(_.map(j =>
              s(j, "text")
            ))
          )
        ),
        McpTool[McpCaller]("boom", "例外。", schema(Nil), readOnly = true)((_, _) =>
          throw new RuntimeException("secret SQL detail")
        ),
        McpTool[McpCaller]("send", "送る。", schema(Nil), readOnly = false, destructive = true, openWorld = true)((_, _) =>
          IO.pure(Right("sent"))
        )
      )
      def session(req: Request[IO], auth: Authorization) = IO.pure(auth.credentials match {
        case Credentials.Token(_, "rw") => Some(Who("a", true))
        case Credentials.Token(_, "ro") => Some(Who("b", false))
        case Credentials.Token(_, "ingest") => Some(Who("ingest", false))
        case _ => None
      })
      def unauthorized =
        IO.pure(Response[IO](Status.Unauthorized).withEntity(Json.obj("error" -> "キーが必要です".asJson)))
      override def refuse(w: Who) = Option.when(w.name == "ingest")(Forbidden("取り込み専用"))
      def context(req: Request[IO], auth: Authorization, w: Who) = new McpCaller(api, auth, List("X-Test-Mcp" -> "1"))
      def canWrite(w: Who) = w.write
      def instructions(w: Who) = s"テスト用（${w.name}）"
    }

  private def app(cfg: McpServer.Settings => McpServer.Settings = identity) = McpServer.routes(host(cfg)).orNotFound

  private def post(body: String, key: String = "rw"): Request[IO] =
    Request[IO](Method.POST, uri"/mcp").withEntity(body)(using EntityEncoder.stringEncoder)
      .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, key)))

  private def rpc(method: String, params: Json = Json.obj(), id: Int = 1): String =
    Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id.asJson, "method" -> method.asJson, "params" -> params).noSpaces

  private def call(name: String, args: Json = Json.obj(), id: Int = 1): String =
    rpc("tools/call", Json.obj("name" -> name.asJson, "arguments" -> args), id)

  private def json(r: Response[IO]): IO[Json] = r.bodyText.compile.string.map(s => parse(s).getOrElse(Json.Null))

  private def text(j: Json): String =
    j.hcursor.downField("result").downField("content").downN(0).get[String]("text").getOrElse("")
  private def isError(j: Json): Boolean = j.hcursor.downField("result").get[Boolean]("isError").getOrElse(false)

  test("キーがない・無効なら 401（WWW-Authenticate: Bearer）. GET・DELETE は 405") {
    for {
      r1 <- app().run(post(rpc("ping")).removeHeader[Authorization])
      r2 <- app().run(post(rpc("ping"), "bad"))
      r3 <- app().run(Request[IO](Method.GET, uri"/mcp"))
      r4 <- app().run(Request[IO](Method.DELETE, uri"/mcp"))
      r5 <- app().run(post(rpc("ping"), "ingest"))
    } yield {
      assertEquals(r1.status, Status.Unauthorized)
      assertEquals(r2.status, Status.Unauthorized)
      assert(r1.headers.get(CIString("WWW-Authenticate")).exists(_.head.value.contains("Bearer realm=\"test\"")))
      assertEquals(r3.status, Status.MethodNotAllowed)
      assertEquals(r4.status, Status.MethodNotAllowed)
      assertEquals(r3.headers.get(CIString("Allow")).map(_.head.value), Some("POST"))
      assertEquals(r5.status, Status.Forbidden)
    }
  }

  test("initialize: 求められた版に対応していればそれを、なければ最新を返す") {
    for {
      j1 <- app().run(post(rpc("initialize", Json.obj("protocolVersion" -> "2024-11-05".asJson)))).flatMap(json)
      j2 <- app().run(post(rpc("initialize", Json.obj("protocolVersion" -> "1999-01-01".asJson)))).flatMap(json)
    } yield {
      val r = j1.hcursor.downField("result")
      assertEquals(r.get[String]("protocolVersion"), Right("2024-11-05"))
      assertEquals(r.downField("serverInfo").get[String]("name"), Right("test"))
      assertEquals(r.downField("serverInfo").get[String]("version"), Right("1.2.3"))
      assertEquals(r.get[String]("instructions"), Right("テスト用（a）"))
      assertEquals(r.downField("capabilities").downField("tools").get[Boolean]("listChanged"), Right(false))
      assertEquals(j1.hcursor.get[Int]("id"), Right(1))
      assertEquals(j2.hcursor.downField("result").get[String]("protocolVersion"), Right(McpServer.Versions.head))
    }
  }

  test("ping・resources/list・prompts/list・知らないメソッド・通知") {
    for {
      p <- app().run(post(rpc("ping"))).flatMap(json)
      rs <- app().run(post(rpc("resources/list"))).flatMap(json)
      ps <- app().run(post(rpc("prompts/list"))).flatMap(json)
      un <- app().run(post(rpc("nope")))
      unj <- json(un)
      note <- app().run(post("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
      noteBody <- note.bodyText.compile.string
    } yield {
      assertEquals(p.hcursor.get[Json]("result"), Right(Json.obj()))
      assertEquals(rs.hcursor.downField("result").get[List[Json]]("resources"), Right(Nil))
      assertEquals(ps.hcursor.downField("result").get[List[Json]]("prompts"), Right(Nil))
      assertEquals(un.status, Status.Ok)
      assertEquals(unj.hcursor.downField("error").get[Int]("code"), Right(-32601))
      assertEquals(note.status, Status.Accepted)
      assertEquals(noteBody, "")
    }
  }

  test("tools/list: 定義と annotations. 参照のみのキーには変更するツールを載せない（hideDenied = false なら載せる）") {
    def names(j: Json) = j.hcursor.downField("result").get[List[Json]]("tools").getOrElse(Nil).map(s(_, "name"))
    for {
      rw <- app().run(post(rpc("tools/list"))).flatMap(json)
      ro <- app().run(post(rpc("tools/list"), "ro")).flatMap(json)
      roAll <- app(_.copy(hideDenied = false)).run(post(rpc("tools/list"), "ro")).flatMap(json)
    } yield {
      assertEquals(names(rw), List("get_me", "echo", "boom", "send"))
      assertEquals(names(ro), List("get_me", "boom"))
      assertEquals(names(roAll), List("get_me", "echo", "boom", "send"))
      val send = rw.hcursor.downField("result").get[List[Json]]("tools").getOrElse(Nil).last.hcursor
      assertEquals(send.downField("inputSchema").get[String]("type"), Right("object"))
      val a = send.downField("annotations")
      assertEquals(
        (a.get[Boolean]("readOnlyHint"), a.get[Boolean]("destructiveHint"), a.get[Boolean]("openWorldHint")),
        (Right(false), Right(true), Right(true))
      )
    }
  }

  test("tools/call: REST API を呼び出し元のキーと足したヘッダーで内部から呼ぶ") {
    app().run(post(call("get_me"))).flatMap(json).map { j =>
      assert(!isError(j))
      val me = parse(text(j)).getOrElse(Json.Null)
      assertEquals(s(me, "auth"), "Bearer rw")
      assertEquals(s(me, "mcp"), "1")
    }
  }

  test("tools/call: 引数・知らないツール・参照のみのキー") {
    for {
      okJ <- app().run(post(call("echo", Json.obj("text" -> " hi ".asJson)))).flatMap(json)
      missing <- app().run(post(call("echo"))).flatMap(json)
      notObject <- app().run(post(rpc("tools/call", Json.obj("name" -> "echo".asJson, "arguments" -> "x".asJson))))
        .flatMap(json)
      unknown <- app().run(post(call("nope"))).flatMap(json)
      ro <- app().run(post(call("echo", Json.obj("text" -> "x".asJson)), "ro")).flatMap(json)
    } yield {
      assertEquals((isError(okJ), text(okJ)), (false, "hi"))
      assertEquals((isError(missing), text(missing)), (true, "text を指定してください"))
      assert(isError(notObject))
      assertEquals(unknown.hcursor.downField("error").get[Int]("code"), Right(-32602))
      assertEquals(unknown.hcursor.downField("error").get[String]("message"), Right("unknown tool: nope"))
      assertEquals((isError(ro), text(ro)), (true, "参照のみの API トークンでは変更できません"))
    }
  }

  test("tools/call: 例外の中身は返さず、コードだけを返す") {
    app().run(post(call("boom"))).flatMap(json).map { j =>
      assert(isError(j))
      assertEquals(text(j), s"内部エラー（${LibErrorCode.Unexpected.code}）")
      assert(!j.noSpaces.contains("secret"))
    }
  }

  test("読めない本文は 400（-32700）. 本文の上限を超えても同じ") {
    for {
      r1 <- app().run(post("{not json"))
      j1 <- json(r1)
      r2 <- app(_.copy(maxBodyBytes = Some(64))).run(post(call("echo", Json.obj("text" -> ("x" * 200).asJson))))
      j2 <- json(r2)
      r3 <- app(_.copy(maxBodyBytes = Some(64))).run(post(rpc("ping")))
    } yield {
      assertEquals(r1.status, Status.BadRequest)
      assertEquals(j1.hcursor.downField("error").get[Int]("code"), Right(-32700))
      assertEquals(j1.hcursor.get[Json]("id"), Right(Json.Null))
      assertEquals(r2.status, Status.BadRequest)
      assertEquals(j2.hcursor.downField("error").get[Int]("code"), Right(-32700))
      assertEquals(r3.status, Status.Ok)
    }
  }

  test("バッチ: 順に処理して通知を除く. 上限を超えたら 400（-32600）. 通知だけなら 202") {
    val two = s"[${call("get_me", id = 1)},${rpc("ping", id = 2)}," +
      """{"jsonrpc":"2.0","method":"notifications/x"}]"""
    val many = List.tabulate(21)(i => rpc("ping", id = i)).mkString("[", ",", "]")
    for {
      r1 <- app().run(post(two))
      j1 <- json(r1)
      r2 <- app().run(post(many))
      j2 <- json(r2)
      r3 <- app(_.copy(maxBatch = 50, batchTooLarge = n => s"バッチは${n}件までです")).run(post(many))
      r4 <- app().run(post("""[{"jsonrpc":"2.0","method":"notifications/x"}]"""))
      r5 <- app(_.copy(parallelReads = 4)).run(post(two))
      j5 <- json(r5)
    } yield {
      assertEquals(j1.asArray.map(_.flatMap(_.hcursor.get[Int]("id").toOption)), Some(Vector(1, 2)))
      assertEquals(r2.status, Status.BadRequest)
      assertEquals(j2.hcursor.downField("error").get[Int]("code"), Right(-32600))
      assertEquals(j2.hcursor.downField("error").get[String]("message"), Right("一度に送れる要求は 20 件までです"))
      assertEquals(r3.status, Status.Ok)
      assertEquals(r4.status, Status.Accepted)
      assertEquals(j5.asArray.map(_.flatMap(_.hcursor.get[Int]("id").toOption)), Some(Vector(1, 2)))
    }
  }

  test("McpCaller: エラーの応答は「状態 文」、見つからないパスは 404、本文なしは null") {
    val c = new McpCaller(api, Authorization(Credentials.Token(AuthScheme.Bearer, "rw")))
    for {
      a <- c(Method.GET, McpCaller.path("api", "forbidden"))
      b <- c(Method.GET, McpCaller.path("api", "none"))
      e <- c(Method.GET, McpCaller.path("api", "empty"))
      d <- c.as[Map[String, String]](Method.POST, McpCaller.path("api", "echo"), Some(Json.obj("k" -> "v".asJson)))
    } yield {
      assertEquals(a, Left("403 権限がありません"))
      assertEquals(b, Left("404 見つかりません"))
      assertEquals(e, Right(Json.Null))
      assertEquals(d, Right(Map("k" -> "v")))
      assertEquals(McpCaller.path("api", "a b", "c/d").renderString, "/api/a%20b/c%2Fd")
    }
  }
}

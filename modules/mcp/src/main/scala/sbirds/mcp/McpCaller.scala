package sbirds.mcp

import cats.effect.IO
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization

import scala.concurrent.duration.FiniteDuration

/**
 * ツールから REST API を内部で呼ぶ（HTTP は通らず、api の Routes をそのまま動かす）.
 * 呼び出し元の Authorization（API キー）をそのまま付けるので、権限・スコープ・監査は REST API と同じになる.
 * 結果は Right が応答の JSON（本文がなければ null、JSON でなければ文字列）、Left が「404 見つかりません」「403 <error の文>」の形の文.
 *
 * @param headers 内部の呼び出しに足すヘッダー（"X-Stui-Mcp" -> "1" など. REST 側が MCP からの呼び出しを見分ける）.
 * @param timeout 終わらない応答（SSE など）でツールが返らなくならないよう区切る時間.
 * @param build   要求の組み立て（既定は JSON の本文. 文字列をそのまま本文にするサービスは差し替える）.
 */
final class McpCaller(api: HttpRoutes[IO],
                      val auth: Authorization,
                      headers: List[(String, String)] = Nil,
                      timeout: Option[FiniteDuration] = None,
                      build: (Method, Uri, Option[Json]) => Request[IO] = McpCaller.jsonRequest) {

  def apply(method: Method, uri: Uri, body: Option[Json] = None): IO[Either[String, Json]] = {
    val req = build(method, uri, body).putHeaders(auth).putHeaders(headers.map(Header.ToRaw.keyValuesToRaw)*)
    val run = api.run(req).value.flatMap {
      case None => IO.pure(Left("404 見つかりません"))
      case Some(r) =>
        r.bodyText.compile.string.map { s =>
          val j = if s.isEmpty then Json.Null else parse(s).getOrElse(Json.fromString(s))
          if r.status.isSuccess then Right(j)
          else Left(s"${r.status.code} ${j.hcursor.get[String]("error").getOrElse(r.status.reason)}")
        }
    }
    timeout.fold(run)(t => run.timeoutTo(t, IO.pure(Left("時間内に応答がありませんでした"))))
  }

  /** 応答を型に読む. */
  def as[A: Decoder](method: Method, uri: Uri, body: Option[Json] = None): IO[Either[String, A]] =
    apply(method, uri, body).map(_.flatMap(_.as[A].left.map(e => s"応答を読めません: ${e.getMessage}")))
}

object McpCaller {

  def jsonRequest(method: Method, uri: Uri, body: Option[Json]): Request[IO] = {
    val req = Request[IO](method, uri)
    body.fold(req)(req.withEntity(_))
  }

  /** パスの区切りごとにエンコードした Uri（/api/tasks/{id} など）. */
  def path(segments: String*): Uri =
    Uri(path = Uri.Path(segments.toVector.map(Uri.Path.Segment(_)), absolute = true))
}

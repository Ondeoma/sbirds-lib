package sbirds.http

import cats.effect.IO
import io.circe.Decoder
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{Authorization, Location}
import sbirds.shared.{AppError, ErrorCode}

import java.util.UUID

/** Cookie の組み立て. */
object Cookies {

  /** HttpOnly の Cookie（https なら Secure. 既定は SameSite=Lax・path=/）. 消すときは value = ""・maxAge = 0. */
  def httpOnly(name: String,
               value: String,
               maxAge: Long,
               secure: Boolean,
               path: String = "/",
               sameSite: SameSite = SameSite.Lax): ResponseCookie =
    ResponseCookie(
      name,
      value,
      maxAge = Some(maxAge),
      path = Some(path),
      httpOnly = true,
      secure = secure,
      sameSite = Some(sameSite)
    )

  /** 要求の Cookie の値. */
  def value(req: Request[IO], name: String): Option[String] = req.cookies.find(_.name == name).map(_.content)
}

/** 要求・応答の小さな部品（*Routes の外でも使えるもの）. */
object HttpParts {

  /** クエリのパラメータ（前後の空白を除き、空なら無いものとして扱う）. */
  def param(req: Request[IO], k: String): Option[String] = req.params.get(k).map(_.trim).filter(_.nonEmpty)

  /** `Authorization: Bearer <値>` の値（形の確認〈接頭辞など〉は各サービスで）. */
  def bearer(req: Request[IO]): Option[String] =
    req.headers.get[Authorization].collect { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) => t }

  /**
   * 302 の応答. noStore なら Cache-Control: no-store を付ける.
   * lenient なら、URL として読めない行き先を / にする（SafePath.of を通した % 入りのパスなど）. そうでなければ読めない行き先は例外.
   */
  def redirect(to: String,
               cookie: Option[ResponseCookie] = None,
               noStore: Boolean = false,
               lenient: Boolean = false): Response[IO] = {
    val uri = if lenient then Uri.fromString(to).getOrElse(Uri(path = Uri.Path.Root)) else Uri.unsafeFromString(to)
    val r0 = Response[IO](Status.Found).putHeaders(Location(uri))
    val r = if noStore then r0.putHeaders("Cache-Control" -> "no-store") else r0
    cookie.fold(r)(r.addCookie)
  }
}

/**
 * 各サービスの RouteSupport の共通部分（本文の読み取り・クエリ・パスの UUID・302）.
 * {{{
 * trait RouteSupport extends sbirds.http.RouteHelpers {
 *   protected def bodyInvalid = ErrorCode.RequestBodyInvalid
 *   …認証（authed など）はサービスごと…
 * }
 * }}}
 */
trait RouteHelpers extends Http4sDsl[IO] {

  /** 本文が読めないときのエラーコード. */
  protected def bodyInvalid: ErrorCode

  /**
   * 本文の読み取りの途中で投げられた AppError（BodyLimit の「大きすぎる」など）を、そのコードの応答にするか.
   * false なら、ほかの読み取りの失敗と同じく bodyInvalid にする.
   */
  protected def bodyKeepsAppError: Boolean = true

  /** 本文（JSON）を読む. 読めなければ bodyInvalid の応答（理由は 200 文字まで）. */
  protected def body[A: Decoder](req: Request[IO])(f: A => IO[Response[IO]]): IO[Response[IO]] =
    req.as[A].attempt.flatMap {
      case Left(e: AppError) if bodyKeepsAppError => Errors.respond(e)
      case Left(e) =>
        Errors.respond(AppError(bodyInvalid, s"リクエストの内容が正しくありません: ${String.valueOf(e.getMessage).take(200)}"))
      case Right(b) => f(b)
    }

  protected def param(req: Request[IO], k: String): Option[String] = HttpParts.param(req, k)

  /** 1 以上 max 以下の整数のパラメータ（無い・読めないときは default）. */
  protected def intParam(req: Request[IO], k: String, default: Int, max: Int): Int =
    param(req, k).flatMap(_.toIntOption).map(v => math.max(1, math.min(v, max))).getOrElse(default)

  /** パスの UUID（`case GET -> Root / "api" / "tasks" / UuidVar(id)`）. */
  protected object UuidVar {
    def unapply(s: String): Option[UUID] = scala.util.Try(UUID.fromString(s)).toOption
  }

  protected def redirect(to: String, cookie: Option[ResponseCookie] = None): IO[Response[IO]] =
    IO.pure(HttpParts.redirect(to, cookie))
}

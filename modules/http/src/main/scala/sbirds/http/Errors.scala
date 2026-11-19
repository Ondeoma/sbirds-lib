package sbirds.http

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import sbirds.observe.Log
import sbirds.shared.{AppError, ErrorCode, ErrorDto, LibErrorCode}

/**
 * エラーの応答とログ. すべてのエラーの応答はここを通り、コード付きでログに出る
 * （4xx は WARN・5xx は ERROR. 認証の確認のような、よくある 401 は INFO）.
 */
object Errors {

  /** エラーの応答を作り、ログに出す. */
  def respond(e: AppError, req: Option[Request[IO]] = None): IO[Response[IO]] =
    log(e, req).as(Response[IO](Status.fromInt(e.status).getOrElse(Status.InternalServerError)).withEntity(ErrorDto(
      e.message,
      Some(e.code.code)
    )))

  def respond(code: ErrorCode): IO[Response[IO]] = respond(AppError(code))

  def respond(code: ErrorCode, detail: String): IO[Response[IO]] = respond(AppError(code, detail))

  /** ログの1行: [SKEA-2001] 404 タスクが見つかりません: その場の文言 (GET /api/tasks/{id}) */
  def log(e: AppError, req: Option[Request[IO]] = None): IO[Unit] = {
    val where = req.fold("")(r => s" (${r.method} ${sbirds.observe.HttpTelemetry.route(r.pathInfo.renderString)})")
    Log.report(e.code, e.detail.filter(_ != e.code.message).getOrElse("") + where, e.cause.orNull)
  }

  /** 応答を返さない処理（バックグラウンドの処理など）のエラーをコード付きでログに出す. */
  def report(code: ErrorCode, detail: String, cause: Throwable | Null = null): IO[Unit] =
    Log.report(code, detail, cause)

  /**
   * 予期しない例外をコード付きの 500 にする（AppError ならそのコードの応答）. HttpApp の一番外側に付ける.
   * code はサービスの「予期しないエラー」のコード（省略すると SBL-9001）.
   */
  def handle(app: HttpApp[IO], code: ErrorCode = LibErrorCode.Unexpected): HttpApp[IO] = Kleisli { req =>
    app.run(req).handleErrorWith {
      case e: AppError => respond(e, Some(req))
      // 大きすぎる本文（MessageFailure の一種なので先に）
      case _: org.http4s.server.middleware.EntityLimiter.EntityTooLarge =>
        respond(AppError(LibErrorCode.TooLarge), Some(req))
      // 本文の読み取りの失敗: パーサーの文言は利用者に返さない（ログには cause として出る）
      case e: MessageFailure => respond(AppError(LibErrorCode.BadBody, None, Some(e)), Some(req))
      case e => respond(AppError(code, None, Some(e)), Some(req))
    }
  }

  /** HttpRoutes の版（見つからないパスはそのまま次へ）. */
  def handleRoutes(routes: HttpRoutes[IO], code: ErrorCode = LibErrorCode.Unexpected): HttpRoutes[IO] = Kleisli { req =>
    OptionT(routes.run(req).value.handleErrorWith {
      case e: AppError => respond(e, Some(req)).map(Some(_))
      case e => respond(AppError(code, None, Some(e)), Some(req)).map(Some(_))
    })
  }
}

package sbirds.http

import cats.effect.*
import org.http4s.*
import org.http4s.dsl.io.*

import scala.concurrent.duration.*

/** ロードバランサー用のヘルスチェック（GET /healthz）. */
object Health {

  /**
   * check が true なら 200（本文は ok）、false なら 503（プライマリでない）、失敗か 3 秒で終わらなければ 503（DB が使えない）.
   * check には sbirds.db.Database.isPrimary(xa)（プライマリかまで見る）か Database.ping(xa)（つながるかだけ）を渡す.
   * ok: 200 の本文（例: s"ok $nodeId"）. onError: 失敗をログに出す処理（コードは各サービスのもの）.
   * errorDetail: true なら 503 の本文に例外の文を足す（接続先が外に出るので、既定は足さない）.
   */
  def routes(check: IO[Boolean],
             ok: String = "ok",
             onError: Throwable => IO[Unit] = _ => IO.unit,
             errorDetail: Boolean = false): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "healthz" =>
      check.timeout(3.seconds).attempt.flatMap {
        case Right(true) => text(Status.Ok, ok)
        case Right(false) => text(Status.ServiceUnavailable, "database is read-only (not primary)")
        case Left(e) =>
          onError(e) *> text(
            Status.ServiceUnavailable,
            if errorDetail then s"database unavailable: ${e.getMessage}" else "database unavailable"
          )
      }
  }

  private def text(status: Status, body: String): IO[Response[IO]] =
    IO.pure(Response[IO](status).withEntity(body)(using EntityEncoder.stringEncoder))
}

package sbirds.http

import cats.data.OptionT
import cats.effect.*
import org.http4s.*
import sbirds.shared.{AppError, ErrorCode, LibErrorCode}

/**
 * リクエストの本文の大きさの上限（大きすぎる本文でメモリを使い切らせない）.
 * Content-Length が上限を超えていれば読まずに断り、Content-Length のない（chunked の）本文も数えて、超えたところで止める.
 */
object BodyLimit {

  /**
   * 本文が max を超えたら tooLarge を投げて止まる要求にする（1つのルートの中で、本文を読む前に掛ける）.
   * Content-Length が超えているときは、本文を読もうとした時点で tooLarge になる.
   */
  def request(req: Request[IO], max: Long)(tooLarge: => Throwable): Request[IO] =
    if req.contentLength.exists(_ > max) then req.withBodyStream(fs2.Stream.raiseError[IO](tooLarge))
    else
      req.withBodyStream(
        req.body.chunks.evalMapAccumulate(0L) { (n, c) =>
          val total = n + c.size
          if total > max then IO.raiseError(tooLarge) else IO.pure((total, c))
        }.flatMap((_, c) => fs2.Stream.chunk(c))
      )

  /**
   * すべての要求に掛ける. limit は要求ごとの上限（バイト. None なら掛けない＝そのルートに任せる）.
   * tooLarge は超えたときのエラー（サービスのコード. 上限ごとに文言を変えられる）.
   *  - Content-Length が超えている: 読まずに、そのエラーの応答（413 など）を返す.
   *  - 読んでいる途中で超えた: そのエラー（AppError）を投げる. ルートがそのまま外に出せば、ここでそのエラーの応答にする
   *    （応答を流し始めた後に超えたときは、外側の Errors.handle がそのエラーの応答にする）.
   */
  def apply(limit: Request[IO] => Option[Long], tooLarge: (Request[IO], Long) => AppError)(
    routes: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes[IO] { req =>
      limit(req) match {
        case None => routes(req)
        case Some(max) if req.contentLength.exists(_ > max) =>
          OptionT.liftF(Errors.respond(tooLarge(req, max), Some(req)))
        case Some(max) =>
          val e = tooLarge(req, max)
          OptionT(routes.run(request(req, max)(e)).value.recoverWith {
            case x: AppError if x eq e => Errors.respond(e, Some(req)).map(Some(_))
          })
      }
    }

  /** どの要求も同じ上限・同じコードのとき. */
  def apply(max: Long, code: ErrorCode = LibErrorCode.TooLarge)(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    apply(_ => Some(max), (_, _) => AppError(code))(routes)
}

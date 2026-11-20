package sbirds.http

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString
import sbirds.shared.LibErrorCode

import scala.concurrent.duration.*

/** RateLimit.standard: 全サービス共通の数え方（どの入口をどの枠で数えるか）と、サービスごとの追加・除外. */
class RateLimitSpec extends CatsEffectSuite {

  private given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.noop.NoOpFactory[IO]

  private val ok = HttpRoutes.of[IO] { case _ => Ok("ok") }
  private val cfg = RateLimitConfig(authPerMinute = 2, publicPerMinute = 3)

  private def from(ip: String, method: Method, path: Uri): Request[IO] =
    Request[IO](method, path).withAttribute(
      Request.Keys.ConnectionInfo,
      Request.Connection(
        SocketAddress(ipv4"10.0.0.2", port"8080"),
        SocketAddress(IpAddress.fromString(ip).get, port"50000"),
        false
      )
    )

  private def statuses(app: HttpApp[IO], n: Int, req: Request[IO]): IO[List[Status]] =
    List.fill(n)(app.run(req)).sequence.map(_.map(_.status))

  private val authLimited = List(Status.Ok, Status.Ok, Status.TooManyRequests)
  private val publicLimited = List(Status.Ok, Status.Ok, Status.Ok, Status.TooManyRequests)

  test("standard: /auth/… と /oauth/authorize は auth の枠、/oauth/token・/oauth/revoke・/api/public/… は public の枠") {
    val app = RateLimit.standard(ok, cfg, clock = IO.pure(10.minutes))().orNotFound
    for {
      login <- List.fill(3)(app.run(from("203.0.113.1", Method.GET, uri"/auth/login"))).sequence
      other <- app.run(from("203.0.113.2", Method.GET, uri"/auth/login"))
      // 認可の画面はログインと同じ枠（/auth/… と合わせて数える）
      mixed <- List(uri"/auth/callback", uri"/oauth/authorize", uri"/oauth/authorize")
        .traverse(u => app.run(from("203.0.113.3", Method.GET, u)).map(_.status))
      // トークンの発行・失効と公開の API は、合わせて public の枠（auth の枠とは別）
      token <- statuses(app, 2, from("203.0.113.3", Method.POST, uri"/oauth/token"))
      revoke <- statuses(app, 1, from("203.0.113.3", Method.POST, uri"/oauth/revoke"))
      pub <- statuses(app, 1, from("203.0.113.3", Method.GET, uri"/api/public/abc"))
      // それ以外は数えない
      rest <- List(uri"/api/me", uri"/mcp", uri"/oauth/other", uri"/api/oauth/grants", uri"/hooks/abc", uri"/")
        .flatTraverse(u => statuses(app, 10, from("203.0.113.1", Method.POST, u)))
    } yield {
      assertEquals(login.map(_.status), authLimited)
      assertEquals(login.last.headers.get(CIString("Retry-After")).map(_.head.value), Some("60"))
      assertEquals(other.status, Status.Ok) // 別の IP は別に数える
      assertEquals(mixed, authLimited)
      assertEquals(token ++ revoke ++ pub, publicLimited)
      assert(rest.forall(_ == Status.Ok))
    }
  }

  test("standard: extra で、追加で数えるパス・対象から外すパス・独自の枠を決める（共通の数え方より先に見る）") {
    val app = RateLimit.standard(ok, cfg, LibErrorCode.TooManyRequests, IO.pure(10.minutes)) { b =>
      val submit = b.counter(1)
      {
        case RateLimit.Path("api" :: "public" :: _ :: "upload" :: Nil) => b.skip
        case RateLimit.Path("api" :: "rooms" :: _ :: "join" :: Nil) => b.public
        case req @ RateLimit.Path("api" :: "public" :: "forms" :: Nil) if req.method == Method.POST =>
          b.public ++ submit
      }
    }.orNotFound
    for {
      upload <- statuses(app, 10, from("203.0.113.1", Method.PUT, uri"/api/public/abc/upload"))
      join <- statuses(app, 4, from("203.0.113.1", Method.POST, uri"/api/rooms/abc/join"))
      // 独自の枠（1分に1回）と public の枠の両方で数える. GET は public の枠だけ
      submit <- statuses(app, 2, from("203.0.113.2", Method.POST, uri"/api/public/forms"))
      view <- statuses(app, 2, from("203.0.113.2", Method.GET, uri"/api/public/forms"))
      login <- statuses(app, 3, from("203.0.113.1", Method.GET, uri"/auth/login")) // extra に無いパスは共通の数え方
    } yield {
      assert(upload.forall(_ == Status.Ok))
      assertEquals(join, publicLimited)
      assertEquals(submit, List(Status.Ok, Status.TooManyRequests))
      assertEquals(view, List(Status.Ok, Status.TooManyRequests)) // public の枠の 3 回を、送信の 2 回と合わせて使い切る
      assertEquals(login, authLimited)
    }
  }

  test("standard: 0 なら制限しない. 窓（1分）が変われば数え直す") {
    var now = 10.minutes
    val off = RateLimit.standard(ok, RateLimitConfig(authPerMinute = 0, publicPerMinute = 0))().orNotFound
    val app = RateLimit.standard(ok, cfg, clock = IO(now))().orNotFound
    val login = from("203.0.113.1", Method.GET, uri"/auth/login")
    for {
      free <- statuses(off, 30, login)
      a <- statuses(app, 3, login)
      _ = now = now + 1.minute
      b <- statuses(app, 1, login)
    } yield {
      assert(free.forall(_ == Status.Ok))
      assertEquals(a ++ b, authLimited :+ Status.Ok)
    }
  }
}

package sbirds.http

import cats.data.OptionT
import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.{Cidr, IpAddress, Ipv6Address}
import org.http4s.*
import org.typelevel.ci.CIString
import sbirds.shared.{AppError, ErrorCode, LibErrorCode}

import scala.concurrent.duration.FiniteDuration

/**
 * 1分ごとの窓で数える（窓が変わったら数え直すので、覚える量はその1分に来た IP の数まで）.
 * limit は1分あたりの上限. 各ノードのメモリで数えるので、複数のノードで動かすと上限はノードの数だけ増える.
 */
final class RateCounter(
  limit: Int,
  clock: IO[FiniteDuration] = IO.realTime) {
  private val counts = Ref.unsafe[IO, (Long, Map[String, Int])]((0L, Map.empty))

  /** 1回数える. 上限を超えたら、次の窓までの秒数. */
  def hit(
    key: String
  ): IO[Option[Long]] = clock.flatMap { now =>
    val window = now.toMinutes
    counts.modify {
      (
        w,
        m0
      ) =>
        val m = if (w == window) m0 else Map.empty
        val n = m.getOrElse(key, 0) + 1
        ((window, m.updated(key, n)), Option.when(n > limit)(60 - now.toSeconds % 60))
    }
  }
}

/**
 * IP ごとの回数の制限. 各サービスは、要求ごとにどのカウンタで数えるか（rules）だけを決める.
 * trusted はリバースプロキシの IP か CIDR. そこから来た要求だけ X-Forwarded-For を信じる.
 */
object RateLimit {

  /** IP（10.0.0.1）か CIDR（10.0.0.0/8）. */
  def proxy(
    s: String
  ): Option[Cidr[IpAddress]] =
    Cidr.fromString(s).orElse(IpAddress.fromString(s).map(ip =>
      Cidr(ip, if (ip.fold(_ => true, _ => false)) 32 else 128)
    ))

  /** 1分あたり perMinute 回のカウンタ（0 以下なら数えない = None）. */
  def counter(
    perMinute: Int,
    clock: IO[FiniteDuration] = IO.realTime
  ): Option[RateCounter] = Option.when(perMinute > 0)(new RateCounter(perMinute, clock))

  /**
   * 要求を送ってきた人の IP（ログにも使う）. 直接つないできた相手から X-Forwarded-For を右（近いほう）へたどり、
   * 信じるプロキシでない最初の IP にする（クライアントが勝手に書いた左側の値は使わない）. 相手が分からなければ None.
   */
  def clientIp(
    req: Request[IO],
    trusted: List[Cidr[IpAddress]]
  ): Option[String] =
    req.remote.map(_.host).map { peer =>
      val forwarded = req.headers.get(CIString("X-Forwarded-For")).toList.flatMap(_.toList)
        .flatMap(_.value.split(',')).map(_.trim).filter(_.nonEmpty).reverse
      val hops = peer.toString :: forwarded
      hops.find(h => IpAddress.fromString(h).forall(ip => !trusted.exists(_.contains(ip)))).getOrElse(hops.last)
    }

  /** 数えるときの鍵（clientIp. IPv6 は /64 ごとにまとめる. 1人が大量のアドレスを使い分けられるため）. */
  def clientKey(
    req: Request[IO],
    trusted: List[Cidr[IpAddress]]
  ): Option[String] =
    clientIp(req, trusted).map { client =>
      IpAddress.fromString(client).map(_.collapseMappedV4).fold(client)(_.fold(
        _.toString,
        v6 => Ipv6Address.fromBytes(v6.toBytes.take(8) ++ Array.fill[Byte](8)(0)).fold(v6.toString)(_.toString + "/64")
      ))
    }

  /** rules が返したカウンタのどれかが上限を超えたら 429（Retry-After 付き）. */
  def apply(
    routes: HttpRoutes[IO],
    trusted: List[Cidr[IpAddress]],
    code: ErrorCode = LibErrorCode.TooManyRequests
  )(
    rules: Request[IO] => List[RateCounter]
  ): HttpRoutes[IO] = HttpRoutes[IO] { req =>
    val counters = rules(req)
    clientKey(req, trusted).filter(_ => counters.nonEmpty) match {
      case None => routes(req)
      case Some(key) =>
        OptionT.liftF(counters.traverse(_.hit(key)).map(_.flatten.maxOption)).flatMap {
          case None => routes(req)
          case Some(wait) =>
            OptionT.liftF(Errors.respond(AppError(code)).map(_.putHeaders("Retry-After" -> wait.toString)))
        }
    }
  }
}

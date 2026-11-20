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
final class RateCounter(limit: Int, clock: IO[FiniteDuration] = IO.realTime) {
  private val counts = Ref.unsafe[IO, (Long, Map[String, Int])]((0L, Map.empty))

  /** 1回数える. 上限を超えたら、次の窓までの秒数. */
  def hit(key: String): IO[Option[Long]] = clock.flatMap { now =>
    val window = now.toMinutes
    counts.modify {
      (w, m0) =>
        val m = if (w == window) m0 else Map.empty
        val n = m.getOrElse(key, 0) + 1
        ((window, m.updated(key, n)), Option.when(n > limit)(60 - now.toSeconds % 60))
    }
  }
}

/**
 * 回数の制限の設定（1分あたり. 0 なら制限しない）. ログインしなくても使える入口だけに掛ける.
 * auth はログイン（/auth/…・/oauth/authorize）、public は公開の入口（/oauth/token・/oauth/revoke・/api/public/…）.
 * どのパスをどちらで数えるかは RateLimit.standard で全サービス共通に決めている.
 * trustedProxies はリバースプロキシの IP か CIDR. そこから来た要求だけ X-Forwarded-For を信じる.
 */
final case class RateLimitConfig(authPerMinute: Int = 20,
                                 publicPerMinute: Int = 120,
                                 trustedProxies: List[Cidr[IpAddress]] = Nil)

object RateLimitConfig {

  /** 環境変数 RATE_LIMIT_AUTH（既定 20）・RATE_LIMIT_PUBLIC（既定 120）・TRUSTED_PROXIES（カンマか空白で区切った IP か CIDR）から読む. */
  def fromEnv(env: Env = Env): Either[String, RateLimitConfig] =
    RateLimit.trustedProxies(env).map(proxies =>
      RateLimitConfig(env.int("RATE_LIMIT_AUTH").getOrElse(20), env.int("RATE_LIMIT_PUBLIC").getOrElse(120), proxies)
    )
}

/**
 * IP ごとの回数の制限. 各サービスは standard（全サービス共通の数え方）を使い、追加で数えるパスと対象から外すパスだけを渡す.
 * apply・keyed は、数え方が別のサービス（sruru）用に、要求ごとにどのカウンタで数えるか（rules）を全部渡す形.
 * trusted はリバースプロキシの IP か CIDR. そこから来た要求だけ X-Forwarded-For を信じる.
 */
object RateLimit {

  /** IP（10.0.0.1）か CIDR（10.0.0.0/8）. */
  def proxy(s: String): Option[Cidr[IpAddress]] =
    Cidr.fromString(s).orElse(IpAddress.fromString(s).map(ip =>
      Cidr(ip, if (ip.fold(_ => true, _ => false)) 32 else 128)
    ))

  /** 環境変数（既定は TRUSTED_PROXIES）の、信じるプロキシの一覧. IP でも CIDR でもない値があれば Left（設定の誤りの文言）. */
  def trustedProxies(env: Env = Env, name: String = "TRUSTED_PROXIES"): Either[String, List[Cidr[IpAddress]]] =
    env.list(name).traverse(s => proxy(s).toRight(s"$name の「$s」は IP か CIDR で書いてください"))

  /** 1分あたり perMinute 回のカウンタ（0 以下なら数えない = None）. */
  def counter(perMinute: Int, clock: IO[FiniteDuration] = IO.realTime): Option[RateCounter] =
    Option.when(perMinute > 0)(new RateCounter(perMinute, clock))

  /**
   * 要求を送ってきた人の IP（ログにも使う）. 直接つないできた相手から X-Forwarded-For を右（近いほう）へたどり、
   * 信じるプロキシでない最初の IP にする（クライアントが勝手に書いた左側の値は使わない）. 相手が分からなければ None.
   */
  def clientIp(req: Request[IO], trusted: List[Cidr[IpAddress]]): Option[String] =
    req.remote.map(_.host).map { peer =>
      val forwarded = req.headers.get(CIString("X-Forwarded-For")).toList.flatMap(_.toList)
        .flatMap(_.value.split(',')).map(_.trim).filter(_.nonEmpty).reverse
      val hops = peer.toString :: forwarded
      hops.find(h => IpAddress.fromString(h).forall(ip => !trusted.exists(_.contains(ip)))).getOrElse(hops.last)
    }

  /** 数えるときの鍵（clientIp. IPv6 は /64 ごとにまとめる. 1人が大量のアドレスを使い分けられるため）. */
  def clientKey(req: Request[IO], trusted: List[Cidr[IpAddress]]): Option[String] =
    clientIp(req, trusted).map(keyOf)

  /** IP の文字列から、数えるときの鍵を作る（IPv6 は /64 ごとにまとめる. IP として読めなければそのまま）. */
  def keyOf(client: String): String =
    IpAddress.fromString(client).map(_.collapseMappedV4).fold(client)(_.fold(
      _.toString,
      v6 => Ipv6Address.fromBytes(v6.toBytes.take(8) ++ Array.fill[Byte](8)(0)).fold(v6.toString)(_.toString + "/64")
    ))

  /** standard に渡す extra から使う枠. auth・public は設定の枠（0 なら空）、skip は「数えない」. */
  final class Buckets(cfg: RateLimitConfig, clock: IO[FiniteDuration]) {
    val auth: List[RateCounter] = RateLimit.counter(cfg.authPerMinute, clock).toList
    val public: List[RateCounter] = RateLimit.counter(cfg.publicPerMinute, clock).toList
    val skip: List[RateCounter] = Nil

    /** サービス独自の枠（同じ時計で数える. 0 以下なら空）. public ++ counter(…) のように重ねて使う. */
    def counter(perMinute: Int): List[RateCounter] = RateLimit.counter(perMinute, clock).toList
  }

  /** パスの区切りごとの文字列（デコード済み）で照合する: case RateLimit.Path("api" :: "rooms" :: _ :: "join" :: Nil) => … */
  object Path {
    def unapply(req: Request[IO]): Some[List[String]] = Some(req.pathInfo.segments.map(_.decoded()).toList)
  }

  /**
   * 全サービス共通の数え方. ログインしなくても届く入口だけを数える:
   *  - auth（RATE_LIMIT_AUTH）: /auth/… と /oauth/authorize.
   *  - public（RATE_LIMIT_PUBLIC）: /oauth/token・/oauth/revoke（連携するサービスのサーバーが同じ IP から利用者の数だけ呼ぶので緩い枠）と /api/public/….
   *  - それ以外は数えない.
   * extra はサービスごとの違い（先に見る）: 追加で数えるパスは枠（b.auth・b.public・b.counter(…)）を、対象から外すパスは b.skip を返す.
   */
  def standard(routes: HttpRoutes[IO],
               cfg: RateLimitConfig,
               code: ErrorCode = LibErrorCode.TooManyRequests,
               clock: IO[FiniteDuration] = IO.realTime)(
    extra: Buckets => PartialFunction[Request[IO], List[RateCounter]] = _ => PartialFunction.empty): HttpRoutes[IO] = {
    val b = new Buckets(cfg, clock)
    val common: Request[IO] => List[RateCounter] = {
      case Path("auth" :: _) | Path("oauth" :: "authorize" :: _) => b.auth
      case Path("oauth" :: ("token" | "revoke") :: _) | Path("api" :: "public" :: _) => b.public
      case _ => b.skip
    }
    val own = extra(b) // 独自の枠を中で作るので、1回だけ呼ぶ
    apply(routes, cfg.trustedProxies, code)(own.applyOrElse(_, common))
  }

  /** rules が返したカウンタのどれかが上限を超えたら 429（Retry-After 付き）. */
  def apply(routes: HttpRoutes[IO],
            trusted: List[Cidr[IpAddress]],
            code: ErrorCode = LibErrorCode.TooManyRequests)(rules: Request[IO] => List[RateCounter]): HttpRoutes[IO] =
    keyed(routes, code)(req => IO.pure(clientKey(req, trusted)))(rules)

  /**
   * 鍵の決め方を渡す形（信じるプロキシを設定の画面で変えるサービス用. 鍵は keyOf で作る）.
   * 鍵は、数えるカウンタがある要求でだけ求める. 鍵が None なら数えない.
   */
  def keyed(routes: HttpRoutes[IO], code: ErrorCode = LibErrorCode.TooManyRequests)(
    key: Request[IO] => IO[Option[String]])(rules: Request[IO] => List[RateCounter]): HttpRoutes[IO] =
    HttpRoutes[IO] { req =>
      val counters = rules(req)
      if (counters.isEmpty) {
        routes(req)
      } else {
        OptionT.liftF(key(req)).flatMap {
          case None => routes(req)
          case Some(k) =>
            OptionT.liftF(counters.traverse(_.hit(k)).map(_.flatten.maxOption)).flatMap {
              case None => routes(req)
              case Some(wait) =>
                OptionT.liftF(Errors.respond(AppError(code)).map(_.putHeaders("Retry-After" -> wait.toString)))
            }
        }
      }
    }
}

package sbirds.net

import cats.effect.*
import com.comcast.ip4s.{Cidr, IpAddress}
import fs2.io.net.Network
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

import java.net.InetAddress
import scala.concurrent.duration.*

/**
 * 利用者が決めた宛先（Webhook・購読の URL・リンクのカードなど）へサーバーからつなぐ前の確認（SSRF 対策）.
 * 名前を引いた結果のすべてのアドレスを NetAddress の判定表で確かめる（1つでも許されないものがあれば断る）.
 * 「内部のアドレスを許す」設定の環境変数は各サービスが読み、Policy にして渡す.
 */
object NetGuard {

  /** どこまで許すか. */
  enum Allow {

    /** 外のアドレスだけ. */
    case PublicOnly

    /** 社内のネットワーク（NetAddress.Kind.Private）も許す. loopback・link-local（メタデータ）などは許さない. */
    case Private

    /** どこでも許す（確かめない. 開発環境など）. */
    case All
  }

  /**
   * allow に加えて、hosts（小文字のホスト名）は名前を引かずに許し、cidrs（アドレスの範囲）に入るアドレスも許す
   * （内部のアドレスのうち、決めた相手だけ許す「許可リスト」）.
   */
  final case class Policy(allow: Allow = Allow.PublicOnly,
                          hosts: Set[String] = Set.empty,
                          cidrs: List[Cidr[IpAddress]] = Nil)

  object Policy {
    val publicOnly: Policy = Policy()
    val allowAll: Policy = Policy(Allow.All)

    /**
     * 許可リスト（環境変数の一覧など）から作る. 項目は CIDR（10.1.0.0/16・fd00::/8）・IP アドレス（1つだけの範囲）・ホスト名（完全一致）.
     * 読めない項目があれば Left（その項目を示す文言）.
     */
    def allowList(entries: List[String], allow: Allow = Allow.PublicOnly): Either[String, Policy] =
      entries.map(_.trim).filter(_.nonEmpty).foldLeft[Either[String, Policy]](Right(Policy(allow))) { (acc, e) =>
        acc.flatMap { p =>
          val t = bare(e)
          if t.contains("/") then
            Cidr.fromString(t).map(c => p.copy(cidrs = p.cidrs :+ c)).toRight(s"CIDR として読めません: $e")
          else
            IpAddress.fromString(t) match {
              case Some(ip) =>
                Right(p.copy(cidrs = p.cidrs :+ Cidr(ip, if ip.fold(_ => true, _ => false) then 32 else 128)))
              case None if hostRe.matches(t) => Right(p.copy(hosts = p.hosts + t))
              case None => Left(s"ホスト名・IP アドレス・CIDR として読めません: $e")
            }
        }
      }

    private val hostRe = "[a-z0-9_]([a-z0-9._-]{0,251}[a-z0-9])?".r

    /** 「内部のアドレスも許す」設定が true ならどこでも、false なら外だけ. */
    def of(allowPrivate: Boolean): Policy = if allowPrivate then allowAll else publicOnly
  }

  /** 断った理由（文言は各サービスで作ってよい. message は既定の文言）. */
  enum Rejected(val message: String) extends Exception(message) {
    case InvalidUrl extends Rejected("URL が正しくありません")
    case BadScheme extends Rejected("URL は http:// か https:// で始まるものにしてください")
    case Unresolvable(host: String) extends Rejected(s"ホスト $host を名前解決できません")
    case Internal(host: String) extends Rejected(s"内部のアドレス（$host）にはつなげません")
  }

  /** そのアドレスへつないでよいか. */
  def allowed(a: InetAddress, allow: Allow): Boolean = allow match {
    case Allow.All => true
    case Allow.Private => NetAddress.kind(a) != NetAddress.Kind.Local
    case Allow.PublicOnly => NetAddress.isPublic(a)
  }

  /** そのアドレスへつないでよいか（policy.cidrs の範囲も許す. IPv4 の範囲は IPv4 のアドレスにだけ、IPv6 も同じ）. */
  def allowed(a: InetAddress, policy: Policy): Boolean =
    allowed(a, policy.allow) || {
      val ip = IpAddress.fromInetAddress(a)
      policy.cidrs.exists(c =>
        c.address.fold(_ => true, _ => false) == ip.fold(_ => true, _ => false) && c.contains(ip)
      )
    }

  /**
   * 名前を引いた結果（addrs）から、つなぐアドレスを選ぶ（1つ目）. 許されないアドレスが1つでも混ざっていれば Left.
   * 名前を自分で引くとき（JavaMail 向けに MX を引いた後など）に使う.
   */
  def pick(host: String, addrs: List[InetAddress], policy: Policy = Policy.publicOnly): Either[Rejected, InetAddress] =
    addrs match {
      case Nil => Left(Rejected.Unresolvable(host))
      case first :: _ =>
        if policy.hosts.contains(bare(host)) || addrs.forall(allowed(_, policy)) then Right(first)
        else Left(Rejected.Internal(host))
    }

  /** host の名前を引いて、つないでよいアドレスを返す（つなぐときは、このアドレスにそのままつなぐこと）. */
  def resolve(host: String, policy: Policy = Policy.publicOnly): IO[Either[Rejected, InetAddress]] =
    IO.blocking(InetAddress.getAllByName(bare(host)).toList).attempt.map {
      case Left(_) => Left(Rejected.Unresolvable(host))
      case Right(as) => pick(host, as, policy)
    }

  /** 送ってよい URL か（http・https で、宛先が許されたアドレス）. */
  def check(uri: Uri, policy: Policy): IO[Either[Rejected, Unit]] =
    (uri.scheme.map(_.value.toString.toLowerCase), uri.host.map(h => bare(h.value))) match {
      case (Some("http" | "https"), Some(host)) if host.nonEmpty =>
        if policy.allow == Allow.All || policy.hosts.contains(host) then IO.pure(Right(()))
        else resolve(host, policy).map(_.map(_ => ()))
      case _ => IO.pure(Left(Rejected.BadScheme))
    }

  def check(url: String, policy: Policy): IO[Either[Rejected, Unit]] =
    Uri.fromString(url.trim).fold(_ => IO.pure(Left(Rejected.InvalidUrl)), check(_, policy))

  /** check の結果だけ（理由は要らないとき）. */
  def isAllowed(url: String, policy: Policy): IO[Boolean] = check(url, policy).map(_.isRight)

  /**
   * 外へつなぐ HTTP クライアント（リダイレクトはたどらない. たどるときは、呼び出し側で1回ごとにこのクライアントで取り直す）.
   * つなぐたびにホスト名を引いて確かめ、確かめたアドレスにそのままつなぐ
   * （確かめてからつなぐまでの間に名前の向き先を変えられても、内部へはつながない. TLS の検証と SNI は元のホスト名で行う）.
   * 断ったときは Rejected.Internal / Rejected.Unresolvable で失敗する. Allow.All で hosts も空なら、確かめないふつうのクライアント.
   * maxResponseHeaderSize は応答のヘッダーの大きさの上限（バイト. None なら ember の既定）.
   * configure は、そのほかの設定を足すためのもの（EmberClientBuilder をそのまま受け取って返す）.
   */
  def client(policy: Policy = Policy.publicOnly,
             timeout: FiniteDuration = 15.seconds,
             idleConnectionTime: FiniteDuration = 30.seconds,
             maxResponseHeaderSize: Option[Int] = None,
             configure: EmberClientBuilder[IO] => EmberClientBuilder[IO] = identity): Resource[IO, Client[IO]] = {
    given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.noop.NoOpFactory[IO]
    def build(using Network[IO]) = {
      val b = EmberClientBuilder.default[IO].withTimeout(timeout).withIdleConnectionTime(idleConnectionTime)
      configure(maxResponseHeaderSize.fold(b)(b.withMaxResponseHeaderSize)).build
    }
    if policy.allow == Allow.All then build(using Network[IO]) else build(using network(policy))
  }

  /** 確かめたアドレスにそのままつなぐ Network（ember のクライアント・サーバー以外のソケットを自分で作るとき）. */
  def network(policy: Policy = Policy.publicOnly): Network[IO] =
    new fs2.io.net.PinnedNetwork(
      Network[IO],
      h => resolve(h.toString, policy).flatMap(r => IO.fromEither(r.map(IpAddress.fromInetAddress)))
    )

  /**
   * 決まった IP へつなぐソケットの工場（JavaMail など、javax.net.SocketFactory を受け取るライブラリ向け）.
   * resolve / pick で確かめたアドレスを渡す（connect に渡された名前は引き直さない）.
   */
  def socketFactory(ip: InetAddress, connectTimeoutMs: Int = 15000): javax.net.SocketFactory =
    new PinnedSocketFactory(ip, connectTimeoutMs)

  private def bare(host: String): String = host.stripPrefix("[").stripSuffix("]").toLowerCase
}

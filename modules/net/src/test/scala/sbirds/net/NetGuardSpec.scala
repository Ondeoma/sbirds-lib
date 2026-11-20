package sbirds.net

import munit.CatsEffectSuite
import sbirds.net.NetAddress.Kind
import sbirds.net.NetGuard.{Allow, Policy, Rejected}

import java.net.InetAddress

class NetGuardSpec extends CatsEffectSuite {

  private def kind(s: String): Kind = NetAddress.kind(InetAddress.getByName(s))

  /** 16 バイトをそのまま IPv6 として作る（getByName は ::ffff:a.b.c.d を IPv4 に直してしまうため）. */
  private def v6(hex: String): InetAddress =
    java.net.Inet6Address.getByAddress(null, hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray, 0)

  test("外のアドレスは Public") {
    List("93.184.216.34", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1", "198.20.0.1", "2606:4700::1111")
      .foreach(a => assertEquals(kind(a), Kind.Public, a))
  }

  test("社内のネットワークは Private") {
    List(
      "10.0.0.5",
      "172.16.0.1",
      "172.31.255.255",
      "192.168.1.1",
      "100.64.0.1",
      "100.127.255.255",
      "fd00::1",
      "fc00::1",
      "fec0::1"
    ).foreach(a => assertEquals(kind(a), Kind.Private, a))
  }

  test("loopback・link-local・メタデータ・予約済みは Local") {
    List(
      "127.0.0.1",
      "127.8.8.8",
      "0.0.0.0",
      "0.1.2.3",
      "169.254.169.254",
      "169.254.0.1",
      "192.0.0.1",
      "198.18.0.1",
      "198.19.255.255",
      "224.0.0.1",
      "240.0.0.1",
      "255.255.255.255",
      "::1",
      "::",
      "fe80::1",
      "ff02::1",
      "2001::1",
      "100::1",
      "64:ff9b:1::1"
    ).foreach(a => assertEquals(kind(a), Kind.Local, a))
  }

  test("IPv4 を埋め込んだ IPv6 は、中の IPv4 で確かめ、外のアドレスでも通さない") {
    // ::ffff:a.b.c.d は中の IPv4 と同じ
    assertEquals(NetAddress.kind(v6("00000000000000000000ffff7f000001")), Kind.Local)
    assertEquals(NetAddress.kind(v6("00000000000000000000ffff0a000001")), Kind.Private)
    assertEquals(NetAddress.kind(v6("00000000000000000000ffff5db8d822")), Kind.Public)
    // ::a.b.c.d・NAT64・6to4
    assertEquals(kind("::10.0.0.1"), Kind.Private)
    assertEquals(kind("::93.184.216.34"), Kind.Local)
    assertEquals(kind("64:ff9b::a00:1"), Kind.Private)
    assertEquals(kind("64:ff9b::7f00:1"), Kind.Local)
    assertEquals(kind("64:ff9b::5db8:d822"), Kind.Local)
    assertEquals(kind("2002:a00:1::1"), Kind.Private)
    assertEquals(kind("2002:a9fe:a9fe::1"), Kind.Local)
    assertEquals(kind("2002:5db8:d822::1"), Kind.Local)
  }

  test("Allow.Private は社内だけ許し、loopback・メタデータは許さない") {
    def ok(s: String, a: Allow) = NetGuard.allowed(InetAddress.getByName(s), a)
    assert(ok("10.0.0.5", Allow.Private) && !ok("127.0.0.1", Allow.Private) && !ok("169.254.169.254", Allow.Private))
    assert(!ok("10.0.0.5", Allow.PublicOnly) && ok("8.8.8.8", Allow.PublicOnly))
    assert(ok("127.0.0.1", Allow.All))
  }

  test("pick: 内部のアドレスが1つでも混ざっていれば断る") {
    val pub = InetAddress.getByName("93.184.216.34")
    val priv = InetAddress.getByName("10.0.0.5")
    assertEquals(NetGuard.pick("a.example", List(pub)), Right(pub))
    assertEquals(NetGuard.pick("a.example", List(pub, priv)), Left(Rejected.Internal("a.example")))
    assertEquals(NetGuard.pick("a.example", Nil), Left(Rejected.Unresolvable("a.example")))
    assertEquals(NetGuard.pick("a.example", List(pub, priv), Policy(Allow.Private)), Right(pub))
    assertEquals(NetGuard.pick("Intra.Example", List(priv), Policy(hosts = Set("intra.example"))), Right(priv))
  }

  test("許可リスト: 書いたホスト・CIDR・IP の内部のアドレスだけ許す") {
    val p = Policy.allowList(List("10.1.0.0/16", "127.0.0.1", "[::1]", "fd00:1::/32", "Intra.Example")).toOption.get
    def ok(s: String) = NetGuard.allowed(InetAddress.getByName(s), p)
    assert(ok("10.1.2.3") && ok("127.0.0.1") && ok("::1") && ok("fd00:1::5") && ok("8.8.8.8"))
    assert(!ok("10.2.0.1") && !ok("127.0.0.2") && !ok("169.254.169.254") && !ok("fd00:2::5") && !ok("192.168.1.1"))
    assertEquals(p.hosts, Set("intra.example"))
    val priv = InetAddress.getByName("10.1.2.3")
    val other = InetAddress.getByName("10.2.0.1")
    assertEquals(NetGuard.pick("a.example", List(priv), p), Right(priv))
    // 許した範囲の外のアドレスが混ざっていれば断る
    assertEquals(NetGuard.pick("a.example", List(priv, other), p), Left(Rejected.Internal("a.example")))
    // IPv4 の範囲は IPv6 のアドレスには当てない
    val all4 = Policy.allowList(List("0.0.0.0/0")).toOption.get
    assert(NetGuard.allowed(InetAddress.getByName("127.0.0.1"), all4) &&
      !NetGuard.allowed(InetAddress.getByName("::1"), all4))
    assertEquals(Policy.allowList(Nil), Right(Policy.publicOnly))
    assert(Policy.allowList(List("10.0.0.0/99")).isLeft && Policy.allowList(List("http://x/")).isLeft)
    for {
      a <- NetGuard.check("http://127.0.0.1:9/x", p)
      b <- NetGuard.check("http://127.0.0.2:9/x", p)
      c <- NetGuard.check("http://10.1.9.9/x", p)
      d <- NetGuard.resolve("10.2.0.1", p)
    } yield assert(a.isRight && b.isLeft && c.isRight && d.isLeft)
  }

  test("check: http・https だけ. IP を直接書いた URL も確かめる") {
    for {
      lo <- NetGuard.check("https://127.0.0.1/hook", Policy.publicOnly)
      pr <- NetGuard.check("https://10.0.0.5:8443/x", Policy.publicOnly)
      md <- NetGuard.check("http://169.254.169.254/latest/meta-data", Policy(Allow.Private))
      v6 <- NetGuard.check("https://[::1]/x", Policy.publicOnly)
      ula <- NetGuard.check("https://[fd00::1]/x", Policy.publicOnly)
      ok <- NetGuard.check("https://93.184.216.34/hook", Policy.publicOnly)
      pr2 <- NetGuard.check("https://10.0.0.5/x", Policy(Allow.Private))
      all <- NetGuard.check("http://localhost:9/hook", Policy.allowAll)
      host <- NetGuard.check("http://intra.invalid/x", Policy(hosts = Set("intra.invalid")))
      ftp <- NetGuard.check("ftp://93.184.216.34/x", Policy.allowAll)
      bad <- NetGuard.check("http://exa mple/", Policy.publicOnly)
    } yield {
      assertEquals(lo, Left(Rejected.Internal("127.0.0.1")))
      assert(pr.isLeft && md.isLeft && v6.isLeft && ula.isLeft)
      assert(ok.isRight && pr2.isRight && all.isRight && host.isRight)
      assertEquals(ftp, Left(Rejected.BadScheme))
      assert(bad.isLeft)
    }
  }

  test("client: 内部のアドレスへはつながない") {
    NetGuard.client().use(_.expect[String]("http://127.0.0.1:9/")).attempt.map(r =>
      assert(r.left.exists(_.isInstanceOf[Rejected.Internal]), r.toString)
    )
  }
}

package sbirds.net

import java.net.{Inet4Address, Inet6Address, InetAddress}

/**
 * アドレスの種類の判定（SSRF 対策の判定表. sbirds の全サービスでこの1つを使う）.
 *  - Local: 外の相手ではなく、設定でも許さないことが多いもの. loopback（127/8・::1）・未指定（0.0.0.0・::）・
 *    link-local（169.254/16〈クラウドのメタデータ 169.254.169.254 を含む〉・fe80::/10）・マルチキャスト（224/4・ff00::/8）・
 *    0.0.0.0/8・192.0.0.0/24・198.18.0.0/15・240.0.0.0/4（ブロードキャストを含む）・
 *    IPv4 を埋め込んだ IPv6（::a.b.c.d・NAT64 の 64:ff9b::/96 と 64:ff9b:1::/48・6to4 の 2002::/16・Teredo の 2001::/32）・捨てる宛先（100::/64）
 *  - Private: 社内のネットワーク. RFC1918（10/8・172.16/12・192.168/16）・CGNAT（100.64/10）・ULA（fc00::/7）・site-local（fec0::/10）
 *  - Public: それ以外（インターネットの相手）
 * IPv4 を埋め込んだ IPv6 は、中の IPv4 が Private なら Private、それ以外は（中が外のアドレスでも）Local にする
 * （変換する経路があると内部に届くことがあり、ふつうの外の相手はこの形のアドレスで公開しない）. ::ffff:a.b.c.d は中の IPv4 と同じ.
 */
object NetAddress {

  enum Kind {
    case Public, Private, Local
  }

  def kind(a: InetAddress): Kind = {
    val b = a.getAddress.map(_ & 0xff)
    def v4(from: Int): Kind = kind4(b.slice(from, from + 4))
    // IPv4 を埋め込んだアドレス: 中が Private のときだけ Private（それ以外は外の相手として扱わない）
    def embedded(from: Int): Kind = if v4(from) == Kind.Private then Kind.Private else Kind.Local
    a match {
      case _: Inet4Address => kind4(b)
      case _: Inet6Address =>
        if b.take(10).forall(_ == 0) && b(10) == 0xff && b(11) == 0xff then v4(12) // ::ffff:a.b.c.d
        else if b.take(12).forall(_ == 0) then embedded(12) // ::/96（::・::1・::a.b.c.d）
        else if b(0) == 0 && b(1) == 0x64 && b(2) == 0xff && b(3) == 0x9b && b.slice(4, 12).forall(_ == 0) then
          embedded(12) // 64:ff9b::/96（NAT64）
        else if b(0) == 0 && b(1) == 0x64 && b(2) == 0xff && b(3) == 0x9b && b(4) == 0 && b(5) == 1 then
          Kind.Local // 64:ff9b:1::/48（NAT64）
        else if b(0) == 0x20 && b(1) == 0x02 then embedded(2) // 2002::/16（6to4）
        else if b(0) == 0x20 && b(1) == 0x01 && b(2) == 0 && b(3) == 0 then Kind.Local // 2001::/32（Teredo）
        else if b(0) == 0x01 && b.slice(1, 8).forall(_ == 0) then Kind.Local // 100::/64（捨てる宛先）
        else if b(0) == 0xff then Kind.Local // ff00::/8（マルチキャスト）
        else if b(0) == 0xfe && (b(1) & 0xc0) == 0x80 then Kind.Local // fe80::/10（link-local）
        else if b(0) == 0xfe && (b(1) & 0xc0) == 0xc0 then Kind.Private // fec0::/10（site-local）
        else if (b(0) & 0xfe) == 0xfc then Kind.Private // fc00::/7（ULA）
        else Kind.Public
      case _ => Kind.Local
    }
  }

  private def kind4(b: Array[Int]): Kind =
    if b(0) == 0 || b(0) == 127 || (b(0) == 169 && b(1) == 254) || b(0) >= 224 then Kind.Local
    else if (b(0) == 192 && b(1) == 0 && b(2) == 0) || (b(0) == 198 && (b(1) & 0xfe) == 18) then Kind.Local
    else if b(0) == 10 || (b(0) == 172 && (b(1) & 0xf0) == 16) || (b(0) == 192 && b(1) == 168) then Kind.Private
    else if b(0) == 100 && (b(1) & 0xc0) == 64 then Kind.Private
    else Kind.Public

  /** 外（インターネット）のアドレスか. */
  def isPublic(a: InetAddress): Boolean = kind(a) == Kind.Public

  /** 内部・特別な用途のアドレスか（isPublic の逆）. */
  def isInternal(a: InetAddress): Boolean = !isPublic(a)
}

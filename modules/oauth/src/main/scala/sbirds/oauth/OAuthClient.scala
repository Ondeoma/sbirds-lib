package sbirds.oauth

import cats.syntax.all.*
import sbirds.http.Env

/**
 * 認可を求める外部のサービス（環境変数 OAUTH_CLIENTS で登録する）.
 * redirectUri は完全一致で照合する. scopes はそのクライアントに許すスコープ.
 * secret は、ふつうは平文（定数時間で比べる）. ハッシュで持つサービスは OAuthProvider.Host.secretMatches を差し替える.
 */
final case class OAuthClient(id: String,
                             secret: String,
                             redirectUri: String,
                             name: String,
                             scopes: List[String] = List("read"))

object OAuthClient {

  val ItemError = "OAUTH_CLIENTS の各要素には id・secret（16文字以上）・redirectUri（https://）を指定してください"

  /**
   * OAUTH_CLIENTS の読み取りの決まり（サービスごとの違い）.
   *  - scopes: 使えるスコープ. default: scopes を省いたときのスコープ.
   *  - scopeError: None なら、知らないスコープは捨てる（残らなければ default）. Some なら、知らないスコープ・空の配列をエラーにする（引数はクライアントの id）.
   *  - duplicateError: Some なら、id の重なりをエラーにする.
   */
  final case class Rules(scopes: List[String] = List("read", "write"),
                         default: List[String] = List("read"),
                         itemError: String = ItemError,
                         scopeError: Option[String => String] = None,
                         duplicateError: Option[String] = None)

  /** 手元のホストか（localhost・127.0.0.1・[::1]・*.localhost）. */
  def isLoopbackHost(host: String): Boolean = {
    val h = host.toLowerCase.stripPrefix("[").stripSuffix("]")
    h == "localhost" || h == "127.0.0.1" || h == "::1" || h.endsWith(".localhost")
  }

  /**
   * 登録してよい redirectUri か: https:// の URL か、手元のホストの http:// の URL.
   * 前方一致ではなく URL として読んでホストを比べる（http://localhost.evil.example は通さない）. # の付いたものは通さない.
   */
  def redirectUriAllowed(uri: String): Boolean =
    scala.util.Try(new java.net.URI(uri)).toOption.exists { u =>
      val host = Option(u.getHost).filter(_.nonEmpty)
      val scheme = Option(u.getScheme).map(_.toLowerCase)
      u.getRawFragment == null && u.getRawUserInfo == null && host.isDefined &&
      (scheme match {
        case Some("https") => true
        case Some("http") => host.exists(isLoopbackHost)
        case _ => false
      })
    }

  /** OAUTH_CLIENTS の値（JSON の配列）を読む: [{"id":"skea","secret":"…","redirectUri":"https://…/callback","name":"skea","scopes":["read"]}] */
  def parse(json: String, rules: Rules = Rules()): Either[String, List[OAuthClient]] =
    io.circe.parser.parse(json).flatMap(_.as[List[io.circe.Json]]).left.map(_ =>
      "OAUTH_CLIENTS は JSON の配列で指定してください"
    ).flatMap(_.traverse { j =>
      val c = j.hcursor
      (c.get[String]("id"), c.get[String]("secret"), c.get[String]("redirectUri")).tupled.toOption
        .filter((id, sec, uri) => id.nonEmpty && sec.length >= 16 && redirectUriAllowed(uri))
        .toRight(rules.itemError)
        .flatMap { (id, sec, uri) =>
          val listed = c.get[List[String]]("scopes").toOption
          val scopes = rules.scopeError match {
            case None => Right(listed.map(_.filter(rules.scopes.contains)).filter(_.nonEmpty).getOrElse(rules.default))
            case Some(msg) =>
              val sc = listed.getOrElse(rules.default)
              Either.cond(sc.nonEmpty && sc.forall(rules.scopes.contains), sc, msg(id))
          }
          scopes.map(sc => OAuthClient(id, sec, uri, c.get[String]("name").getOrElse(id), sc.distinct))
        }
    }).flatMap(cs =>
      rules.duplicateError.fold(Right(cs))(msg => Either.cond(cs.map(_.id).distinct.size == cs.size, cs, msg))
    )

  /** 環境変数 OAUTH_CLIENTS を読む（無ければ空）. */
  def fromEnv(rules: Rules = Rules(),
              env: Env = Env,
              name: String = "OAUTH_CLIENTS"): Either[String, List[OAuthClient]] =
    env.opt(name).fold(Right(Nil))(parse(_, rules))
}

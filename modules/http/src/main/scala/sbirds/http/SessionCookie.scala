package sbirds.http

import cats.effect.IO
import org.http4s.*
import sbirds.auth.{AppNames, Crypto}

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/**
 * セッションの Cookie（各サービスの SessionAuth の共通部分）. 値はランダムなトークンで、DB には SHA-256 のハッシュだけを保存する.
 * 保存・検索・削除は各サービスの UserStore の関数を受け取る（主体の型はサービスごとに違うので、ここでは扱わない）.
 * {{{
 * private val cookie = new SessionCookie(cfg.cookieName, cfg.cookieSecure, cfg.sessionTtl)
 * def issue(user: User, idToken: Option[String]) = cookie.issue((hash, exp) => users.createSession(hash, user.id, exp, idToken))
 * def revoke(req: Request[IO]) = cookie.revoke(req)(users.deleteSession)
 * def appNames(req: Request[IO]) = cookie.appNames(req)(users.sessionIdToken)
 * }}}
 */
final class SessionCookie(val name: String, secure: Boolean, ttl: FiniteDuration) {

  /** 要求の Cookie の値（トークン）. */
  def token(req: Request[IO]): Option[String] = Cookies.value(req, name)

  /** トークンのハッシュ（DB の検索に使う）. */
  def hash(req: Request[IO]): Option[Array[Byte]] = token(req).map(Crypto.sha256)

  /** HttpOnly・SameSite=Lax・path=/ の Cookie. */
  def cookie(value: String, maxAge: Long): ResponseCookie = Cookies.httpOnly(name, value, maxAge, secure)

  /** Cookie を消す指示. */
  def expired: ResponseCookie = cookie("", 0)

  /** 新しいセッションを発行する. save は (トークンのハッシュ, 有効期限) を保存する. */
  def issue(save: (Array[Byte], Instant) => IO[Unit]): IO[ResponseCookie] = {
    val token = Crypto.randomToken()
    save(Crypto.sha256(token), Instant.now().plusSeconds(ttl.toSeconds)).as(cookie(token, ttl.toSeconds))
  }

  /**
   * セッションを消す. delete はハッシュのセッションを消して、IdP からもログアウトするための id_token（あれば）を返す.
   * 返すのは (id_token, Cookie を消す指示).
   */
  def revoke(req: Request[IO])(delete: Array[Byte] => IO[Option[String]]): IO[(Option[String], ResponseCookie)] =
    hash(req) match {
      case Some(h) => delete(h).map(_ -> expired)
      case None => IO.pure(None -> expired)
    }

  /** sbirds のアプリの表示名（ログインしたときの ID トークンにある. API キーや開発用のログインでは空）. */
  def appNames(req: Request[IO])(idToken: Array[Byte] => IO[Option[String]]): IO[Map[String, String]] =
    hash(req) match {
      case Some(h) => idToken(h).map(_.fold(Map.empty)(AppNames.fromIdToken))
      case None => IO.pure(Map.empty)
    }
}

package sbirds.oauth

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import sbirds.db.TxStore

import java.time.Instant
import java.util.UUID

/** 認可コードに結び付けて保存するもの. scope の形（空白区切り・1 つの値）は各サービスが決める. */
final case class OAuthCode(clientId: String, redirectUri: String, userId: UUID, codeChallenge: String, scope: String)

/** 認可コードの保存（OAuthProvider が使う入口. 自分のストアに持つサービスはこれを実装して渡す）. */
trait OAuthCodes {
  def createCode(hash: Array[Byte], c: OAuthCode, expires: Instant): IO[Unit]

  /** 認可コードを使う（有効期限内で、まだ使っていないものだけ. 使ったら使用済みにする）. */
  def consumeCode(hash: Array[Byte]): IO[Option[OAuthCode]]
}

/**
 * 認可コードの表（oauth_code: code_hash・client_id・redirect_uri・user_id・code_challenge・scope・expires_at・used_at）.
 * 表のマイグレーションは各サービスに置く.
 * onReuse は、使用済みのコードがもう一度出されたときに同じトランザクションで行う処理
 * （盗まれた恐れがあるので、そのコードで発行したトークンを失効させる. RFC 6749 4.1.2. 引数はコードのハッシュ）.
 */
class OAuthCodeStore(xa: Transactor[IO], onReuse: Array[Byte] => ConnectionIO[Unit] = _ => ().pure[ConnectionIO])
  extends TxStore(xa) with OAuthCodes {

  def createCode(hash: Array[Byte], c: OAuthCode, expires: Instant): IO[Unit] = tx(
    sql"""insert into oauth_code (code_hash, client_id, redirect_uri, user_id, code_challenge, scope, expires_at)
          values ($hash, ${c.clientId}, ${c.redirectUri}, ${c.userId}, ${c.codeChallenge}, ${c.scope}, $expires)""".update.run *>
      sql"delete from oauth_code where expires_at < now() - interval '1 hour'".update.run.void
  )

  def consumeCode(hash: Array[Byte]): IO[Option[OAuthCode]] = tx(
    sql"""update oauth_code set used_at = now() where code_hash = $hash and used_at is null and expires_at > now()
          returning client_id, redirect_uri, user_id, code_challenge, scope""".query[OAuthCode].option.flatMap {
      case some @ Some(_) => some.pure[ConnectionIO]
      case None => onReuse(hash).as(none[OAuthCode])
    }
  )
}

/**
 * 認可コードの表に加えて、認可で発行する API キーの表
 * （api_key: id・user_id・name・prefix・key_hash・scopes〈text[]〉・oauth_client・revoked_at・created_at）も扱う.
 * この形の表を持つサービス（stui・skorimako・smohua・skea・skawau）向け. 形の違うサービスは OAuthCodeStore と自分のストアを使う.
 * revokeOnReuse なら、使用済みのコードがもう一度出されたときに、そのコードで発行したキーを失効させる.
 */
class OAuthKeyStore(xa: Transactor[IO], revokeOnReuse: Boolean = false)
  extends OAuthCodeStore(xa, if revokeOnReuse then OAuthKeyStore.revokeIssued else _ => ().pure[ConnectionIO]) {

  /** そのユーザーにそのクライアントが以前に発行したキーを失効させ、新しいキーを登録する. */
  def replaceKey(userId: UUID,
                 clientId: String,
                 name: String,
                 prefix: String,
                 hash: Array[Byte],
                 scopes: List[String]): IO[UUID] = tx {
    val id = UUID.randomUUID()
    sql"update api_key set revoked_at = now() where user_id = $userId and oauth_client = $clientId and revoked_at is null".update.run *>
      sql"""insert into api_key (id, user_id, name, prefix, key_hash, scopes, oauth_client)
            values ($id, $userId, $name, $prefix, $hash, ${scopes.toArray}, $clientId)""".update.run.as(id)
  }

  /** そのクライアントが発行したキーを失効させる（なければ何もしない）. */
  def revokeKey(hash: Array[Byte], clientId: String): IO[Unit] = tx(
    sql"update api_key set revoked_at = now() where key_hash = $hash and oauth_client = $clientId and revoked_at is null".update.run.void
  )
}

object OAuthKeyStore {

  /** OAuthCodeStore の onReuse に渡す: 使用済みのコードで発行したキー（api_key）を失効させる. */
  def revokeIssued(codeHash: Array[Byte]): ConnectionIO[Unit] =
    sql"""update api_key k set revoked_at = now() from oauth_code c
          where c.code_hash = $codeHash and c.used_at is not null and k.user_id = c.user_id and k.oauth_client = c.client_id
            and k.revoked_at is null and k.created_at >= c.used_at""".update.run.void
}

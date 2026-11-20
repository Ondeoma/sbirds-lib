package sbirds.db

import cats.effect.*
import com.zaxxer.hikari.HikariConfig
import doobie.*
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import org.flywaydb.core.Flyway

final case class DbConfig(url: String, user: String, password: String, maxPoolSize: Int = 10)

/** DB への接続（HikariCP のプール）と、マイグレーション（Flyway. 各サービスの db/migration を適用する）。 */
object Database {

  def migrate(cfg: DbConfig): IO[Int] =
    IO.blocking {
      Flyway.configure().dataSource(cfg.url, cfg.user, cfg.password).load().migrate().migrationsExecuted
    }

  /**
   * 接続のプール。
   * telemetry: SQL ごとにトレースの子のスパンと所要時間のメトリクスを残す（SQL の文は ? のままで、値は載せない）。
   * startWithoutDb: 起動の時点でプライマリが見つからなくても（フェイルオーバーの最中など）起動を続け、後からつなぐ。
   */
  def transactor(cfg: DbConfig,
                 telemetry: Boolean = true,
                 startWithoutDb: Boolean = false): Resource[IO, HikariTransactor[IO]] = {
    val hc = new HikariConfig()
    hc.setJdbcUrl(cfg.url)
    hc.setUsername(cfg.user)
    hc.setPassword(cfg.password)
    hc.setMaximumPoolSize(cfg.maxPoolSize)
    hc.setDriverClassName("org.postgresql.Driver")
    // Patroni のフェイルオーバー対策: 接続待ちを短くし、切れた接続は早めに検出して張り直す。
    // URL は複数ホスト + targetServerType=primary を想定（例: jdbc:postgresql://db1,db2,db3/skea?targetServerType=primary）
    hc.setConnectionTimeout(5000)
    hc.setValidationTimeout(3000)
    hc.setKeepaliveTime(60000)
    hc.setMaxLifetime(600000)
    if startWithoutDb then hc.setInitializationFailTimeout(-1)
    HikariTransactor.fromHikariConfig[IO](hc, Option.when(telemetry)(sbirds.observe.DbTelemetry.logHandler))
  }

  /** 書き込める DB（プライマリ）につながっているか（ヘルスチェック用。sbirds.http.Health に渡す）。 */
  def isPrimary(xa: Transactor[IO]): IO[Boolean] =
    sql"select not pg_is_in_recovery()".query[Boolean].unique.transact(xa)

  /** DB につながるか（プライマリかどうかは見ない）。 */
  def ping(xa: Transactor[IO]): IO[Boolean] = sql"select 1".query[Int].unique.transact(xa).as(true)
}

package sbirds.http

import cats.effect.*
import cats.syntax.all.*
import fs2.io.file.Path
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.server.staticcontent.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** ビルド済みの画面（Vite の dist）の配信. 存在しないパスは index.html を返す（SPA）. */
object Spa {

  /** バックエンドのパスか（画面の index.html を返さない）: パスが "/" + 名前 で始まる（"api" は /api・/api/x・/apix に当たる）. */
  def startsWith(names: String*): Request[IO] => Boolean =
    req => names.exists(n => req.pathInfo.renderString.startsWith("/" + n))

  /** バックエンドのパスか: パスの最初の区切りが名前と同じ（"api" は /api・/api/x に当たり、/apix には当たらない）. */
  def firstSegment(names: String*): Request[IO] => Boolean = {
    val set = names.toSet
    req => set.contains(req.pathInfo.segments.headOption.map(_.decoded()).getOrElse(""))
  }

  /**
   * dir のファイルを返し、無ければ（backend に当たらない GET なら）index.html を返す.
   * Cache-Control は /assets/（ファイル名に内容のハッシュが入る）なら長期、それ以外は no-cache.
   * notFound: index.html が無いときの応答（既定は空の 404）.
   * LoggerFactory は各サービスの Main の given（Slf4jFactory）をそのまま使う.
   * finish: 応答の仕上げ（画面の CSP を付ける、特定のファイルだけ Cache-Control を変える、など. Cache-Control を付けた後に呼ぶ）.
   */
  def routes(dir: String,
             backend: Request[IO] => Boolean,
             notFound: IO[Response[IO]] = NotFound(),
             finish: (Request[IO], Response[IO]) => Response[IO] = (_, r) => r)(using
    org.typelevel.log4cats.LoggerFactory[IO]): HttpRoutes[IO] = {
    val files = fileService[IO](FileService.Config(dir))
    val index = HttpRoutes.of[IO] {
      case req @ GET -> _ if !backend(req) =>
        StaticFile.fromPath(Path(dir) / "index.html", Some(req)).getOrElseF(notFound)
    }
    HttpRoutes[IO] { req =>
      val cc =
        if req.pathInfo.renderString.startsWith("/assets/") then "public, max-age=31536000, immutable" else "no-cache"
      (files <+> index).run(req).map(r => finish(req, r.putHeaders("Cache-Control" -> cc)))
    }
  }

  /** 画面の index.html の中身（CSP に載せるハッシュを計算するために、起動のときに読む）. */
  def indexHtml(dir: String): IO[String] =
    IO.blocking(java.nio.file.Files.readString(java.nio.file.Path.of(dir, "index.html")))

  /** index.html に直接書いたスクリプト（`<script>…</script>`）を CSP の script-src で許すための値（'sha256-…'）. */
  def inlineScriptHashes(indexHtml: String): List[String] =
    "(?s)<script>(.*?)</script>".r.findAllMatchIn(indexHtml).map { m =>
      val d = MessageDigest.getInstance("SHA-256").digest(m.group(1).getBytes(StandardCharsets.UTF_8))
      s"'sha256-${Base64.getEncoder.encodeToString(d)}'"
    }.toList
}

package sbirds.oauth

import cats.effect.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.{Authorization, Location}
import org.http4s.implicits.*
import org.typelevel.ci.CIString
import sbirds.auth.Crypto
import sbirds.http.Env

import java.time.Instant
import java.util.UUID

class OAuthProviderSpec extends CatsEffectSuite {

  // ---------------------------------------------------------------- クライアントの設定

  private val secret = "0123456789abcdef"
  private def item(uri: String, extra: String = "") =
    s"""[{"id":"skea","secret":"$secret","redirectUri":"$uri"$extra}]"""

  test("redirectUriAllowed: https か、手元のホストの http だけ（ホストは完全一致）") {
    val ok = List(
      "https://skea.example.com/api/integrations/stui/callback",
      "http://localhost:8090/callback",
      "http://localhost/callback",
      "http://127.0.0.1:5173/cb",
      "http://[::1]:5173/cb",
      "http://skea.localhost:8090/cb"
    )
    val ng = List(
      "http://localhost.evil.example/callback",
      "http://localhostevil.example/callback",
      "http://localhost@evil.example/callback",
      "http://evil.example/localhost",
      "http://example.com/callback",
      "https://skea.example.com/cb#frag",
      "https:///nohost",
      "javascript:alert(1)",
      "ftp://localhost/x",
      "localhost:8090/callback",
      ""
    )
    ok.foreach(u => assert(OAuthClient.redirectUriAllowed(u), u))
    ng.foreach(u => assert(!OAuthClient.redirectUriAllowed(u), u))
  }

  test("parse: 既定は、知らないスコープを捨てて read に戻す") {
    assertEquals(
      OAuthClient.parse(item("https://a.example/cb")),
      Right(List(OAuthClient("skea", secret, "https://a.example/cb", "skea", List("read"))))
    )
    assertEquals(
      OAuthClient.parse(item(
        "https://a.example/cb",
        ""","name":"タスク","scopes":["write","admin","read"]"""
      )).map(_.head),
      Right(OAuthClient("skea", secret, "https://a.example/cb", "タスク", List("write", "read")))
    )
    assertEquals(
      OAuthClient.parse(item("https://a.example/cb", ""","scopes":["admin"]""")).map(_.head.scopes),
      Right(List("read"))
    )
  }

  test("parse: 配列でない・項目が足りない・秘密が短い・redirectUri が許されない") {
    assertEquals(OAuthClient.parse("{}"), Left("OAUTH_CLIENTS は JSON の配列で指定してください"))
    assertEquals(OAuthClient.parse("x"), Left("OAUTH_CLIENTS は JSON の配列で指定してください"))
    assertEquals(OAuthClient.parse("""[{"id":"a","secret":"0123456789abcdef"}]"""), Left(OAuthClient.ItemError))
    assertEquals(
      OAuthClient.parse("""[{"id":"a","secret":"short","redirectUri":"https://a.example/cb"}]"""),
      Left(OAuthClient.ItemError)
    )
    assertEquals(OAuthClient.parse(item("http://localhost.evil.example/cb")), Left(OAuthClient.ItemError))
    assertEquals(OAuthClient.parse(item("http://example.com/cb")), Left(OAuthClient.ItemError))
    assertEquals(
      OAuthClient.parse(item("https://a.example/cb"), OAuthClient.Rules(itemError = "だめ")).map(_.size),
      Right(1)
    )
    assertEquals(OAuthClient.parse(item("ng"), OAuthClient.Rules(itemError = "だめ")), Left("だめ"))
  }

  test("parse: scopeError を渡すと知らないスコープ・空の配列はエラー. duplicateError を渡すと id の重なりはエラー") {
    val strict = OAuthClient.Rules(
      scopes = List("read", "write", "send"),
      scopeError = Some(id => s"$id の scopes が正しくありません"),
      duplicateError = Some("重なっています")
    )
    assertEquals(
      OAuthClient.parse(
        item("https://a.example/cb", ""","scopes":["send","read","send"]"""),
        strict
      ).map(_.head.scopes),
      Right(List("send", "read"))
    )
    assertEquals(OAuthClient.parse(item("https://a.example/cb"), strict).map(_.head.scopes), Right(List("read")))
    assertEquals(
      OAuthClient.parse(item("https://a.example/cb", ""","scopes":["admin"]"""), strict),
      Left("skea の scopes が正しくありません")
    )
    assertEquals(
      OAuthClient.parse(item("https://a.example/cb", ""","scopes":[]"""), strict),
      Left("skea の scopes が正しくありません")
    )
    val two = s"""[{"id":"a","secret":"$secret","redirectUri":"https://a.example/cb"},
                  {"id":"a","secret":"$secret","redirectUri":"https://b.example/cb"}]"""
    assertEquals(OAuthClient.parse(two, strict), Left("重なっています"))
    assertEquals(OAuthClient.parse(two).map(_.size), Right(2))
  }

  test("fromEnv: 無ければ空") {
    assertEquals(OAuthClient.fromEnv(env = new Env(_ => None)), Right(Nil))
    assertEquals(
      OAuthClient.fromEnv(env = new Env(Map("OAUTH_CLIENTS" -> item("https://a.example/cb")).get)).map(_.map(_.id)),
      Right(List("skea"))
    )
  }

  // ---------------------------------------------------------------- スコープと PKCE

  test("requestedScopes: 省くと read. 許していないものがあれば None") {
    assertEquals(OAuthProvider.requestedScopes(List("read", "write"), None), Some(List("read")))
    assertEquals(OAuthProvider.requestedScopes(List("read", "write"), Some("  ")), Some(List("read")))
    assertEquals(
      OAuthProvider.requestedScopes(List("read", "write"), Some("write  read write")),
      Some(List("write", "read"))
    )
    assertEquals(OAuthProvider.requestedScopes(List("read"), Some("read write")), None)
    assertEquals(OAuthProvider.requestedScopes(List("write"), None), None)
  }

  test("PKCE: S256 の照合と、challenge・verifier の形") {
    // RFC 7636 の付録 B の例
    val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    assert(OAuthProvider.pkceMatches(challenge, verifier))
    assert(!OAuthProvider.pkceMatches(challenge, verifier + "x"))
    assert(OAuthProvider.validChallenge(challenge))
    assert(!OAuthProvider.validChallenge(challenge.take(42)))
    assert(!OAuthProvider.validChallenge(challenge.take(42) + "="))
    assert(OAuthProvider.validVerifier(verifier))
    assert(!OAuthProvider.validVerifier("a" * 42))
    assert(!OAuthProvider.validVerifier("a" * 129))
  }

  // ---------------------------------------------------------------- 流れ（DB なし. 認可コードはメモリに置く）

  private final case class Session(user: UUID, name: String)

  private final class Fixture(refreshing: Boolean = false) {
    val client = OAuthClient("skea", secret, "https://skea.example/cb", "skea<b>", List("read", "write"))
    val user = UUID.randomUUID()
    val codes = Ref.unsafe[IO, Map[String, (OAuthCode, Instant, Boolean)]](Map.empty)
    val issued = Ref.unsafe[IO, List[(String, String)]](Nil)
    val revoked = Ref.unsafe[IO, List[String]](Nil)

    val host: OAuthProvider.Host[Session] = new OAuthProvider.Host[Session] {
      val settings = OAuthProvider.Settings("svc", cookieSecure = true)
      def client(id: String) = IO.pure(Option.when(id == "skea")(Fixture.this.client))
      // Cookie のセッションだけ（Bearer では通さない）
      def browserSession(req: Request[IO]) =
        IO.pure(req.cookies.find(_.name == "svc_session").map(_ => Session(user, "山田")))
      def userId(s: Session) = s.user
      def consent(req: Request[IO], c: OAuthClient, s: Session, scopes: List[String]) =
        IO.pure(OAuthProvider.Consent(
          s"${c.name} との連携",
          s"<h1>${OAuthProvider.escape(c.name)}</h1><p>${scopes.mkString(",")}</p>"
        ))
      val codes: OAuthCodes = new OAuthCodes {
        def createCode(hash: Array[Byte], c: OAuthCode, expires: Instant) =
          Fixture.this.codes.update(_ + (Crypto.hex(hash) -> (c, expires, false)))
        def consumeCode(hash: Array[Byte]) = Fixture.this.codes.modify { m =>
          m.get(Crypto.hex(hash)) match {
            case Some((c, exp, false)) if exp.isAfter(Instant.now()) =>
              (m + (Crypto.hex(hash) -> (c, exp, true)), Some(c))
            case _ => (m, None)
          }
        }
      }
      def issue(req: Request[IO], c: OAuthClient, code: OAuthCode) =
        issued.update((c.id, code.scope) :: _).as(OAuthProvider.Token(
          "tok",
          code.scope,
          Option.when(refreshing)(3600L),
          Option.when(refreshing)("ref")
        ))
      override def refresh = Option.when(refreshing)((_, _, rt, _) =>
        IO.pure(
          if rt == "ref" then Right(OAuthProvider.Token("tok2", "read", Some(3600L), Some("ref2")))
          else Left(OAuthProvider.Failure.invalidGrant("無効です"))
        )
      )
      def revoke(req: Request[IO], c: OAuthClient, token: String) = revoked.update(token :: _)
    }
    val app = new OAuthProvider(host).routes.orNotFound
  }

  private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
  private val challenge = Crypto.pkceChallenge(verifier)
  private val authParams = Map(
    "client_id" -> "skea",
    "redirect_uri" -> "https://skea.example/cb",
    "response_type" -> "code",
    "code_challenge" -> challenge,
    "code_challenge_method" -> "S256",
    "state" -> "st 1",
    "scope" -> "read write"
  )
  private def authorizeUri(ps: Map[String, String] = authParams) = uri"/oauth/authorize".withQueryParams(ps)
  private val session = RequestCookie("svc_session", "x")
  private def basic(id: String = "skea", sec: String = secret) = Authorization(BasicCredentials(id, sec))
  private def location(r: Response[IO]): String = r.headers.get[Location].map(_.uri.renderString).getOrElse("")
  private def header(r: Response[IO], n: String) = r.headers.get(CIString(n)).map(_.head.value)
  private def query(loc: String): Map[String, String] = Uri.unsafeFromString(loc).query.params

  /** 許可の画面を開いて許可し、認可コードを得る. */
  private def authorize(f: Fixture, decision: String = "allow"): IO[Response[IO]] =
    for {
      page <- f.app.run(Request[IO](Method.GET, authorizeUri()).addCookie(session))
      csrf = page.cookies.find(_.name == "svc_oauth_csrf").get
      res <- f.app.run(
        Request[IO](Method.POST, uri"/oauth/authorize")
          .withEntity(UrlForm((authParams + ("csrf" -> csrf.content) + ("decision" -> decision)).toSeq*))
          .addCookie(session).addCookie(RequestCookie(csrf.name, csrf.content))
      )
    } yield res

  private def token(f: Fixture, form: (String, String)*)(auth: Authorization = basic()): IO[Response[IO]] =
    f.app.run(Request[IO](Method.POST, uri"/oauth/token").withEntity(UrlForm(form*)).putHeaders(auth))

  test("authorize: client_id・redirect_uri が正しくなければ、リダイレクトせずに 400 の画面") {
    val f = new Fixture
    for {
      a <- f.app.run(Request[IO](Method.GET, authorizeUri(authParams + ("client_id" -> "other"))))
      b <- f.app.run(Request[IO](Method.GET, authorizeUri(authParams + ("redirect_uri" -> "https://skea.example/cb2"))))
      c <- f.app.run(Request[IO](Method.GET, authorizeUri(authParams + ("redirect_uri" -> "https://skea.example/cb/"))))
      body <- a.as[String](using implicitly, EntityDecoder.text[IO])
    } yield {
      assertEquals(List(a.status, b.status, c.status), List.fill(3)(Status.BadRequest))
      assert(body.contains("登録されていないクライアントです"))
      assertEquals(header(a, "X-Frame-Options"), Some("DENY"))
      assertEquals(header(a, "Cache-Control"), Some("no-store"))
    }
  }

  test("authorize: PKCE（S256）が無い・response_type が違う・許していないスコープは redirect_uri へエラーで戻す") {
    val f = new Fixture
    def err(ps: Map[String, String]) =
      f.app.run(Request[IO](Method.GET, authorizeUri(ps)).addCookie(session)).map(r => (r.status, query(location(r))))
    for {
      a <- err(authParams - "code_challenge")
      b <- err(authParams + ("code_challenge_method" -> "plain"))
      c <- err(authParams + ("response_type" -> "token"))
      d <- err(authParams + ("scope" -> "read admin"))
    } yield {
      assertEquals(a._1, Status.Found)
      assertEquals(a._2.get("error"), Some("invalid_request"))
      assertEquals(a._2.get("state"), Some("st 1"))
      assertEquals(b._2.get("error"), Some("invalid_request"))
      assertEquals(c._2.get("error"), Some("unsupported_response_type"))
      assertEquals(d._2.get("error"), Some("invalid_scope"))
    }
  }

  test("authorize: ログインしていなければログインへ. Bearer（API キー）では許可の画面を出さない") {
    val f = new Fixture
    val bearer = Authorization(Credentials.Token(AuthScheme.Bearer, "svc_key"))
    for {
      a <- f.app.run(Request[IO](Method.GET, authorizeUri()))
      b <- f.app.run(Request[IO](Method.GET, authorizeUri()).putHeaders(bearer))
      c <- f.app.run(
        Request[IO](Method.POST, uri"/oauth/authorize").withEntity(UrlForm((authParams +
          ("decision" -> "allow")).toSeq*))
          .putHeaders(bearer)
      )
      n <- f.codes.get
    } yield {
      assertEquals(a.status, Status.Found)
      assert(location(a).startsWith("/auth/login?returnTo=%2Foauth%2Fauthorize%3F"), location(a))
      assertEquals(b.status, Status.Found)
      assert(location(b).startsWith("/auth/login?"))
      assertEquals(c.status, Status.BadRequest)
      assert(n.isEmpty)
    }
  }

  test("authorize: 画面には hidden と CSRF の Cookie. CSRF が合わなければ許可しない") {
    val f = new Fixture
    for {
      page <- f.app.run(Request[IO](Method.GET, authorizeUri()).addCookie(session))
      html <- page.as[String](using implicitly, EntityDecoder.text[IO])
      csrf = page.cookies.find(_.name == "svc_oauth_csrf").get
      bad <- f.app.run(
        Request[IO](Method.POST, uri"/oauth/authorize")
          .withEntity(UrlForm((authParams + ("csrf" -> "x") + ("decision" -> "allow")).toSeq*))
          .addCookie(session).addCookie(RequestCookie(csrf.name, csrf.content))
      )
      none <- f.app.run(
        Request[IO](Method.POST, uri"/oauth/authorize")
          .withEntity(UrlForm((authParams + ("csrf" -> csrf.content) + ("decision" -> "allow")).toSeq*))
          .addCookie(session)
      )
      n <- f.codes.get
    } yield {
      assertEquals(page.status, Status.Ok)
      assert(html.contains("<title>skea&lt;b&gt; との連携 | svc</title>"), html)
      assert(html.contains("<h1>skea&lt;b&gt;</h1><p>read,write</p>"))
      assert(html.contains("""<input type="hidden" name="state" value="st 1">"""))
      assert(html.contains(s"""<input type="hidden" name="csrf" value="${csrf.content}">"""))
      assertEquals(csrf.path, Some("/oauth"))
      assertEquals(csrf.sameSite, Some(SameSite.Strict))
      assert(csrf.httpOnly && csrf.secure)
      assertEquals(bad.status, Status.BadRequest)
      assertEquals(none.status, Status.BadRequest)
      assert(n.isEmpty)
    }
  }

  test("authorize: フォームとして読めない本文は 500 でなくエラーの画面") {
    val f = new Fixture
    f.app.run(
      Request[IO](Method.POST, uri"/oauth/authorize").withEntity(Json.obj("a" -> Json.fromInt(1))).addCookie(session)
    ).map(r => assertEquals(r.status, Status.BadRequest))
  }

  test("拒否すると access_denied で戻す") {
    val f = new Fixture
    authorize(f, "deny").map { r =>
      assertEquals(r.status, Status.Found)
      assertEquals(query(location(r)).get("error"), Some("access_denied"))
      assertEquals(query(location(r)).get("state"), Some("st 1"))
    }
  }

  test("許可 → トークン. コードは 1 回限り") {
    val f = new Fixture
    for {
      r <- authorize(f)
      q = query(location(r))
      code = q("code")
      t <- token(
        f,
        "grant_type" -> "authorization_code",
        "code" -> code,
        "redirect_uri" -> "https://skea.example/cb",
        "code_verifier" -> verifier
      )()
      j <- t.as[Json]
      again <- token(
        f,
        "grant_type" -> "authorization_code",
        "code" -> code,
        "redirect_uri" -> "https://skea.example/cb",
        "code_verifier" -> verifier
      )()
      aj <- again.as[Json]
      is <- f.issued.get
    } yield {
      assert(location(r).startsWith("https://skea.example/cb?code="), location(r))
      assertEquals(q.get("state"), Some("st 1"))
      assertEquals(t.status, Status.Ok)
      assertEquals(
        j,
        Json.obj(
          "access_token" -> Json.fromString("tok"),
          "token_type" -> Json.fromString("Bearer"),
          "scope" -> Json.fromString("read write")
        )
      )
      assertEquals(header(t, "Cache-Control"), Some("no-store"))
      assertEquals(again.status, Status.BadRequest)
      assertEquals(aj.hcursor.get[String]("error"), Right("invalid_grant"))
      assertEquals(is, List("skea" -> "read write"))
    }
  }

  test("トークン: verifier・redirect_uri が違えば invalid_grant（コードは使用済みになる）") {
    def attempt(form: (String, String)*) = {
      val f = new Fixture
      for {
        r <- authorize(f)
        code = query(location(r))("code")
        t <- token(f, (Seq("grant_type" -> "authorization_code", "code" -> code) ++ form)*)()
        j <- t.as[Json]
        is <- f.issued.get
      } yield (t.status, j.hcursor.get[String]("error").toOption, is.size)
    }
    for {
      a <- attempt("redirect_uri" -> "https://skea.example/cb", "code_verifier" -> ("x" * 43))
      b <- attempt("redirect_uri" -> "https://skea.example/other", "code_verifier" -> verifier)
      c <- attempt("redirect_uri" -> "https://skea.example/cb", "code_verifier" -> "short")
      d <- attempt("redirect_uri" -> "https://skea.example/cb")
    } yield {
      assertEquals(a, (Status.BadRequest, Some("invalid_grant"), 0))
      assertEquals(b, (Status.BadRequest, Some("invalid_grant"), 0))
      assertEquals(c, (Status.BadRequest, Some("invalid_request"), 0))
      assertEquals(d, (Status.BadRequest, Some("invalid_request"), 0))
    }
  }

  test("トークン: クライアントの認証（Basic）・grant_type・本文の形") {
    val f = new Fixture
    for {
      a <- token(f, "grant_type" -> "authorization_code")(basic(sec = "wrong-secret-0000"))
      b <- token(f, "grant_type" -> "authorization_code")(basic(id = "other"))
      c <-
        f.app.run(Request[IO](Method.POST, uri"/oauth/token").withEntity(UrlForm("grant_type" -> "authorization_code")))
      d <- token(f, "grant_type" -> "password")()
      dj <- d.as[Json]
      e <- token(f)()
      ej <- e.as[Json]
      g <- token(f, "grant_type" -> "refresh_token", "refresh_token" -> "ref")()
      gj <- g.as[Json]
      h <- f.app.run(
        Request[IO](Method.POST, uri"/oauth/token").withEntity(Json.obj("grant_type" -> Json.fromString("x")))
          .putHeaders(basic())
      )
      hj <- h.as[Json]
    } yield {
      assertEquals(List(a.status, b.status, c.status), List.fill(3)(Status.Unauthorized))
      assertEquals(header(a, "WWW-Authenticate"), Some("Basic realm=\"svc\""))
      assertEquals(dj.hcursor.get[String]("error"), Right("unsupported_grant_type"))
      assertEquals(dj.hcursor.get[String]("error_description"), Right("grant_type は authorization_code です"))
      assertEquals(ej.hcursor.get[String]("error"), Right("invalid_request"))
      // refresh に対応していないサービスでは refresh_token も unsupported_grant_type
      assertEquals(gj.hcursor.get[String]("error"), Right("unsupported_grant_type"))
      assertEquals(h.status, Status.BadRequest)
      assertEquals(hj.hcursor.get[String]("error"), Right("invalid_request"))
    }
  }

  test("refresh_token: Host.refresh があれば更新できる") {
    val f = new Fixture(refreshing = true)
    for {
      a <- token(f, "grant_type" -> "refresh_token", "refresh_token" -> "ref")()
      aj <- a.as[Json]
      b <- token(f, "grant_type" -> "refresh_token", "refresh_token" -> "old")()
      bj <- b.as[Json]
      c <- token(f, "grant_type" -> "refresh_token")()
      cj <- c.as[Json]
      d <- token(f, "grant_type" -> "password")()
      dj <- d.as[Json]
    } yield {
      assertEquals(
        aj,
        Json.obj(
          "access_token" -> Json.fromString("tok2"),
          "token_type" -> Json.fromString("Bearer"),
          "expires_in" -> Json.fromLong(3600),
          "refresh_token" -> Json.fromString("ref2"),
          "scope" -> Json.fromString("read")
        )
      )
      assertEquals(bj.hcursor.get[String]("error"), Right("invalid_grant"))
      assertEquals(cj.hcursor.get[String]("error"), Right("invalid_request"))
      assertEquals(
        dj.hcursor.get[String]("error_description"),
        Right("grant_type は authorization_code か refresh_token です")
      )
    }
  }

  test("revoke: クライアントの認証が要る. token が無くても 200") {
    val f = new Fixture
    def revoke(auth: Option[Authorization], form: (String, String)*) = {
      val r = Request[IO](Method.POST, uri"/oauth/revoke").withEntity(UrlForm(form*))
      f.app.run(auth.fold(r)(r.putHeaders(_)))
    }
    for {
      a <- revoke(None, "token" -> "t1")
      b <- revoke(Some(basic()), "token" -> "t1")
      c <- revoke(Some(basic()))
      rs <- f.revoked.get
    } yield {
      assertEquals(a.status, Status.Unauthorized)
      assertEquals(b.status, Status.Ok)
      assertEquals(c.status, Status.Ok)
      assertEquals(rs, List("t1"))
    }
  }

  test("metadata: RFC 8414 の項目") {
    OAuthProvider.metadata("https://svc.example/", List("read", "write"), refresh = true).orNotFound
      .run(Request[IO](Method.GET, uri"/.well-known/oauth-authorization-server")).flatMap(_.as[Json]).map { j =>
        assertEquals(j.hcursor.get[String]("issuer"), Right("https://svc.example"))
        assertEquals(j.hcursor.get[String]("token_endpoint"), Right("https://svc.example/oauth/token"))
        assertEquals(
          j.hcursor.get[List[String]]("grant_types_supported"),
          Right(List("authorization_code", "refresh_token"))
        )
        assertEquals(j.hcursor.get[List[String]]("code_challenge_methods_supported"), Right(List("S256")))
      }
  }
}

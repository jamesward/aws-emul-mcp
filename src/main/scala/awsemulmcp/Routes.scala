package awsemulmcp

import awsemulmcp.aws.SigV4
import awsemulmcp.emulator.*
import awsemulmcp.mcp.*
import java.nio.charset.StandardCharsets.UTF_8
import kyo.*

/** Every request lands here. A request signed with AWS credentials goes to the emulator that issued them, whatever its path (S3
  * paths are arbitrary); everything else is this server's own: the MCP endpoint, a landing page and a health check.
  */
final class Routes(config: Config, emulators: Emulators, mcp: McpEndpoint):
  import Routes.*

  def handle(request: HttpRequest[Body])(using Frame): HttpResponse[Body] < Async =
    val requestId = Maybe.fromOption(request.headers.get("X-Request-ID").toOption).map(RequestId(_))
    AppLog.requestId.let(requestId) {
      for
        start    <- Clock.now
        response <- route(request)
        end      <- Clock.now
        _ <- AppLog.info(
          "request served",
          "method"      -> request.method.name,
          "path"        -> request.path,
          "status"      -> response.status.code.toString,
          "duration_ms" -> end.minus(start).fold("0")(_.toMillis.toString),
        )
      yield response
    }

  private def route(request: HttpRequest[Body])(using Frame): HttpResponse[Body] < Async =
    SigV4.accessKeyId(Maybe.fromOption(request.headers.get("Authorization").toOption), request.url.rawQuery) match
      case Present(key) => EmulatorProxy.forward(emulators, key, request, config.emulator.requestTimeout)
      case _ =>
        (request.method, request.path) match
          case (_, "/mcp")                  => mcp.handle(request, publicUrl(request))
          case (HttpMethod.GET, "/health")  => Kyo.lift(text(HttpStatus.OK, "text/plain", "ok"))
          case (HttpMethod.GET, "/")        => Kyo.lift(text(HttpStatus.OK, "text/html; charset=utf-8", Landing.html(publicUrl(request))))
          case _ =>
            Kyo.lift(EmulatorProxy.awsError(
              HttpStatus.NotFound,
              "MissingAuthenticationToken",
              "Not found. AWS requests must be signed with the keys from emulator_create; the MCP endpoint is /mcp.",
            ))

  /** `PUBLIC_URL`, or the scheme and host the client used (Heroku's router terminates TLS and sets `X-Forwarded-Proto`). */
  private def publicUrl(request: HttpRequest[Body]): PublicUrl =
    config.publicUrl.getOrElse {
      val scheme = request.headers.get("X-Forwarded-Proto").toOption.getOrElse("http")
      val host   = request.headers.get("Host").toOption.getOrElse(s"localhost:${config.port}")
      PublicUrl(s"$scheme://$host")
    }
end Routes

object Routes:
  type Body = "body" ~ Span[Byte]

  val Methods: Chunk[HttpMethod] =
    Chunk(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.HEAD, HttpMethod.PATCH, HttpMethod.OPTIONS)

  /** One handler per method for `/` and for every other path. */
  def handlers(routes: Routes)(using Frame): Chunk[HttpHandler[?, ?, ?]] =
    Methods.flatMap { method =>
      Chunk(
        HttpRoute(method, HttpRoute.RequestDef(HttpPath.empty)),
        HttpRoute(method, HttpRoute.RequestDef("" / Capture.Rest("rest"))),
      ).map(_.request(_.bodyBinary).response(_.bodyBinary).handler(req => routes.handle(req)))
    }

  def text(status: HttpStatus, contentType: String, body: String): HttpResponse[Body] =
    HttpResponse(status).addField("body", Span.from(body.getBytes(UTF_8))).setHeader("Content-Type", contentType)
end Routes

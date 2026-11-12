package awsemulmcp.emulator

import awsemulmcp.*
import awsemulmcp.http.*
import java.nio.charset.StandardCharsets.UTF_8
import kyo.*

/** Forwards an AWS SDK/CLI request to the emulator whose access key signed it.
  *
  * The request reaches the emulator byte-for-byte as the client signed it: same method, raw path and query, `Host` header and body.
  * That lets the emulator verify the SigV4 signature itself, with `--auth on`, even though the client signed for this server's
  * public host name.
  */
object EmulatorProxy:

  type Body = "body" ~ Span[Byte]

  /** Added by the Heroku router after the client signed the request, or describe only the hop to this server. */
  private val DroppedRequestHeaders =
    Http1.HopByHop ++ Set("x-forwarded-for", "x-forwarded-proto", "x-forwarded-port", "x-request-start", "x-request-id", "via")

  /** Set by kyo-http for the response it writes. */
  private val DroppedResponseHeaders = Http1.HopByHop ++ Set("date")

  def toUpstream(request: HttpRequest[Body]): Http1.Request =
    val headers = request.headers.foldLeft(Chunk.empty[(String, String)]) { (acc, k, v) =>
      if DroppedRequestHeaders.contains(k.toLowerCase) then acc else acc :+ (k, v)
    }
    Http1.Request(request.method.name, request.url.pathWithQuery, headers, request.fields.body.toArray)

  def toDownstream(response: Http1.Response, isHead: Boolean): HttpResponse[Body] =
    // kyo-http frames every buffered response with the length of its body and writes no body for HEAD, so a HEAD answer gets a
    // placeholder body of the emulator's Content-Length (S3 HeadObject reports object sizes this way).
    val body =
      if isHead then Array.ofDim[Byte](response.header("Content-Length").flatMap(v => Maybe.fromOption(v.trim.toIntOption)).getOrElse(0))
      else response.body
    response.headers.foldLeft(HttpResponse(HttpStatus(response.status)).addField("body", Span.from(body))) {
      case (r, (k, v)) if DroppedResponseHeaders.contains(k.toLowerCase) => r
      case (r, (k, v))                                                   => r.addHeader(k, v)
    }

  def forward(emulators: Emulators, key: AccessKeyId, request: HttpRequest[Body], timeout: Duration)(using
      Frame
  ): HttpResponse[Body] < Async =
    emulators.byAccessKey(key).map {
      case Present(emulator) =>
        Abort.run[ExchangeError](emulator.exchange(toUpstream(request), timeout)).map {
          case Result.Success(response) => toDownstream(response, isHead = request.method == HttpMethod.HEAD)
          case Result.Failure(e) =>
            AppLog.error("emulator request failed", "emulator_id" -> emulator.id.value, "error" -> e.message)
              .andThen(awsError(HttpStatus.BadGateway, "ServiceUnavailable", e.message))
          case p: Result.Panic =>
            AppLog.errorCause("emulator request failed", p.exception, "emulator_id" -> emulator.id.value)
              .andThen(awsError(HttpStatus.BadGateway, "InternalFailure", "The request to the emulator failed."))
        }
      case _ =>
        AppLog.warn("no emulator for access key", "access_key_id" -> key.value)
          .andThen(awsError(
            HttpStatus.Forbidden,
            "InvalidClientTokenId",
            "No running emulator has this access key. Emulators stop when idle; create a new one with the emulator_create MCP tool.",
          ))
    }

  /** An error in the query/XML shape most AWS SDKs can surface. */
  def awsError(status: HttpStatus, code: String, message: String): HttpResponse[Body] =
    val xml =
      s"""<?xml version="1.0" encoding="UTF-8"?><ErrorResponse><Error><Type>Sender</Type><Code>$code</Code><Message>${escape(message)}</Message></Error></ErrorResponse>"""
    HttpResponse(status).addField("body", Span.from(xml.getBytes(UTF_8))).setHeader("Content-Type", "text/xml")

  private def escape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
end EmulatorProxy

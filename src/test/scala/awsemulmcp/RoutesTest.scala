package awsemulmcp

import awsemulmcp.Fixtures.*
import kyo.*

class RoutesTest extends kyo.test.Test[Any]:

  private val testConfig = appConfig("MAX_EMULATORS" -> "0", "AWS_EMULATOR_BIN" -> "/nonexistent")

  private def handle(request: HttpRequest[Routes.Body]): HttpResponse[Routes.Body] < Async =
    routes(testConfig).map((routes, _) => routes.handle(request))

  private def mcpPost(body: String): HttpResponse[Routes.Body] < Async =
    handle(request(HttpMethod.POST, "/mcp", Seq("Content-Type" -> "application/json"), body.getBytes("UTF-8")))

  "entries written while handling a request carry its request_id" in {
    captureLogs(handle(request(HttpMethod.GET, "/health", Seq("X-Request-ID" -> "req-abc-123")))).map { (response, lines) =>
      assert(response.status == HttpStatus.OK)
      assert(lines.nonEmpty)
      assert(lines.forall(_.contains(""""request_id":"req-abc-123"""")), lines.mkString("\n"))
      assert(lines.exists(l => l.contains(""""message":"request served"""") && l.contains(""""status":"200"""")), lines.mkString("\n"))
    }
  }

  "entries for a request without an id have no request_id" in {
    captureLogs(handle(request(HttpMethod.GET, "/health"))).map { (_, lines) =>
      assert(lines.nonEmpty && lines.forall(!_.contains("request_id")), lines.mkString("\n"))
    }
  }

  "signed requests with an unknown key are refused" in {
    val auth = "AWS4-HMAC-SHA256 Credential=EMULATORUNKNOWN0000/20261112/us-east-1/sqs/aws4_request, SignedHeaders=host, Signature=x"
    handle(request(HttpMethod.POST, "/", Seq("Authorization" -> auth))).map { response =>
      assert(response.status == HttpStatus.Forbidden)
      assert(bodyText(response).contains("InvalidClientTokenId"))
    }
  }

  "MCP initialize returns a session id and server info" in {
    mcpPost(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
    ).map { response =>
      assert(response.status == HttpStatus.OK)
      assert(response.headers.get("Mcp-Session-Id").isDefined)
      assert(bodyText(response).contains(""""protocolVersion":"2025-06-18""""), bodyText(response))
      assert(bodyText(response).contains("emulator_create"))
    }
  }

  "MCP notifications are accepted without a body" in {
    mcpPost("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").map(response => assert(response.status == HttpStatus.Accepted))
  }

  "MCP GET is not supported (no server-initiated stream)" in {
    handle(request(HttpMethod.GET, "/mcp")).map(response => assert(response.status == HttpStatus.MethodNotAllowed))
  }

  "emulator_create reports when emulators are disabled" in {
    mcpPost("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"emulator_create","arguments":{}}}""").map { response =>
      assert(bodyText(response).contains(""""isError":true"""), bodyText(response))
      assert(bodyText(response).contains("MAX_EMULATORS=0"), bodyText(response))
    }
  }

  "the landing page names the MCP endpoint" in {
    handle(request(HttpMethod.GET, "/", Seq("Host" -> "x.example.com", "X-Forwarded-Proto" -> "https"))).map { response =>
      assert(bodyText(response).contains("https://x.example.com/mcp"))
    }
  }
end RoutesTest

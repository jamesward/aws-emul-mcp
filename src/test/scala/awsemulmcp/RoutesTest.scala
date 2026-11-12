package awsemulmcp

import awsemulmcp.Fixtures.*
import kyo.*

class RoutesTest extends munit.FunSuite:

  private val testConfig = config("MAX_EMULATORS" -> "0", "AWS_EMULATOR_BIN" -> "/nonexistent")

  test("entries written while handling a request carry its request_id") {
    val (response, lines) = run {
      routes(testConfig).map((routes, _) =>
        captureLogs(routes.handle(request(HttpMethod.GET, "/health", Seq("X-Request-ID" -> "req-abc-123"))))
      )
    }
    assert(response.status == HttpStatus.OK)
    assert(lines.nonEmpty)
    assert(lines.forall(_.contains(""""request_id":"req-abc-123"""")), lines.mkString("\n"))
    assert(lines.exists(l => l.contains(""""message":"request served"""") && l.contains(""""status":"200"""")), lines.mkString("\n"))
  }

  test("entries outside a request id have no request_id") {
    val (_, lines) = run(routes(testConfig).map((routes, _) => captureLogs(routes.handle(request(HttpMethod.GET, "/health")))))
    assert(lines.nonEmpty && lines.forall(!_.contains("request_id")), lines.mkString("\n"))
  }

  test("signed requests with an unknown key are refused") {
    val auth     = "AWS4-HMAC-SHA256 Credential=EMULATORUNKNOWN0000/20261112/us-east-1/sqs/aws4_request, SignedHeaders=host, Signature=x"
    val response = run(routes(testConfig).map((routes, _) => routes.handle(request(HttpMethod.POST, "/", Seq("Authorization" -> auth)))))
    assert(response.status == HttpStatus.Forbidden)
    assert(bodyText(response).contains("InvalidClientTokenId"))
  }

  test("MCP initialize returns a session id and server info") {
    val body =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
    val response = run(routes(testConfig).map((routes, _) =>
      routes.handle(request(HttpMethod.POST, "/mcp", Seq("Content-Type" -> "application/json"), body.getBytes("UTF-8")))
    ))
    assert(response.status == HttpStatus.OK)
    assert(response.headers.get("Mcp-Session-Id").isDefined)
    assert(bodyText(response).contains(""""protocolVersion":"2025-06-18""""), bodyText(response))
    assert(bodyText(response).contains("emulator_create"))
  }

  test("MCP notifications are accepted without a body") {
    val body     = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
    val response = run(routes(testConfig).map((routes, _) => routes.handle(request(HttpMethod.POST, "/mcp", body = body.getBytes("UTF-8")))))
    assert(response.status == HttpStatus.Accepted)
  }

  test("emulator_create reports when emulators are disabled") {
    val body     = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"emulator_create","arguments":{}}}"""
    val response = run(routes(testConfig).map((routes, _) => routes.handle(request(HttpMethod.POST, "/mcp", body = body.getBytes("UTF-8")))))
    assert(bodyText(response).contains(""""isError":true"""), bodyText(response))
    assert(bodyText(response).contains("MAX_EMULATORS=0"), bodyText(response))
  }

  test("the landing page names the MCP endpoint") {
    val response = run(routes(testConfig).map((routes, _) =>
      routes.handle(request(HttpMethod.GET, "/", Seq("Host" -> "x.example.com", "X-Forwarded-Proto" -> "https")))
    ))
    assert(bodyText(response).contains("https://x.example.com/mcp"))
  }
end RoutesTest

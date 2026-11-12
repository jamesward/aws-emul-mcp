package awsemulmcp

import awsemulmcp.knowledge.*
import awsemulmcp.mcp.*
import kyo.*

class ProtocolTest extends munit.FunSuite:
  import AllowUnsafe.embrace.danger

  private def run[A](v: A < (Async & Abort[Any])): Result[Throwable, A] = KyoApp.Unsafe.runAndBlock(10.seconds)(v)

  test("decodes requests and notifications") {
    val req = Json.decode[RpcRequest]("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"x","arguments":{"a":1}}}""")
    assert(req.map(r => (r.id, r.method)) == Result.succeed((Present(Structure.Value.Integer(7)), "tools/call")))
    val call = req.toMaybe.flatMap(_.params).map(p => Structure.decode[CallToolParams](p).toMaybe.map(_.name))
    assert(call == Present(Present("x")))
    val note = Json.decode[RpcRequest]("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    assert(note.map(_.id) == Result.succeed(Absent))
  }

  test("string ids are echoed") {
    val ok = RpcSuccess("2.0", Structure.Value.Str("abc"), Protocol.emptyObject)
    assert(Json.encode(ok) == """{"jsonrpc":"2.0","id":"abc","result":{}}""")
  }

  // Pins the wire format of a tool result.
  test("tool results omit absent structured content") {
    assert(
      Json.encode(EmulatorTools.failure("nope")) == """{"content":[{"type":"text","text":"nope"}],"isError":true}"""
    )
  }

  test("tool input schemas are JSON Schema objects") {
    val schema = Json.encode(EmulatorTools.inputSchema[RequestInput])
    assert(schema.contains(""""type":"object""""), schema)
    assert(schema.contains(""""emulatorId""""), schema)
    assert(schema.contains("SigV4 signing name"), schema)
    assert(Json.encode(EmulatorTools.inputSchema[NoInput]).contains(""""type":"object""""))
  }

  test("protocol version negotiation") {
    assert(Protocol.negotiate(Present("2025-06-18")) == "2025-06-18")
    assert(Protocol.negotiate(Present("1999-01-01")) == Protocol.SupportedVersions.head)
    assert(Protocol.negotiate(Absent) == Protocol.SupportedVersions.head)
  }

  test("reads SSE responses from the upstream server") {
    val body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"tools\":[]}}\n\n"
    assert(KnowledgeClient.sseData(body) == Chunk("""{"jsonrpc":"2.0","id":3,"result":{"tools":[]}}"""))
    val result = run(KnowledgeClient.parseResponse("text/event-stream", body, 3))
    assert(result.map(Json.encode(_)) == Result.succeed("""{"tools":[]}"""))
  }

  test("upstream JSON-RPC errors are kept") {
    val body   = """{"jsonrpc":"2.0","id":4,"error":{"code":-32602,"message":"bad"}}"""
    val result = run(Abort.run[KnowledgeError](KnowledgeClient.parseResponse("application/json", body, 4)))
    assert(result == Result.succeed(Result.fail(KnowledgeError.Rpc(RpcError(-32602, "bad", Absent)))))
  }
end ProtocolTest

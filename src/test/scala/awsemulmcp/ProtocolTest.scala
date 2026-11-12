package awsemulmcp

import awsemulmcp.knowledge.*
import awsemulmcp.mcp.*
import kyo.*

class ProtocolTest extends kyo.test.Test[Any]:

  "decodes requests and notifications" in {
    val req = Json.decode[RpcRequest]("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"x","arguments":{"a":1}}}""")
    assert(req.map(r => (r.id, r.method)) == Result.succeed((Present(Structure.Value.Integer(7)), "tools/call")))
    val call = req.toMaybe.flatMap(_.params).map(p => Structure.decode[CallToolParams](p).toMaybe.map(_.name))
    assert(call == Present(Present("x")))
    val note = Json.decode[RpcRequest]("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    assert(note.map(_.id) == Result.succeed(Absent))
  }

  "string ids are echoed" in {
    val ok = RpcSuccess("2.0", Structure.Value.Str("abc"), Protocol.emptyObject)
    assert(Json.encode(ok) == """{"jsonrpc":"2.0","id":"abc","result":{}}""")
  }

  // Pins the wire format of a tool result.
  "tool results omit absent structured content" in {
    assert(
      Json.encode(EmulatorTools.failure("nope")) == """{"content":[{"type":"text","text":"nope"}],"isError":true}"""
    )
  }

  "tool input schemas are JSON Schema objects" in {
    val schema = Json.encode(EmulatorTools.inputSchema[RequestInput])
    assert(schema.contains(""""type":"object""""), schema)
    assert(schema.contains(""""emulatorId""""), schema)
    assert(schema.contains("SigV4 signing name"), schema)
    assert(Json.encode(EmulatorTools.inputSchema[NoInput]).contains(""""type":"object""""))
  }

  "protocol version negotiation" in {
    assert(Protocol.negotiate(Present("2025-06-18")) == "2025-06-18")
    assert(Protocol.negotiate(Present("1999-01-01")) == Protocol.SupportedVersions.head)
    assert(Protocol.negotiate(Absent) == Protocol.SupportedVersions.head)
  }

  "reads SSE responses from the upstream server" in {
    val body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"tools\":[]}}\n\n"
    assert(KnowledgeClient.sseData(body) == Chunk("""{"jsonrpc":"2.0","id":3,"result":{"tools":[]}}"""))
    KnowledgeClient.parseResponse("text/event-stream", body, 3).map(result => assert(Json.encode(result) == """{"tools":[]}"""))
  }

  "upstream JSON-RPC errors are kept" in {
    val body   = """{"jsonrpc":"2.0","id":4,"error":{"code":-32602,"message":"bad"}}"""
    Abort.run[KnowledgeError](KnowledgeClient.parseResponse("application/json", body, 4)).map { result =>
      assert(result == Result.fail(KnowledgeError.Rpc(RpcError(-32602, "bad", Absent))))
    }
  }
end ProtocolTest

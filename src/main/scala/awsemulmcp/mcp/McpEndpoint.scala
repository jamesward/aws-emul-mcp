package awsemulmcp.mcp

import awsemulmcp.*
import awsemulmcp.knowledge.*
import java.nio.charset.StandardCharsets.UTF_8
import kyo.*

/** A stateless Streamable HTTP MCP endpoint: each POST carries one JSON-RPC message and is answered with `application/json`.
  * `tools/list` and `tools/call` combine the relayed AWS Knowledge tools with [[EmulatorTools]].
  */
final class McpEndpoint(knowledge: KnowledgeClient, tools: EmulatorTools, idleTimeout: Duration):
  import McpEndpoint.*

  def handle(request: HttpRequest[Body], publicUrl: PublicUrl)(using Frame): HttpResponse[Body] < Async =
    request.method match
      case HttpMethod.POST   => post(request, publicUrl)
      case HttpMethod.DELETE => Kyo.lift(empty(HttpStatus.OK))
      case _                 => Kyo.lift(empty(HttpStatus.MethodNotAllowed).setHeader("Allow", "POST, DELETE"))

  private def post(request: HttpRequest[Body], publicUrl: PublicUrl)(using Frame): HttpResponse[Body] < Async =
    Json.decodeBytes[RpcRequest](request.fields.body) match
      case Result.Success(rpc) =>
        val session = Maybe.fromOption(request.headers.get("Mcp-Session-Id").toOption).map(SessionId(_))
        rpc.id match
          case Present(id) => dispatch(rpc, id, ToolContext(session, publicUrl))
          case _           => Kyo.lift(empty(HttpStatus.Accepted)) // notifications (initialized, cancelled, ...) need no answer
      case _ =>
        Kyo.lift(
          json(HttpStatus.BadRequest, RpcFailure("2.0", Structure.Value.Null, RpcError(RpcError.ParseError, "Expected one JSON-RPC message", Absent)))
        )

  private def dispatch(rpc: RpcRequest, id: Structure.Value, ctx: ToolContext)(using Frame): HttpResponse[Body] < Async =
    def ok[A: Schema](result: A): HttpResponse[Body] = json(HttpStatus.OK, RpcSuccess("2.0", id, Structure.encode(result)))
    def fail(code: Int, message: String): HttpResponse[Body] = json(HttpStatus.OK, RpcFailure("2.0", id, RpcError(code, message, Absent)))
    val params = rpc.params.getOrElse(Protocol.emptyObject)

    rpc.method match
      case "initialize" =>
        val version = Structure.decode[InitializeParams](params).toMaybe.flatMap(_.protocolVersion)
        newSessionId.map(s => ok(initializeResult(Protocol.negotiate(version), idleTimeout)).setHeader("Mcp-Session-Id", s.value))
      case "ping" => Kyo.lift(ok(Protocol.emptyObject))
      case "tools/list" =>
        upstreamTools.map(up => ok(ListToolsResult(tools.definitions.map(Structure.encode(_)) ++ up)))
      case "tools/call" =>
        Structure.decode[CallToolParams](params) match
          case Result.Success(call) if tools.names.contains(call.name) =>
            tools.call(call.name, call.arguments.getOrElse(Protocol.emptyObject), ctx).map(ok(_))
          case Result.Success(call) =>
            Abort.run[KnowledgeError](knowledge.callTool(params)).map {
              case Result.Success(result) => json(HttpStatus.OK, RpcSuccess("2.0", id, result))
              case Result.Failure(KnowledgeError.Rpc(e)) => json(HttpStatus.OK, RpcFailure("2.0", id, e))
              case Result.Failure(e) =>
                AppLog.warn("AWS Knowledge tool call failed", "tool" -> call.name, "error" -> e.message)
                  .andThen(fail(RpcError.InternalError, e.message))
              case p: Result.Panic =>
                AppLog.errorCause("AWS Knowledge tool call failed", p.exception, "tool" -> call.name)
                  .andThen(fail(RpcError.InternalError, "The AWS Knowledge tool call failed."))
            }
          case _ => Kyo.lift(fail(RpcError.InvalidParams, "tools/call needs a tool name"))
      case other => Kyo.lift(fail(RpcError.MethodNotFound, s"Method not found: $other"))
    end match
  end dispatch

  /** The relayed tools; when the upstream server is unreachable the emulator tools are still offered. */
  private def upstreamTools(using Frame): Chunk[Structure.Value] < Async =
    Abort.run[KnowledgeError](knowledge.tools).map {
      case Result.Success(ts) => ts
      case Result.Failure(e)  => AppLog.warn("AWS Knowledge tools unavailable", "error" -> e.message).andThen(Chunk.empty)
      case p: Result.Panic    => AppLog.errorCause("AWS Knowledge tools unavailable", p.exception).andThen(Chunk.empty)
    }
end McpEndpoint

object McpEndpoint:
  type Body = "body" ~ Span[Byte]

  def initializeResult(version: String, idleTimeout: Duration): InitializeResult =
    InitializeResult(
      version,
      ServerCapabilities(ToolsCapability(listChanged = false)),
      Implementation(AppInfo.Name, AppInfo.Title, AppInfo.Version),
      s"""Tools for building on AWS without an AWS account or anything installed locally.
         |- AWS documentation: the aws___* tools search and read AWS docs and check regional availability (relayed from the AWS Knowledge MCP server).
         |- AWS Emulator: ${EmulatorTools.Create} starts a private in-memory AWS Emulator (account ${AppInfo.AccountId}, region ${AppInfo.Region}).
         |  Call AWS APIs on it with ${EmulatorTools.Request}, or run the AWS CLI/SDKs against the returned endpoint and keys.
         |  Emulators stop after ${idleTimeout.toMinutes} minutes without API calls; ${EmulatorTools.Delete} stops one sooner.
         |The emulator is for building and testing, not production; it does not enforce IAM policies the way AWS does.""".stripMargin,
    )

  def newSessionId(using Frame): SessionId < Sync =
    SecureRandom.nextBytes(16).map(b => SessionId(java.util.HexFormat.of().formatHex(b.toArray)))

  def json[A: Schema](status: HttpStatus, value: A)(using Frame): HttpResponse[Body] =
    HttpResponse(status).addField("body", Json.encodeBytes(value)).setHeader("Content-Type", "application/json")

  def empty(status: HttpStatus): HttpResponse[Body] = HttpResponse(status).addField("body", Span.from("".getBytes(UTF_8)))
end McpEndpoint

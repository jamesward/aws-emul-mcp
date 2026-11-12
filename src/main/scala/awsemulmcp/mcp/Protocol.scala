package awsemulmcp.mcp

import kyo.*

/** JSON-RPC 2.0 envelopes. `id`, `params` and `result` are kept as `Structure.Value`: the server echoes ids it did not choose and
  * relays tool definitions and results from the upstream AWS Knowledge server without knowing their shapes.
  */
final case class RpcRequest(
    jsonrpc: String,
    id: Maybe[Structure.Value],
    method: String,
    params: Maybe[Structure.Value],
) derives Schema

final case class RpcError(code: Int, message: String, data: Maybe[Structure.Value]) derives Schema, CanEqual

object RpcError:
  val ParseError     = -32700
  val InvalidRequest = -32600
  val MethodNotFound = -32601
  val InvalidParams  = -32602
  val InternalError  = -32603

final case class RpcSuccess(jsonrpc: String, id: Structure.Value, result: Structure.Value) derives Schema
final case class RpcFailure(jsonrpc: String, id: Structure.Value, error: RpcError) derives Schema

/** A response read from the upstream server: exactly one of `result` and `error` is set. */
final case class RpcResponse(
    jsonrpc: String,
    id: Maybe[Structure.Value],
    result: Maybe[Structure.Value],
    error: Maybe[RpcError],
) derives Schema

// --- MCP messages (only the fields this server reads or writes) ---

final case class InitializeParams(protocolVersion: Maybe[String]) derives Schema

final case class Implementation(name: String, title: String, version: String) derives Schema
final case class ToolsCapability(listChanged: Boolean) derives Schema
final case class ServerCapabilities(tools: ToolsCapability) derives Schema
final case class InitializeResult(
    protocolVersion: String,
    capabilities: ServerCapabilities,
    serverInfo: Implementation,
    instructions: String,
) derives Schema

final case class ToolAnnotations(
    title: String,
    readOnlyHint: Boolean,
    destructiveHint: Boolean,
    idempotentHint: Boolean,
    openWorldHint: Boolean,
) derives Schema

final case class ToolDefinition(
    name: String,
    title: String,
    description: String,
    inputSchema: Structure.Value,
    annotations: ToolAnnotations,
) derives Schema

final case class ListToolsResult(tools: Chunk[Structure.Value]) derives Schema

final case class CallToolParams(name: String, arguments: Maybe[Structure.Value]) derives Schema

final case class TextContent(`type`: String, text: String) derives Schema
object TextContent:
  def apply(text: String): TextContent = TextContent("text", text)

final case class CallToolResult(content: Chunk[TextContent], structuredContent: Maybe[Structure.Value], isError: Boolean)
    derives Schema

object Protocol:
  /** Newest first; the first is offered when a client asks for a version this server does not know. */
  val SupportedVersions: Chunk[String] = Chunk("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

  def negotiate(requested: Maybe[String]): String =
    requested.filter(SupportedVersions.contains).getOrElse(SupportedVersions.head)

  val emptyObject: Structure.Value = Structure.Value.Record(Chunk.empty)

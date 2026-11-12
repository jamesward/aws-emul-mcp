package awsemulmcp.knowledge

import awsemulmcp.*
import awsemulmcp.mcp.*
import kyo.*

enum KnowledgeError derives CanEqual:
  case Transport(detail: String)
  case Status(code: Int, body: String)
  case Decode(detail: String)
  case Rpc(error: RpcError)

  def message: String = this match
    case Transport(detail)  => s"could not reach the AWS Knowledge MCP server: $detail"
    case Status(code, body) => s"the AWS Knowledge MCP server answered HTTP $code: ${body.take(300)}"
    case Decode(detail)     => s"unexpected response from the AWS Knowledge MCP server: $detail"
    case Rpc(error)         => s"the AWS Knowledge MCP server returned error ${error.code}: ${error.message}"
end KnowledgeError

/** A Streamable HTTP MCP client for the remote AWS Knowledge server, shared by every session of this server. Its tools are
  * relayed unchanged.
  */
final class KnowledgeClient private (
    url: String,
    session: AtomicRef[Maybe[KnowledgeClient.Session]],
    toolsCache: AtomicRef[Maybe[KnowledgeClient.CachedTools]],
    ids: AtomicLong,
):
  import KnowledgeClient.*

  def tools(using Frame): Chunk[Structure.Value] < (Async & Abort[KnowledgeError]) =
    for
      now    <- Clock.now
      cached <- toolsCache.get
      tools <- cached.filter(c => now.minus(c.fetchedAt).exists(_ < ToolsTtl)) match
        case Present(c) => Kyo.lift(c.tools)
        case _ =>
          call("tools/list", Protocol.emptyObject).map { result =>
            decode[ListToolsResult](result).map { r =>
              toolsCache.set(Present(CachedTools(now, r.tools))).andThen(r.tools)
            }
          }
    yield tools

  /** Relays a `tools/call`; the upstream result is returned as is. */
  def callTool(params: Structure.Value)(using Frame): Structure.Value < (Async & Abort[KnowledgeError]) =
    call("tools/call", params)

  private def call(method: String, params: Structure.Value)(using Frame): Structure.Value < (Async & Abort[KnowledgeError]) =
    currentSession.map { s =>
      Abort.run[KnowledgeError](rpc(Present(s), method, params)).map {
        // The upstream session expired (404 per the Streamable HTTP spec): start a new one and retry once.
        case Result.Failure(KnowledgeError.Status(404, _)) =>
          session.set(Absent).andThen(currentSession.map(fresh => rpc(Present(fresh), method, params)))
        case other => Abort.get(other)
      }
    }

  private def currentSession(using Frame): Session < (Async & Abort[KnowledgeError]) =
    session.get.map {
      case Present(s) => Kyo.lift(s)
      case _          => initialize.map(s => session.set(Present(s)).andThen(s))
    }

  private def initialize(using Frame): Session < (Async & Abort[KnowledgeError]) =
    val params = Structure.encode(UpstreamInitialize(Protocol.SupportedVersions.head, Protocol.emptyObject, ClientInfo))
    for
      response <- post(Absent, Protocol.SupportedVersions.head, "initialize", Present(params), notification = false)
      result   <- response.body
      init     <- decode[InitializeParams](result)
      s = Session(response.sessionId, init.protocolVersion.getOrElse(Protocol.SupportedVersions.head))
      _ <- post(Present(s), s.protocolVersion, "notifications/initialized", Absent, notification = true)
      _ <- AppLog.info("connected to the AWS Knowledge MCP server", "protocol_version" -> s.protocolVersion)
    yield s
    end for
  end initialize

  private def rpc(s: Maybe[Session], method: String, params: Structure.Value)(using
      Frame
  ): Structure.Value < (Async & Abort[KnowledgeError]) =
    post(s, s.map(_.protocolVersion).getOrElse(Protocol.SupportedVersions.head), method, Present(params), notification = false)
      .map(_.body)

  private final case class Posted(sessionId: Maybe[String], body: Structure.Value < Abort[KnowledgeError])

  private def post(s: Maybe[Session], version: String, method: String, params: Maybe[Structure.Value], notification: Boolean)(using
      Frame
  ): Posted < (Async & Abort[KnowledgeError]) =
    for
      id <- ids.incrementAndGet
      request = RpcRequest("2.0", if notification then Absent else Present(Structure.Value.Integer(id)), method, params)
      headers = Seq(
        "Content-Type"         -> "application/json",
        "Accept"               -> "application/json, text/event-stream",
        "MCP-Protocol-Version" -> version,
      ) ++ s.flatMap(_.id).map("Mcp-Session-Id" -> _).toList
      response <- Abort.recover[HttpException](e => Abort.fail(KnowledgeError.Transport(e.getMessage)))(
        HttpClient.withConfig(_.timeout(UpstreamTimeout))(
          HttpClient.postTextResponse(url, Json.encode(request), headers, failOnError = false)
        )
      )
      status = response.status.code
      _ <- Abort.when(status < 200 || status >= 300)(KnowledgeError.Status(status, response.fields.body))
    yield Posted(
      Maybe.fromOption(response.headers.get("Mcp-Session-Id").toOption),
      if notification then Kyo.lift(Protocol.emptyObject)
      else parseResponse(response.headers.get("Content-Type").getOrElse(""), response.fields.body, id),
    )

  private def decode[A: Schema](value: Structure.Value)(using Frame): A < Abort[KnowledgeError] =
    Structure.decode[A](value) match
      case Result.Success(a) => Kyo.lift(a)
      case Result.Failure(e) => Abort.fail(KnowledgeError.Decode(e.getMessage))
      case p: Result.Panic   => Abort.fail(KnowledgeError.Decode(p.exception.getMessage))
end KnowledgeClient

object KnowledgeClient:
  final case class Session(id: Maybe[String], protocolVersion: String)
  final case class CachedTools(fetchedAt: Instant, tools: Chunk[Structure.Value])

  final case class UpstreamClientInfo(name: String, version: String) derives Schema
  final case class UpstreamInitialize(protocolVersion: String, capabilities: Structure.Value, clientInfo: UpstreamClientInfo)
      derives Schema

  val ClientInfo      = UpstreamClientInfo("aws-emul-mcp", AppInfo.Version)
  val ToolsTtl        = 10.minutes
  val UpstreamTimeout = 60.seconds

  def init(url: String)(using Frame): KnowledgeClient < Sync =
    for
      session <- AtomicRef.init(Maybe.empty[Session])
      tools   <- AtomicRef.init(Maybe.empty[CachedTools])
      ids     <- AtomicLong.init(0L)
    yield new KnowledgeClient(url, session, tools, ids)

  /** The JSON-RPC response with id `id`, from a JSON body or from the `data:` lines of a `text/event-stream` body. */
  def parseResponse(contentType: String, body: String, id: Long)(using Frame): Structure.Value < Abort[KnowledgeError] =
    val candidates =
      if contentType.toLowerCase.startsWith("text/event-stream") then sseData(body)
      else Chunk(body)
    val matching = candidates.iterator.flatMap(c => Json.decode[RpcResponse](c).toMaybe.toOption).find(_.id.exists {
      case Structure.Value.Integer(n) => n == id
      case Structure.Value.Str(s)     => s == id.toString
      case _                          => false
    })
    matching match
      case Some(r) =>
        r.error match
          case Present(e) => Abort.fail(KnowledgeError.Rpc(e))
          case _ =>
            r.result match
              case Present(result) => Kyo.lift(result)
              case _               => Abort.fail(KnowledgeError.Decode("a response with neither result nor error"))
      case None => Abort.fail(KnowledgeError.Decode(s"no JSON-RPC response with id $id in: ${body.take(300)}"))
    end match
  end parseResponse

  /** The data of each event in a `text/event-stream` body (multi-line data joined with `\n`). */
  def sseData(body: String): Chunk[String] =
    Chunk.from(
      body.replace("\r\n", "\n").split("\n\n").iterator.map { event =>
        event.linesIterator.filter(_.startsWith("data:")).map(_.stripPrefix("data:").stripPrefix(" ")).mkString("\n")
      }.filter(_.nonEmpty).toSeq
    )
end KnowledgeClient

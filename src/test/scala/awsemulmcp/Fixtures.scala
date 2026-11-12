package awsemulmcp

import awsemulmcp.emulator.*
import awsemulmcp.knowledge.*
import awsemulmcp.mcp.*
import java.nio.charset.StandardCharsets.UTF_8
import kyo.*

object Fixtures:

  def appConfig(vars: (String, String)*): Config =
    Config.fromEnv(name => Maybe.fromOption(vars.toMap.get(name).orElse(sys.env.get(name)))) match
      case Result.Success(c) => c
      case other             => throw IllegalArgumentException(s"bad test config: $other")

  def routes(config: Config)(using Frame): (Routes, Emulators) < Sync =
    for
      emulators <- Emulators.init(config.emulator)
      knowledge <- KnowledgeClient.init(config.knowledgeMcpUrl)
    yield (Routes(config, emulators, McpEndpoint(knowledge, EmulatorTools(emulators, config.emulator), config.emulator.idleTimeout)), emulators)

  def request(
      method: HttpMethod,
      pathWithQuery: String,
      headers: Seq[(String, String)] = Seq.empty,
      body: Array[Byte] = Array.emptyByteArray,
  ): HttpRequest[Routes.Body] =
    val (path, query) = pathWithQuery.split("\\?", 2) match
      case Array(p, q) => (p, Present(q))
      case Array(p)    => (p, Absent)
      case _           => (pathWithQuery, Absent)
    HttpRequest(method, HttpUrl(Absent, "", 0, path, query), headers.foldLeft(HttpHeaders.empty)((h, kv) => h.add(kv._1, kv._2)), Record.empty)
      .addField("body", Span.from(body))

  def bodyText(response: HttpResponse[Routes.Body]): String = String(response.fields.body.toArray, UTF_8)

  /** Collects AppLog lines while `v` runs. */
  def captureLogs[A, S](v: A < (S & Sync))(using Frame): (A, Chunk[String]) < (S & Sync) =
    AtomicRef.init(Chunk.empty[String]).map { lines =>
      AppLog.sink.let(line => lines.updateAndGet(_ :+ line).unit)(v).map(a => lines.get.map(ls => (a, ls)))
    }
end Fixtures

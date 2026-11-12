package awsemulmcp

import awsemulmcp.emulator.*
import awsemulmcp.knowledge.*
import awsemulmcp.mcp.*
import kyo.*

object App extends KyoApp:

  private def env(name: String): Maybe[String] = Maybe.fromOption(sys.env.get(name))

  run {
    Log.let(Log(AppLog.JsonLogger("kyo", Log.Level.info))) {
      Config.fromEnv(env) match
        case Result.Success(config) => serve(config)
        case Result.Failure(invalid) =>
          AppLog.error("invalid configuration", "error" -> invalid.message).andThen(Abort.fail(invalid.message))
        case p: Result.Panic => Abort.get(p)
    }
  }

  def serve(config: Config)(using Frame): Unit < (Async & Scope & Abort[HttpBindException]) =
    for
      emulators <- Emulators.init(config.emulator)
      knowledge <- KnowledgeClient.init(config.knowledgeMcpUrl)
      mcp    = McpEndpoint(knowledge, EmulatorTools(emulators, config.emulator), config.emulator.idleTimeout)
      routes = Routes(config, emulators, mcp)
      server <- HttpServer.init(
        HttpServerConfig.default.port(config.port).host(config.host).maxContentLength(config.maxRequestBytes)
      )(Routes.handlers(routes)*)
      _ <- AppLog.info(
        "listening",
        "port"          -> server.port.toString,
        "max_emulators" -> config.emulator.maxEmulators.toString,
        "emulator_bin"  -> config.emulator.binary,
      )
      _ <- server.await
    yield ()
end App

package awsemulmcp

import kyo.*

enum Telemetry derives CanEqual:
  case On, Off

  def flag: String = this match
    case On  => "on"
    case Off => "off"

final case class EmulatorConfig(
    binary: String,
    homeRoot: String,
    maxEmulators: Int,
    idleTimeout: Duration,
    startupTimeout: Duration,
    requestTimeout: Duration,
    telemetry: Telemetry,
)

final case class Config(
    port: Int,
    host: String,
    publicUrl: Maybe[PublicUrl],
    knowledgeMcpUrl: String,
    maxRequestBytes: Int,
    emulator: EmulatorConfig,
)

object Config:

  final case class Invalid(name: String, value: String, expected: String):
    def message: String = s"$name=$value is invalid: expected $expected"

  /** Reads configuration from environment variables (see README for each one). */
  def fromEnv(env: String => Maybe[String]): Result[Invalid, Config] =
    def int(name: String, default: Int, min: Int): Result[Invalid, Int] =
      env(name) match
        case Absent => Result.succeed(default)
        case Present(v) =>
          v.trim.toIntOption.filter(_ >= min) match
            case Some(n) => Result.succeed(n)
            case None    => Result.fail(Invalid(name, v, s"an integer >= $min"))

    def telemetry: Result[Invalid, Telemetry] =
      env("AWS_EMULATOR_TELEMETRY").map(_.trim.toLowerCase) match
        case Present("on")    => Result.succeed(Telemetry.On)
        case Present("off")   => Result.succeed(Telemetry.Off)
        case Present(other)   => Result.fail(Invalid("AWS_EMULATOR_TELEMETRY", other, "on or off"))
        case _                => Result.succeed(Telemetry.Off)

    for
      port        <- int("PORT", 8080, 1)
      max         <- int("MAX_EMULATORS", 2, 0)
      idle        <- int("EMULATOR_IDLE_TIMEOUT_SECONDS", 1800, 1)
      maxRequestMb <- int("MAX_REQUEST_MB", 64, 1)
      tel         <- telemetry
    yield Config(
      port = port,
      host = env("HOST").getOrElse("0.0.0.0"),
      publicUrl = env("PUBLIC_URL").filter(_.nonEmpty).map(PublicUrl(_)),
      knowledgeMcpUrl = env("KNOWLEDGE_MCP_URL").getOrElse("https://knowledge-mcp.global.api.aws"),
      maxRequestBytes = maxRequestMb * 1024 * 1024,
      emulator = EmulatorConfig(
        binary = env("AWS_EMULATOR_BIN").getOrElse("aws-emulator"),
        homeRoot = env("EMULATOR_HOME_ROOT").getOrElse("/tmp/aws-emulators"),
        maxEmulators = max,
        idleTimeout = idle.seconds,
        startupTimeout = 90.seconds,
        requestTimeout = 60.seconds,
        telemetry = tel,
      ),
    )
  end fromEnv
end Config

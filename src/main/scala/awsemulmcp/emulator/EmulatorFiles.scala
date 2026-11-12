package awsemulmcp.emulator

import awsemulmcp.*
import kyo.*

/** Parsers for what an `aws-emulator` process writes: its JSON-lines stdout and its per-port credentials file. */
object EmulatorFiles:

  final case class StartupFields(message: String, endpointUrl: Maybe[String], caFile: Maybe[String]) derives CanEqual
  object StartupFields:
    given Schema[StartupFields] = Schema.derived[StartupFields].renameAllFields(Schema.NameCase.SnakeCase)

  final case class LogLine(fields: StartupFields) derives Schema, CanEqual

  final case class Startup(port: Int, caFile: String) derives CanEqual

  val StartupMessage = "emulator server information"

  /** Finds the "emulator server information" line, which names the port (the emulator is started with `--port 0`) and its CA. */
  def startup(stdout: String): Maybe[Startup] =
    Maybe.fromOption(stdout.linesIterator.map(_.trim).filter(_.startsWith("{")).flatMap { line =>
      Json.decode[LogLine](line).toMaybe.toOption
    }.collectFirst {
      case LogLine(StartupFields(StartupMessage, Present(url), Present(ca))) => port(url).map(Startup(_, ca))
    }.flatten)

  private def port(endpointUrl: String): Option[Int] =
    endpointUrl.reverse.takeWhile(_ != ':').reverse.toIntOption

  /** The `[default]` profile of `<home>/.aws/emulator/<port>/emulator_credentials`. */
  def credentials(ini: String): Maybe[Credentials] =
    val values = ini.linesIterator.map(_.trim).flatMap { line =>
      line.split("=", 2) match
        case Array(k, v) => Some(k.trim -> v.trim)
        case _           => None
    }.toMap
    (values.get("aws_access_key_id"), values.get("aws_secret_access_key")) match
      case (Some(id), Some(secret)) if id.nonEmpty && secret.nonEmpty =>
        Present(Credentials(AccessKeyId(id), SecretAccessKey(secret)))
      case _ => Absent
  end credentials
end EmulatorFiles

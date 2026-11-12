package awsemulmcp.emulator

import awsemulmcp.*
import awsemulmcp.http.*
import kyo.*

/** A running `aws-emulator` child process: one AWS account, in memory, listening on 127.0.0.1. */
final case class Emulator(
    id: EmulatorId,
    owner: Maybe[SessionId],
    port: Int,
    caFile: String,
    credentials: Credentials,
    createdAt: Instant,
    process: Process,
):
  def exchange(request: Http1.Request, timeout: Duration)(using Frame): Http1.Response < (Async & Abort[ExchangeError]) =
    EmulatorConnection.exchange(port, caFile, request, timeout)

enum EmulatorError derives CanEqual:
  case Disabled
  case CapacityReached(max: Int)
  case LaunchFailed(detail: String)
  case ExitedDuringStartup(exitCode: Int)
  case StartupTimedOut
  case BadStartup(detail: String)

  def message: String = this match
    case Disabled                  => "Creating emulators is disabled on this server (MAX_EMULATORS=0)."
    case CapacityReached(max)      => s"This server is already running its maximum of $max emulators. Delete one with emulator_delete or try again later; idle emulators stop on their own."
    case LaunchFailed(detail)      => s"The emulator could not be started: $detail"
    case ExitedDuringStartup(code) => s"The emulator exited during startup (exit code $code)."
    case StartupTimedOut           => "The emulator did not finish starting in time."
    case BadStartup(detail)        => s"The emulator started but could not be used: $detail"
end EmulatorError

final class Emulators private (config: EmulatorConfig, state: AtomicRef[Map[EmulatorId, Emulators.Slot]]):
  import Emulators.Slot

  def max: Int = config.maxEmulators

  def list(owner: SessionId)(using Frame): Chunk[Emulator] < Sync =
    running.map(_.filter(_.owner.contains(owner)))

  def get(id: EmulatorId)(using Frame): Maybe[Emulator] < Sync =
    state.get.map(_.get(id) match
      case Some(Slot.Running(e)) => Present(e)
      case _                     => Absent)

  def byAccessKey(key: AccessKeyId)(using Frame): Maybe[Emulator] < Sync =
    running.map(es => Maybe.fromOption(es.find(_.credentials.accessKeyId == key)))

  def count(using Frame): Int < Sync = state.get.map(_.size)

  private def running(using Frame): Chunk[Emulator] < Sync =
    state.get.map(m => Chunk.from(m.values.collect { case Slot.Running(e) => e }).sortBy(_.createdAt))

  def create(owner: Maybe[SessionId])(using Frame): Emulator < (Async & Abort[EmulatorError]) =
    if config.maxEmulators == 0 then Abort.fail(EmulatorError.Disabled)
    else
      for
        id       <- Emulators.newId
        previous <- state.getAndUpdate(m => if m.size < config.maxEmulators then m.updated(id, Slot.Starting) else m)
        _        <- Abort.when(previous.size >= config.maxEmulators)(EmulatorError.CapacityReached(config.maxEmulators))
        emulator <- Abort.run[EmulatorError](start(id, owner)).map {
          case Result.Success(e) => Kyo.lift(e)
          case Result.Failure(err) =>
            state.updateAndGet(_ - id).andThen(removeHome(id)).unit.andThen(Abort.fail(err))
          case p: Result.Panic => state.updateAndGet(_ - id).unit.andThen(Abort.get(p))
        }
      yield emulator

  /** Stops an emulator. Its resources disappear with it: the emulator keeps everything in memory. */
  def delete(id: EmulatorId)(using Frame): Boolean < Sync =
    state.getAndUpdate(_ - id).map(_.get(id) match
      case Some(Slot.Running(e)) => e.process.destroy.andThen(AppLog.info("emulator deleted", "emulator_id" -> id.value)).andThen(true)
      case _                     => false)

  private def home(id: EmulatorId)(using Frame): Path = Path(config.homeRoot, id.value)

  private def start(id: EmulatorId, owner: Maybe[SessionId])(using Frame): Emulator < (Async & Abort[EmulatorError]) =
    val dir     = home(id)
    val logFile = dir / "emulator.log"
    val command = Command(
      config.binary,
      "--bind", "127.0.0.1",
      "--port", "0",
      "--auth", "on",
      "--telemetry", config.telemetry.flag,
      "--idle-timeout", config.idleTimeout.toSeconds.toString,
    ).envAppend(Map("HOME" -> dir.toString)).stdoutToFile(logFile).redirectErrorStream(true)
    for
      _ <- fileOp(s"create $dir")(dir.mkDir)
      process <- Abort.recover[CommandException](e => Abort.fail(EmulatorError.LaunchFailed(e.getMessage)))(command.spawnUnscoped)
      emulator <- Abort.run[EmulatorError](started(id, owner, dir, logFile, process)).map {
        case Result.Success(e)   => Kyo.lift(e)
        case Result.Failure(err) => process.destroy.andThen(Abort.fail(err))
        case p: Result.Panic     => process.destroy.andThen(Abort.get(p))
      }
    yield emulator
    end for
  end start

  private def started(id: EmulatorId, owner: Maybe[SessionId], dir: Path, logFile: Path, process: Process)(using
      Frame
  ): Emulator < (Async & Abort[EmulatorError]) =
    for
      info <- Abort.recover[Timeout](_ => Abort.fail(EmulatorError.StartupTimedOut))(
        Async.timeout(config.startupTimeout)(awaitStartup(logFile, process))
      )
      credsText <- fileOp("read emulator credentials")(
        (dir / ".aws" / "emulator" / info.port.toString / "emulator_credentials").read
      )
      creds <- EmulatorFiles.credentials(credsText) match
        case Present(c) => Kyo.lift(c)
        case Absent     => Abort.fail(EmulatorError.BadStartup("its credentials file has no access key"))
      health <- Abort.recover[ExchangeError](e => Abort.fail(EmulatorError.BadStartup(e.message)))(
        EmulatorConnection.exchange(info.port, info.caFile, Emulators.healthRequest(info.port), 10.seconds)
      )
      _   <- Abort.when(health.status != 200)(EmulatorError.BadStartup(s"health check returned ${health.status}"))
      now <- Clock.now
      emulator = Emulator(id, owner, info.port, info.caFile, creds, now, process)
      _ <- state.updateAndGet(_.updated(id, Slot.Running(emulator)))
      _ <- Fiber.initUnscoped(watch(emulator))
      _ <- AppLog.info("emulator started", "emulator_id" -> id.value, "port" -> info.port.toString)
    yield emulator

  /** Polls the emulator's stdout file for its startup line, failing early if the process exits. */
  private def awaitStartup(logFile: Path, process: Process)(using Frame): EmulatorFiles.Startup < (Async & Abort[EmulatorError]) =
    Loop.foreach {
      for
        text <- fileOp("read emulator output")(logFile.exists.map(e => if e then logFile.read else ""))
        exit <- process.exitCode
        step <- EmulatorFiles.startup(text) match
          case Present(s) => Loop.done[Unit, EmulatorFiles.Startup](s)
          case _ =>
            exit match
              case Present(code) => Abort.fail(EmulatorError.ExitedDuringStartup(code.toInt))
              case _             => Async.sleep(200.millis).andThen(Loop.continue[EmulatorFiles.Startup])
      yield step
    }

  /** Forgets an emulator once its process ends: deleted, stopped by `--idle-timeout`, or crashed. */
  private def watch(emulator: Emulator)(using Frame): Unit < Async =
    for
      code <- emulator.process.waitFor
      _    <- state.updateAndGet(_ - emulator.id)
      _    <- AppLog.info("emulator exited", "emulator_id" -> emulator.id.value, "exit_code" -> code.toInt.toString)
      _    <- removeHome(emulator.id)
    yield ()

  private def removeHome(id: EmulatorId)(using Frame): Unit < Sync =
    Abort.run[FileSystemException](Path.run(home(id).removeAll)).map {
      case Result.Success(_) => ()
      case Result.Failure(e) => AppLog.warn("could not remove emulator home", "emulator_id" -> id.value, "error" -> e.getMessage)
      case p: Result.Panic   => AppLog.warn("could not remove emulator home", "emulator_id" -> id.value, "error" -> p.toString)
    }

  private def fileOp[A](what: String)(op: A < PathWrite)(using Frame): A < (Sync & Abort[EmulatorError]) =
    Abort.recover[FileSystemException](e => Abort.fail(EmulatorError.LaunchFailed(s"could not $what: ${e.getMessage}")))(Path.run(op))
end Emulators

object Emulators:
  enum Slot:
    case Starting
    case Running(emulator: Emulator)

  def init(config: EmulatorConfig)(using Frame): Emulators < Sync =
    AtomicRef.init(Map.empty[EmulatorId, Slot]).map(new Emulators(config, _))

  /** 128 random bits: the id is also the capability that lets an MCP client use or delete the emulator. */
  def newId(using Frame): EmulatorId < Sync =
    SecureRandom.nextBytes(16).map(b => EmulatorId("emu-" + java.util.HexFormat.of().formatHex(b.toArray)))

  def healthRequest(port: Int): Http1.Request =
    Http1.Request("GET", "/__admin/health", Chunk("Host" -> s"127.0.0.1:$port"), Array.emptyByteArray)
end Emulators

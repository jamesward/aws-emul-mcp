package awsemulmcp

import kyo.*

/** One JSON object per line on stdout, with the router's `X-Request-ID` as `request_id` on every entry written while a request is
  * handled.
  */
object AppLog:

  enum Level derives CanEqual:
    case Debug, Info, Warn, Error

    def wire: String = this match
      case Debug => "DEBUG"
      case Info  => "INFO"
      case Warn  => "WARN"
      case Error => "ERROR"
  end Level

  final case class Entry(
      timestamp: String,
      level: String,
      logger: String,
      message: String,
      requestId: Maybe[String],
      cause: Maybe[String],
  ) derives CanEqual

  given entrySchema: Schema[Entry] = Schema.derived[Entry].renameAllFields(Schema.NameCase.SnakeCase)

  /** Renders an entry as a single JSON line. `fields` are lifted to top-level keys so each annotation is its own field. */
  def render(entry: Entry, fields: Seq[(String, String)]): String =
    val extra = Chunk.from(fields.map((k, v) => (k, Structure.Value.Str(v))))
    val value = Structure.encode(entry)(using entrySchema) match
      case Structure.Value.Record(fs) => Structure.Value.Record(fs ++ extra)
      case other                      => other
    Json.encode(value)

  val requestId: Local[Maybe[RequestId]] = Local.init(Absent)

  /** Where entries go; tests replace it to capture output. */
  val sink: Local[String => Unit < Sync] = Local.init(line => Sync.defer(scala.Console.out.println(line)))

  def log(level: Level, message: String, cause: Maybe[Throwable], fields: (String, String)*)(using Frame): Unit < Sync =
    for
      rid  <- requestId.get
      out  <- sink.get
      now  <- Clock.now
      entry = Entry(now.toJava.toString, level.wire, "aws-emul-mcp", message, rid.map(_.value), cause.map(stackTrace))
      _    <- out(render(entry, fields))
    yield ()

  def info(message: String, fields: (String, String)*)(using Frame): Unit < Sync  = log(Level.Info, message, Absent, fields*)
  def warn(message: String, fields: (String, String)*)(using Frame): Unit < Sync  = log(Level.Warn, message, Absent, fields*)
  def error(message: String, fields: (String, String)*)(using Frame): Unit < Sync = log(Level.Error, message, Absent, fields*)
  def errorCause(message: String, cause: Throwable, fields: (String, String)*)(using Frame): Unit < Sync =
    log(Level.Error, message, Present(cause), fields*)

  private def stackTrace(t: Throwable): String =
    val sw = java.io.StringWriter()
    t.printStackTrace(java.io.PrintWriter(sw))
    sw.toString

  /** Kyo's own logs (and anything else using `kyo.Log`) in the same JSON format, at INFO and above. */
  final case class JsonLogger(name: String, level: kyo.Log.Level) extends kyo.Log.Unsafe:
    def withName(name: String): kyo.Log.Unsafe = copy(name = name)

    private def write(lvl: kyo.Log.Level, msg: => String, t: Maybe[Throwable]): Unit =
      if lvl.enabled(level) then
        val entry = Entry(java.time.Instant.now().toString, lvl.toString.toUpperCase, name, msg, Absent, t.map(stackTrace))
        scala.Console.out.println(render(entry, Seq.empty))

    def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                   = write(kyo.Log.Level.trace, msg, Absent)
    def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = write(kyo.Log.Level.trace, msg, Present(t))
    def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                   = write(kyo.Log.Level.debug, msg, Absent)
    def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = write(kyo.Log.Level.debug, msg, Present(t))
    def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                    = write(kyo.Log.Level.info, msg, Absent)
    def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit  = write(kyo.Log.Level.info, msg, Present(t))
    def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                    = write(kyo.Log.Level.warn, msg, Absent)
    def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit  = write(kyo.Log.Level.warn, msg, Present(t))
    def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                   = write(kyo.Log.Level.error, msg, Absent)
    def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = write(kyo.Log.Level.error, msg, Present(t))
  end JsonLogger
end AppLog

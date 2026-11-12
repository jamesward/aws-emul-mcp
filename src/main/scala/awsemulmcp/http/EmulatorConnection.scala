package awsemulmcp.http

import kyo.*
import kyo.net.*

enum ExchangeError derives CanEqual:
  case Connect(detail: String)
  case Io(detail: String)
  case TimedOut
  case BadResponse(error: Http1.ParseError)

  def message: String = this match
    case Connect(detail)    => s"could not connect to the emulator: $detail"
    case Io(detail)         => s"connection to the emulator failed: $detail"
    case TimedOut           => "the emulator did not answer in time"
    case BadResponse(error) => error.message
end ExchangeError

/** One request/response exchange with an emulator on 127.0.0.1, verifying its TLS certificate against that emulator's own CA. */
object EmulatorConnection:

  def exchange(port: Int, caFile: String, request: Http1.Request, timeout: Duration)(using
      Frame
  ): Http1.Response < (Async & Abort[ExchangeError]) =
    val io: Http1.Response < (Async & Abort[ExchangeError]) = Scope.run {
      for
        conn <- Scope.acquireRelease(connect(port, caFile))(c => Sync.Unsafe.defer(c.close()))
        _ <- Abort.recover[Closed](c => Abort.fail(ExchangeError.Io(c.getMessage)))(
          conn.outbound.safe.put(Span.from(Http1.encode(request)))
        )
        response <- read(conn, isHead = request.method == "HEAD")
      yield response
    }
    Abort.recover[Timeout](_ => Abort.fail(ExchangeError.TimedOut))(Async.timeout(timeout)(io))
  end exchange

  private final case class Received(chunks: Chunk[Array[Byte]], size: Int, needed: Maybe[Int]):
    def bytes: Array[Byte] = chunks.toArray.flatten
    def endsWith(suffix: Array[Byte]): Boolean =
      size >= suffix.length && chunks.reverseIterator.flatMap(_.reverseIterator).take(suffix.length).toArray.reverse.sameElements(suffix)

  /** Reads until the response is complete; the emulator may keep the connection open after answering. Bytes are joined and parsed
    * only once the response's framing says it is all there.
    */
  private def read(conn: Connection, isHead: Boolean)(using Frame): Http1.Response < (Async & Abort[ExchangeError]) =
    def parsed(bytes: Array[Byte], atEnd: Boolean): Maybe[Http1.Response] < Abort[ExchangeError] =
      Http1.parse(bytes, isHead, atEnd) match
        case Result.Success(r) => Kyo.lift(r)
        case Result.Failure(e) => Abort.fail(ExchangeError.BadResponse(e))
        case p: Result.Panic   => Abort.get(p)
    def next(state: Received) = Loop.continue[Received, Http1.Response, Any](state)
    def done(r: Http1.Response) = Loop.done[Received, Http1.Response](r)
    Loop(Received(Chunk.empty, 0, Absent)) { state =>
      Abort.run[Closed](conn.inbound.safe.take).map {
        case Result.Success(chunk) =>
          val grown = Received(state.chunks :+ chunk.toArray, state.size + chunk.size, state.needed)
          grown.needed match
            case Present(n) => if grown.size >= n then parsed(grown.bytes, atEnd = false).map(_.fold(next(grown))(done)) else next(grown)
            case _ =>
              val bytes = grown.bytes
              Http1.framedLength(bytes, isHead) match
                case Present(n) => if bytes.length >= n then parsed(bytes, atEnd = false).map(_.fold(next(grown))(done)) else next(grown.copy(needed = Present(n)))
                case _ if grown.endsWith(Http1.ChunkedEnd) || grown.size < 65536 =>
                  parsed(bytes, atEnd = false).map(_.fold(next(grown))(done))
                case _ => next(grown)
        case _ => parsed(state.bytes, atEnd = true).map(_.fold(Abort.fail(ExchangeError.BadResponse(Http1.ParseError.IncompleteHeaders)))(done))
      }
    }
  end read

  private def connect(port: Int, caFile: String)(using Frame): Connection < (Async & Abort[ExchangeError]) =
    Abort.recover[NetException](e => Abort.fail(ExchangeError.Connect(e.getMessage))) {
      Sync.Unsafe.defer(NetPlatform.transport.connectTls("127.0.0.1", port, NetTlsConfig(caCertPath = Present(caFile))).safe.get)
    }
end EmulatorConnection

package awsemulmcp.http

import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import kyo.*

/** Minimal HTTP/1.1 framing for talking to a local emulator over a TLS connection that kyo-http's client cannot open (it has no
  * per-connection CA setting, and each emulator run has its own self-signed CA). Requests carry `Connection: close`; a response is
  * complete once its framing (Content-Length or chunked) says so, or when the connection closes.
  */
object Http1:

  final case class Request(method: String, target: String, headers: Chunk[(String, String)], body: Array[Byte])

  final case class Response(status: Int, headers: Chunk[(String, String)], body: Array[Byte]):
    def header(name: String): Maybe[String] =
      Maybe.fromOption(headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v })

  enum ParseError derives CanEqual:
    case IncompleteHeaders
    case BadStatusLine(line: String)
    case BadChunk(detail: String)
    case TruncatedBody(expected: Int, actual: Int)

    def message: String = this match
      case IncompleteHeaders            => "the emulator closed the connection before sending complete response headers"
      case BadStatusLine(line)          => s"unexpected status line from the emulator: $line"
      case BadChunk(detail)             => s"malformed chunked body from the emulator: $detail"
      case TruncatedBody(expected, got) => s"the emulator sent $got of $expected body bytes"
  end ParseError

  /** Headers that describe one hop and must not be forwarded (RFC 9110 §7.6.1), plus the framing headers this codec sets itself
    * and `Expect` (the whole body is sent at once). */
  val HopByHop: Set[String] =
    Set("connection", "keep-alive", "proxy-connection", "proxy-authenticate", "proxy-authorization", "te", "trailer",
      "transfer-encoding", "upgrade", "content-length", "expect")

  def encode(request: Request): Array[Byte] =
    val lines =
      s"${request.method} ${request.target} HTTP/1.1" +:
        request.headers.toSeq.collect { case (k, v) if !HopByHop.contains(k.toLowerCase) => s"$k: $v" } :+
        s"Content-Length: ${request.body.length}" :+
        "Connection: close"
    (lines.mkString("", "\r\n", "\r\n\r\n")).getBytes(ISO_8859_1) ++ request.body

  /** Parses a response from everything read so far: `Absent` while more bytes are needed. With `atEnd` (the connection was
    * closed) a response without a length runs to the end of what was read. A response to `HEAD` has no body whatever its headers say.
    */
  def parse(bytes: Array[Byte], isHead: Boolean, atEnd: Boolean): Result[ParseError, Maybe[Response]] =
    indexOf(bytes, HeaderEnd, 0) match
      case -1 => if atEnd then Result.fail(ParseError.IncompleteHeaders) else Result.succeed(Absent)
      case end =>
        val lines = new String(bytes, 0, end, ISO_8859_1).split("\r\n").toList
        lines match
          case Nil => Result.fail(ParseError.IncompleteHeaders)
          case statusLine :: headerLines =>
            statusLine.split(" ", 3) match
              case Array(version, code, _*) if version.startsWith("HTTP/1.") && code.toIntOption.isDefined =>
                val headers = Chunk.from(headerLines.flatMap { line =>
                  line.indexOf(':') match
                    case -1 => None
                    case i  => Some((line.substring(0, i).trim, line.substring(i + 1).trim))
                })
                val status = code.toInt
                val rest   = bytes.drop(end + HeaderEnd.length)
                // An interim response (100 Continue, 103 Early Hints) precedes the real one.
                if status >= 100 && status < 200 then parse(rest, isHead, atEnd)
                else
                  val noBody = isHead || status == 204 || status == 304
                  body(headers, rest, noBody, atEnd).map(_.map(Response(status, headers, _)))
              case _ => Result.fail(ParseError.BadStatusLine(statusLine))
            end match
        end match

  private def body(headers: Chunk[(String, String)], rest: Array[Byte], noBody: Boolean, atEnd: Boolean)
      : Result[ParseError, Maybe[Array[Byte]]] =
    def header(name: String) = headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }
    if noBody then Result.succeed(Present(Array.emptyByteArray))
    else if header("transfer-encoding").exists(_.toLowerCase.contains("chunked")) then dechunk(rest, atEnd)
    else
      header("content-length").flatMap(_.toIntOption) match
        case Some(n) if rest.length >= n => Result.succeed(Present(rest.take(n)))
        case Some(n) if atEnd            => Result.fail(ParseError.TruncatedBody(n, rest.length))
        case Some(_)                     => Result.succeed(Absent)
        case None                        => Result.succeed(if atEnd then Present(rest) else Absent)
  end body

  /** Decodes a chunked body; `Absent` while the terminating chunk has not arrived (unless `atEnd`). */
  def dechunk(bytes: Array[Byte], atEnd: Boolean): Result[ParseError, Maybe[Array[Byte]]] =
    def incomplete(detail: String) = if atEnd then Result.fail(ParseError.BadChunk(detail)) else Result.succeed(Absent)
    @scala.annotation.tailrec
    def loop(pos: Int, acc: Chunk[Array[Byte]]): Result[ParseError, Maybe[Array[Byte]]] =
      indexOf(bytes, Crlf, pos) match
        case -1 => incomplete("missing chunk size line")
        case eol =>
          val sizeText = new String(bytes, pos, eol - pos, ISO_8859_1).takeWhile(_ != ';').trim
          scala.util.Try(Integer.parseInt(sizeText, 16)).toOption match
            case None                                           => Result.fail(ParseError.BadChunk(s"bad chunk size '$sizeText'"))
            case Some(0)                                        => Result.succeed(Present(acc.toArray.flatten))
            case Some(n) if eol + Crlf.length + n > bytes.length => incomplete("chunk runs past end of body")
            case Some(n) =>
              val start = eol + Crlf.length
              loop(start + n + Crlf.length, acc :+ bytes.slice(start, start + n))
    loop(0, Chunk.empty)
  end dechunk

  private val Crlf      = "\r\n".getBytes(UTF_8)
  private val HeaderEnd = "\r\n\r\n".getBytes(UTF_8)

  def indexOf(haystack: Array[Byte], needle: Array[Byte], from: Int): Int =
    @scala.annotation.tailrec
    def matchesAt(i: Int, j: Int): Boolean = j == needle.length || (haystack(i + j) == needle(j) && matchesAt(i, j + 1))
    @scala.annotation.tailrec
    def loop(i: Int): Int =
      if i > haystack.length - needle.length then -1
      else if matchesAt(i, 0) then i
      else loop(i + 1)
    loop(math.max(from, 0))

  /** How many bytes a response needs in total, once its head has arrived, when it is framed by Content-Length (`Absent` when the
    * head is incomplete or the body is chunked or unframed).
    */
  def framedLength(bytes: Array[Byte], isHead: Boolean): Maybe[Int] =
    indexOf(bytes, HeaderEnd, 0) match
      case -1 => Absent
      case end =>
        val head = new String(bytes, 0, end, ISO_8859_1).split("\r\n").toList
        val status = head.headOption.flatMap(_.split(" ", 3).lift(1)).flatMap(_.toIntOption)
        val length = head.drop(1).collectFirst {
          case line if line.toLowerCase.startsWith("content-length:") => line.drop("content-length:".length).trim.toIntOption
        }.flatten
        val chunked = head.drop(1).exists(l => l.toLowerCase.startsWith("transfer-encoding:") && l.toLowerCase.contains("chunked"))
        status match
          case Some(code) if code >= 100 && code < 200 => Absent
          case Some(code) if isHead || code == 204 || code == 304 => Present(end + HeaderEnd.length)
          case Some(_) if !chunked => Maybe.fromOption(length).map(_ + end + HeaderEnd.length)
          case _ => Absent
    end match
  end framedLength

  val ChunkedEnd: Array[Byte] = "0\r\n\r\n".getBytes(UTF_8)
end Http1

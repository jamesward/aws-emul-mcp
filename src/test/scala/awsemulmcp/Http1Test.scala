package awsemulmcp

import awsemulmcp.http.Http1
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import kyo.*

class Http1Test extends kyo.test.Test[Any]:

  private def bytes(s: String) = s.getBytes(ISO_8859_1)

  private def error(r: Result[Http1.ParseError, Maybe[Http1.Response]]): Maybe[Http1.ParseError] = r match
    case Result.Failure(e) => Present(e)
    case _                 => Absent

  "encodes a request with framing headers of its own" in {
    val request = Http1.Request(
      "PUT",
      "/b/k?x=1",
      Chunk("Host" -> "app.example.com", "Transfer-Encoding" -> "chunked", "Connection" -> "keep-alive", "X-Amz-Date" -> "d"),
      "hello".getBytes(UTF_8),
    )
    assert(
      String(Http1.encode(request), ISO_8859_1) ==
        "PUT /b/k?x=1 HTTP/1.1\r\nHost: app.example.com\r\nX-Amz-Date: d\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello"
    )
  }

  "parses a content-length response" in {
    val parsed = Http1.parse(bytes("HTTP/1.1 200 OK\r\ncontent-type: text/xml\r\ncontent-length: 2\r\n\r\nok"), isHead = false, atEnd = true)
    assert(parsed.map(_.map(r => (r.status, r.header("Content-Type"), String(r.body, UTF_8)))) ==
      Result.succeed(Present((200, Present("text/xml"), "ok"))))
  }

  "parses a chunked response" in {
    val raw    = "HTTP/1.1 201 Created\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n6;ext=1\r\npedia \r\n0\r\n\r\n"
    val parsed = Http1.parse(bytes(raw), isHead = false, atEnd = true)
    assert(parsed.map(_.map(r => (r.status, String(r.body, UTF_8)))) == Result.succeed(Present((201, "Wikipedia "))))
  }

  "a HEAD response has no body whatever its content-length" in {
    val parsed = Http1.parse(bytes("HTTP/1.1 200 OK\r\nContent-Length: 1234\r\n\r\n"), isHead = true, atEnd = true)
    assert(parsed.map(_.map(r => (r.header("content-length"), r.body.length))) == Result.succeed(Present((Present("1234"), 0))))
  }

  "a response is complete once its framing says so, before the connection closes" in {
    val head = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n"
    assert(Http1.parse(bytes(head + "he"), isHead = false, atEnd = false).map(_.isEmpty) == Result.succeed(true))
    assert(Http1.parse(bytes(head + "hello"), isHead = false, atEnd = false).map(_.map(r => String(r.body, UTF_8))) ==
      Result.succeed(Present("hello")))
    val chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n"
    assert(Http1.parse(bytes(chunked), isHead = false, atEnd = false).map(_.isEmpty) == Result.succeed(true))
    assert(Http1.parse(bytes(chunked + "0\r\n\r\n"), isHead = false, atEnd = false).map(_.map(r => String(r.body, UTF_8))) ==
      Result.succeed(Present("hello")))
    assert(Http1.parse(bytes("HTTP/1.1 200"), isHead = false, atEnd = false).map(_.isEmpty) == Result.succeed(true))
  }

  "skips an interim 100 Continue" in {
    val raw = "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nETag: \"x\"\r\nContent-Length: 0\r\n\r\n"
    assert(Http1.parse(bytes(raw), isHead = false, atEnd = false).map(_.map(r => (r.status, r.header("etag")))) ==
      Result.succeed(Present((200, Present("\"x\"")))))
    assert(Http1.framedLength(bytes("HTTP/1.1 100 Continue\r\n\r\n"), isHead = false) == Absent)
  }

  "framed length of a Content-Length response" in {
    assert(Http1.framedLength(bytes("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nab"), isHead = false) == Present(49))
    assert(Http1.framedLength(bytes("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n"), isHead = true) == Present(39))
    assert(Http1.framedLength(bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"), isHead = false) == Absent)
  }

  "Expect is not forwarded" in {
    val encoded = String(Http1.encode(Http1.Request("PUT", "/", Chunk("Expect" -> "100-continue"), Array.emptyByteArray)), ISO_8859_1)
    assert(!encoded.toLowerCase.contains("expect"))
  }

  "rejects truncated and malformed responses" in {
    assert(error(Http1.parse(bytes("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort"), isHead = false, atEnd = true)) ==
      Present(Http1.ParseError.TruncatedBody(10, 5)))
    assert(error(Http1.parse(bytes("HTTP/1.1 200 OK\r\n"), isHead = false, atEnd = true)) == Present(Http1.ParseError.IncompleteHeaders))
    assert(error(Http1.parse(bytes("garbage\r\n\r\n"), isHead = false, atEnd = true)) == Present(Http1.ParseError.BadStatusLine("garbage")))
  }
end Http1Test

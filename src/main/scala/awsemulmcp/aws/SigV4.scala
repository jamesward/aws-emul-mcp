package awsemulmcp.aws

import awsemulmcp.*
import java.nio.charset.StandardCharsets.UTF_8
import kyo.*

/** The parts of AWS Signature Version 4 this server needs: finding which access key signed a request (to route it to the emulator
  * that issued the key) and signing requests the `emulator_request` tool sends on an agent's behalf.
  */
object SigV4:

  val Algorithm = "AWS4-HMAC-SHA256"

  /** The access key that signed a request, from the `Authorization` header (SigV4, SigV4a or legacy `AWS key:sig`) or from a
    * presigned URL's `X-Amz-Credential` (or SigV2 `AWSAccessKeyId`) query parameter.
    */
  def accessKeyId(authorization: Maybe[String], rawQuery: Maybe[String]): Maybe[AccessKeyId] =
    authorization.flatMap(fromAuthorization).orElse(rawQuery.flatMap(fromQuery))

  private def fromAuthorization(header: String): Maybe[AccessKeyId] =
    val value = header.trim
    val credential = value.indexOf("Credential=") match
      case -1 if value.startsWith("AWS ") => Present(value.drop(4).takeWhile(_ != ':'))
      case -1                             => Absent
      case i                              => Present(value.substring(i + "Credential=".length).takeWhile(c => c != '/' && c != ','))
    credential.map(_.trim).filter(_.nonEmpty).map(AccessKeyId(_))

  private def fromQuery(rawQuery: String): Maybe[AccessKeyId] =
    val params = rawQuery.split('&').iterator.map(_.split("=", 2)).collect { case Array(k, v) => (decode(k), decode(v)) }.toSeq
    val credential = params.collectFirst {
      case (k, v) if k.equalsIgnoreCase("X-Amz-Credential") => v.takeWhile(_ != '/')
      case (k, v) if k == "AWSAccessKeyId"                  => v
    }
    Maybe.fromOption(credential).filter(_.nonEmpty).map(AccessKeyId(_))

  private def decode(s: String): String = java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

  /** RFC 3986 percent-encoding as SigV4 defines it: everything except unreserved characters is encoded. */
  def uriEncode(s: String, encodeSlash: Boolean): String =
    s.getBytes(UTF_8).iterator.map { b =>
      val c = (b & 0xff).toChar
      if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~' then
        c.toString
      else if c == '/' && !encodeSlash then "/"
      else f"%%${b & 0xff}%02X"
    }.mkString

  final case class Request(
      method: String,
      path: String,
      rawQuery: Maybe[String],
      headers: Chunk[(String, String)],
      body: Array[Byte],
  )

  final case class Scope(date: String, region: String, service: String):
    def value: String = s"$date/$region/$service/aws4_request"

  /** `path` and `rawQuery` are as sent on the wire (already percent-encoded). S3 signs the path as is; every other service signs it
    * encoded a second time, which is what the AWS SDKs do.
    */
  def canonicalRequest(request: Request, service: String): String =
    val canonicalUri =
      val p = if request.path.isEmpty then "/" else request.path
      if service == "s3" then p else uriEncode(p, encodeSlash = false)
    val canonicalQuery = request.rawQuery.fold("") { q =>
      q.split('&').iterator.filter(_.nonEmpty).map { kv =>
        kv.split("=", 2) match
          case Array(k, v) => (uriEncode(decode(k), encodeSlash = true), uriEncode(decode(v), encodeSlash = true))
          case Array(k)    => (uriEncode(decode(k), encodeSlash = true), "")
          case _           => ("", "")
      }.toSeq.sorted.map((k, v) => s"$k=$v").mkString("&")
    }
    val canonicalHeaders = normalizedHeaders(request.headers)
    Seq(
      request.method,
      canonicalUri,
      canonicalQuery,
      canonicalHeaders.map((k, v) => s"$k:$v\n").mkString,
      signedHeaders(request.headers),
      payloadHash(request),
    ).mkString("\n")
  end canonicalRequest

  private def normalizedHeaders(headers: Chunk[(String, String)]): Seq[(String, String)] =
    headers.toSeq
      .groupMap(_._1.toLowerCase)(_._2.trim.replaceAll("\\s+", " "))
      .view.mapValues(_.mkString(",")).toSeq.sortBy(_._1)

  def signedHeaders(headers: Chunk[(String, String)]): String = normalizedHeaders(headers).map(_._1).mkString(";")

  private def payloadHash(request: Request): String =
    request.headers.collectFirst { case (k, v) if k.equalsIgnoreCase("x-amz-content-sha256") => v }
      .getOrElse(Crypto.hex(Crypto.sha256(request.body)))

  def stringToSign(amzDate: String, scope: Scope, canonical: String): String =
    Seq(Algorithm, amzDate, scope.value, Crypto.hex(Crypto.sha256(canonical.getBytes(UTF_8)))).mkString("\n")

  def signature(secret: SecretAccessKey, scope: Scope, toSign: String): String =
    val kDate    = Crypto.hmacSha256(("AWS4" + secret.value).getBytes(UTF_8), scope.date.getBytes(UTF_8))
    val kRegion  = Crypto.hmacSha256(kDate, scope.region.getBytes(UTF_8))
    val kService = Crypto.hmacSha256(kRegion, scope.service.getBytes(UTF_8))
    val kSigning = Crypto.hmacSha256(kService, "aws4_request".getBytes(UTF_8))
    Crypto.hex(Crypto.hmacSha256(kSigning, toSign.getBytes(UTF_8)))

  /** The `Authorization` header for `request`, whose headers must already include `host` and `x-amz-date`. */
  def authorization(credentials: Credentials, amzDate: String, region: String, service: String, request: Request): String =
    val scope = Scope(amzDate.take(8), region, service)
    val sig   = signature(credentials.secretAccessKey, scope, stringToSign(amzDate, scope, canonicalRequest(request, service)))
    s"$Algorithm Credential=${credentials.accessKeyId.value}/${scope.value}, SignedHeaders=${signedHeaders(request.headers)}, Signature=$sig"

  /** `yyyyMMdd'T'HHmmss'Z'` */
  def amzDate(instant: java.time.Instant): String =
    val t = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneOffset.UTC)
    f"${t.getYear}%04d${t.getMonthValue}%02d${t.getDayOfMonth}%02dT${t.getHour}%02d${t.getMinute}%02d${t.getSecond}%02dZ"
end SigV4

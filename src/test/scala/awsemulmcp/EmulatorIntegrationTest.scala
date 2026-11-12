package awsemulmcp

import awsemulmcp.Fixtures.*
import awsemulmcp.aws.*
import awsemulmcp.mcp.*
import java.nio.charset.StandardCharsets.UTF_8
import kyo.*

/** Runs a real `aws-emulator` (set AWS_EMULATOR_BIN, or install the package so /opt/aws-emulator/bin/aws-emulator exists). */
class EmulatorIntegrationTest extends munit.FunSuite:

  private val binary: Option[String] =
    sys.env.get("AWS_EMULATOR_BIN").orElse(Some("/opt/aws-emulator/bin/aws-emulator")).filter(p => java.io.File(p).canExecute)

  override def munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private def mcp(routes: Routes, session: String, method: String, params: String): String =
    val body = s"""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
    bodyText(run(routes.handle(request(HttpMethod.POST, "/mcp", Seq("Mcp-Session-Id" -> session), body.getBytes(UTF_8)))))

  private def toolResult(json: String): CallToolResult =
    Json.decode[RpcSuccess](json).flatMap(s => Structure.decode[CallToolResult](s.result)).getOrThrow

  private def structured[A: Schema](json: String): A =
    toolResult(json).structuredContent.map(v => Structure.decode[A](v).getOrThrow).getOrElse(fail(s"no structuredContent in $json"))

  test("create an emulator, call it through the tool and through the proxy, then delete it") {
    assume(binary.isDefined, "aws-emulator is not installed")
    val cfg = config(
      "AWS_EMULATOR_BIN"   -> binary.get,
      "MAX_EMULATORS"      -> "1",
      "EMULATOR_HOME_ROOT" -> java.nio.file.Files.createTempDirectory("emulators").toString,
      "PUBLIC_URL"         -> "https://app.example.com",
    )
    val (routes, _) = run(Fixtures.routes(cfg))
    val session     = "test-session"

    val created = structured[EmulatorInfo](mcp(routes, session, "tools/call", """{"name":"emulator_create","arguments":{}}"""))
    assert(created.endpointUrl == "https://app.example.com")
    assert(created.environment.contains(s"AWS_ACCESS_KEY_ID=${created.accessKeyId}"))

    // At capacity
    val second = toolResult(mcp(routes, session, "tools/call", """{"name":"emulator_create","arguments":{}}"""))
    assert(second.isError && second.content.exists(_.text.contains("maximum of 1")))

    // The emulator_request tool signs for the emulator itself.
    val sts = structured[RequestOutput](mcp(
      routes,
      session,
      "tools/call",
      s"""{"name":"emulator_request","arguments":{"emulatorId":"${created.emulatorId}","service":"sts","headers":{"Content-Type":"application/x-www-form-urlencoded"},"body":"Action=GetCallerIdentity&Version=2011-06-15"}}""",
    ))
    assert(sts.status == 200 && sts.body.contains(AppInfo.AccountId), sts.toString)

    // A client that signed for the public host name (what the AWS SDKs do) reaches the emulator through the proxy.
    val body    = """{"QueueName":"proxied"}""".getBytes(UTF_8)
    val amzDate = SigV4.amzDate(java.time.Instant.now())
    val headers = Chunk(
      "Host"                 -> "app.example.com",
      "Content-Type"         -> "application/x-amz-json-1.0",
      "X-Amz-Target"         -> "AmazonSQS.CreateQueue",
      "X-Amz-Date"           -> amzDate,
      "X-Amz-Content-Sha256" -> Crypto.hex(Crypto.sha256(body)),
    )
    val creds = Credentials(AccessKeyId(created.accessKeyId), SecretAccessKey(created.secretAccessKey))
    val auth  = SigV4.authorization(creds, amzDate, AppInfo.Region, "sqs", SigV4.Request("POST", "/", Absent, headers, body))
    val proxied = run(routes.handle(request(
      HttpMethod.POST,
      "/",
      headers.toSeq :+ ("Authorization" -> auth) :+ ("X-Forwarded-For" -> "203.0.113.9"),
      body,
    )))
    assert(proxied.status == HttpStatus.OK, bodyText(proxied))
    assert(bodyText(proxied).contains("proxied"), bodyText(proxied))

    val listed = structured[EmulatorList](mcp(routes, session, "tools/call", """{"name":"emulator_list","arguments":{}}"""))
    assert(listed.emulators.map(_.emulatorId) == Chunk(created.emulatorId))
    val otherSession = structured[EmulatorList](mcp(routes, "someone-else", "tools/call", """{"name":"emulator_list","arguments":{}}"""))
    assert(otherSession.emulators.isEmpty)

    val deleted = structured[Deleted](mcp(
      routes,
      session,
      "tools/call",
      s"""{"name":"emulator_delete","arguments":{"emulatorId":"${created.emulatorId}"}}""",
    ))
    assert(deleted.deleted)
    val after = toolResult(mcp(
      routes,
      session,
      "tools/call",
      s"""{"name":"emulator_request","arguments":{"emulatorId":"${created.emulatorId}","service":"sts"}}""",
    ))
    assert(after.isError)
  }
end EmulatorIntegrationTest

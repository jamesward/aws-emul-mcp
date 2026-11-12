package awsemulmcp

import awsemulmcp.Fixtures.*
import awsemulmcp.aws.*
import awsemulmcp.mcp.*
import java.nio.charset.StandardCharsets.UTF_8
import kyo.*

/** Runs a real `aws-emulator` (set AWS_EMULATOR_BIN, or install the package so /opt/aws-emulator/bin/aws-emulator exists). */
class EmulatorIntegrationTest extends kyo.test.Test[Any]:

  override protected def timeout: Duration = 5.minutes

  private val binary: Option[String] =
    sys.env.get("AWS_EMULATOR_BIN").orElse(Some("/opt/aws-emulator/bin/aws-emulator")).filter(p => java.io.File(p).canExecute)

  private def mcp(routes: Routes, session: String, method: String, params: String): String < Async =
    val body = s"""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
    routes.handle(request(HttpMethod.POST, "/mcp", Seq("Mcp-Session-Id" -> session), body.getBytes(UTF_8))).map(bodyText)

  private def tool(routes: Routes, session: String, name: String, arguments: String): CallToolResult < (Async & Abort[Any]) =
    mcp(routes, session, "tools/call", s"""{"name":"$name","arguments":$arguments}""").map { json =>
      Abort.get(Json.decode[RpcSuccess](json).flatMap(s => Structure.decode[CallToolResult](s.result)))
    }

  private def structured[A: Schema](result: CallToolResult): A < Abort[Any] =
    result.structuredContent match
      case Present(v) => Abort.get(Structure.decode[A](v))
      case _          => Abort.fail(s"no structuredContent in $result")

  /** What an AWS SDK sends: SQS CreateQueue (JSON protocol), signed for the public host name. */
  private def sdkRequest(created: EmulatorInfo, now: java.time.Instant): HttpRequest[Routes.Body] =
    val body    = """{"QueueName":"proxied"}""".getBytes(UTF_8)
    val amzDate = SigV4.amzDate(now)
    val headers = Chunk(
      "Host"                 -> "app.example.com",
      "Content-Type"         -> "application/x-amz-json-1.0",
      "X-Amz-Target"         -> "AmazonSQS.CreateQueue",
      "X-Amz-Date"           -> amzDate,
      "X-Amz-Content-Sha256" -> Crypto.hex(Crypto.sha256(body)),
    )
    val creds = Credentials(AccessKeyId(created.accessKeyId), SecretAccessKey(created.secretAccessKey))
    val auth  = SigV4.authorization(creds, amzDate, AppInfo.Region, "sqs", SigV4.Request("POST", "/", Absent, headers, body))
    request(HttpMethod.POST, "/", headers.toSeq :+ ("Authorization" -> auth) :+ ("X-Forwarded-For" -> "203.0.113.9"), body)

  "create an emulator, call it through the tool and through the proxy, then delete it" in {
    assume(binary.isDefined, "aws-emulator is not installed")
    val cfg = appConfig(
      "AWS_EMULATOR_BIN"   -> binary.getOrElse(""),
      "MAX_EMULATORS"      -> "1",
      "EMULATOR_HOME_ROOT" -> java.nio.file.Files.createTempDirectory("emulators").toString,
      "PUBLIC_URL"         -> "https://app.example.com",
    )
    val session = "test-session"
    for
      (routes, _) <- Fixtures.routes(cfg)
      created     <- tool(routes, session, "emulator_create", "{}").map(structured[EmulatorInfo])
      _ = assert(created.endpointUrl == "https://app.example.com")
      _ = assert(created.environment.contains(s"AWS_ACCESS_KEY_ID=${created.accessKeyId}"))

      second <- tool(routes, session, "emulator_create", "{}")
      _ = assert(second.isError && second.content.exists(_.text.contains("maximum of 1")), second.toString)

      // The emulator_request tool signs for the emulator itself.
      sts <- tool(
        routes,
        session,
        "emulator_request",
        s"""{"emulatorId":"${created.emulatorId}","service":"sts","headers":{"Content-Type":"application/x-www-form-urlencoded"},"body":"Action=GetCallerIdentity&Version=2011-06-15"}""",
      ).map(structured[RequestOutput])
      _ = assert(sts.status == 200 && sts.body.contains(AppInfo.AccountId), sts.toString)

      // A client that signed for the public host name reaches the emulator through the proxy.
      now     <- Clock.now
      proxied <- routes.handle(sdkRequest(created, now.toJava))
      _ = assert(proxied.status == HttpStatus.OK, bodyText(proxied))
      _ = assert(bodyText(proxied).contains("proxied"), bodyText(proxied))

      listed <- tool(routes, session, "emulator_list", "{}").map(structured[EmulatorList])
      _ = assert(listed.emulators.map(_.emulatorId) == Chunk(created.emulatorId))
      others <- tool(routes, "someone-else", "emulator_list", "{}").map(structured[EmulatorList])
      _ = assert(others.emulators.isEmpty)

      deleted <- tool(routes, session, "emulator_delete", s"""{"emulatorId":"${created.emulatorId}"}""").map(structured[Deleted])
      _ = assert(deleted.deleted)
      after <- tool(routes, session, "emulator_request", s"""{"emulatorId":"${created.emulatorId}","service":"sts"}""")
    yield assert(after.isError)
    end for
  }
end EmulatorIntegrationTest

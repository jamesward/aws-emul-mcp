package awsemulmcp.mcp

import awsemulmcp.*
import awsemulmcp.aws.SigV4
import awsemulmcp.emulator.*
import awsemulmcp.http.*
import java.nio.charset.StandardCharsets.UTF_8
import kyo.*

/** Who is calling a tool and how clients reach this server. */
final case class ToolContext(session: Maybe[SessionId], publicUrl: PublicUrl)

final case class NoInput() derives Schema

final case class EmulatorIdInput(emulatorId: String)
object EmulatorIdInput:
  given Schema[EmulatorIdInput] =
    Schema[EmulatorIdInput].doc(_.emulatorId)("The emulatorId returned by emulator_create.")

final case class RequestInput(
    emulatorId: String,
    service: String,
    method: Maybe[String],
    path: Maybe[String],
    query: Maybe[String],
    headers: Maybe[Map[String, String]],
    body: Maybe[String],
)
object RequestInput:
  given Schema[RequestInput] = Schema[RequestInput]
    .doc(_.emulatorId)("The emulatorId returned by emulator_create.")
    .doc(_.service)(
      "SigV4 signing name of the service, usually its endpoint prefix: sqs, sns, dynamodb, s3, lambda, iam, sts, logs, events, states, kinesis, secretsmanager, ssm, kms, cloudformation, ec2, ..."
    )
    .doc(_.method)("HTTP method. Default POST.")
    .doc(_.path)("Percent-encoded request path, e.g. / (query and JSON protocols), /my-bucket/my-key (S3, path style), /2015-03-31/functions (Lambda). Default /.")
    .doc(_.query)("Raw query string without the leading ?, e.g. Action=ListQueues&Version=2012-11-05 or list-type=2.")
    .doc(_.headers)(
      "Extra headers, e.g. {\"Content-Type\": \"application/x-amz-json-1.0\", \"X-Amz-Target\": \"DynamoDB_20120810.ListTables\"}. Host, X-Amz-Date, X-Amz-Content-Sha256 and Authorization are set by the server."
    )
    .doc(_.body)("Request body as UTF-8 text (JSON, XML or form-encoded).")
end RequestInput

final case class EmulatorInfo(
    emulatorId: String,
    endpointUrl: String,
    region: String,
    accountId: String,
    accessKeyId: String,
    secretAccessKey: String,
    createdAt: String,
    idleTimeoutSeconds: Long,
    environment: String,
    notes: Chunk[String],
) derives Schema

final case class EmulatorList(emulators: Chunk[EmulatorInfo], running: Int, maxEmulators: Int) derives Schema
final case class Deleted(emulatorId: String, deleted: Boolean) derives Schema
final case class RequestOutput(status: Int, headers: Map[String, String], body: String, bodyTruncated: Boolean) derives Schema

/** The tools this server adds next to the relayed AWS Knowledge tools. */
final class EmulatorTools(emulators: Emulators, config: EmulatorConfig):
  import EmulatorTools.*

  val definitions: Chunk[ToolDefinition] = Chunk(
    ToolDefinition(
      Create,
      "Create AWS Emulator",
      "Starts a private, in-memory AWS Emulator (AWS's local emulator of 200+ services: one account 000000000000, region us-east-1) and returns its endpoint and access keys. Call AWS APIs on it with emulator_request, or point the AWS CLI/SDKs at the endpoint with the returned environment variables. It stops after a period without API calls, losing its resources.",
      inputSchema[NoInput],
      ToolAnnotations("Create AWS Emulator", readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
    ),
    ToolDefinition(
      ListTool,
      "List AWS Emulators",
      "Lists the running emulators created in this MCP session, with their endpoints and keys.",
      inputSchema[NoInput],
      ToolAnnotations("List AWS Emulators", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    ),
    ToolDefinition(
      Delete,
      "Delete AWS Emulator",
      "Stops an emulator and discards everything in it.",
      inputSchema[EmulatorIdInput],
      ToolAnnotations("Delete AWS Emulator", readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false),
    ),
    ToolDefinition(
      Request,
      "Call AWS API on Emulator",
      """Sends one AWS API request to an emulator, signed with its keys (SigV4, us-east-1), and returns the raw HTTP response. Use the service's wire protocol, for example:
- Query (SNS, IAM, STS, CloudFormation, EC2): method POST, path /, headers {"Content-Type":"application/x-www-form-urlencoded"}, body "Action=CreateTopic&Name=t1&Version=2010-03-31"
- JSON (SQS with X-Amz-Target AmazonSQS.CreateQueue, DynamoDB, CloudWatch Logs, Step Functions, Kinesis, Secrets Manager, SSM, KMS): method POST, path /, headers {"Content-Type":"application/x-amz-json-1.0","X-Amz-Target":"DynamoDB_20120810.ListTables"}, body "{}"
- REST-JSON (Lambda, API Gateway): e.g. method GET, path /2015-03-31/functions
- REST-XML (S3, path-style): e.g. PUT /my-bucket, PUT /my-bucket/key.txt with a body, GET /my-bucket?list-type=2
Use the AWS documentation tools to look up an API's protocol, target prefix and parameters.""",
      inputSchema[RequestInput],
      ToolAnnotations("Call AWS API on Emulator", readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false),
    ),
  )

  val names: Set[String] = definitions.map(_.name).toSet

  def call(name: String, arguments: Structure.Value, ctx: ToolContext)(using Frame): CallToolResult < Async =
    val result: CallToolResult < (Async & Abort[CallToolResult]) = name match
      case Create   => decode[NoInput](arguments).map(_ => create(ctx))
      case ListTool => decode[NoInput](arguments).map(_ => list(ctx))
      case Delete   => decode[EmulatorIdInput](arguments).map(in => delete(EmulatorId(in.emulatorId)))
      case Request  => decode[RequestInput](arguments).map(request)
      case other    => Kyo.lift(failure(s"Unknown tool: $other"))
    result.recovered
  end call

  private def decode[A: Schema](arguments: Structure.Value)(using Frame): A < Abort[CallToolResult] =
    Structure.decode[A](arguments) match
      case Result.Success(a) => Kyo.lift(a)
      case Result.Failure(e) => Abort.fail(failure(s"Invalid arguments: ${e.getMessage}"))
      case p: Result.Panic   => Abort.fail(failure(s"Invalid arguments: ${p.exception.getMessage}"))

  extension (v: CallToolResult < (Async & Abort[CallToolResult]))
    private def recovered(using Frame): CallToolResult < Async = Abort.run[CallToolResult](v).map {
      case Result.Success(r) => r
      case Result.Failure(r) => r
      case p: Result.Panic   => AppLog.errorCause("tool failed", p.exception).andThen(failure("The tool failed unexpectedly."))
    }

  private def create(ctx: ToolContext)(using Frame): CallToolResult < Async =
    Abort.run[EmulatorError](emulators.create(ctx.session)).map {
      case Result.Success(e) => success(info(e, ctx.publicUrl))
      case Result.Failure(err) =>
        AppLog.warn("emulator creation failed", "error" -> err.message).andThen(failure(err.message))
      case p: Result.Panic =>
        AppLog.errorCause("emulator creation failed", p.exception).andThen(failure("The emulator could not be started."))
    }

  private def list(ctx: ToolContext)(using Frame): CallToolResult < Async =
    ctx.session match
      case Present(s) =>
        for
          es    <- emulators.list(s)
          count <- emulators.count
        yield success(EmulatorList(es.map(info(_, ctx.publicUrl)), count, emulators.max))
      case _ => Kyo.lift(failure("This MCP connection has no session (Mcp-Session-Id), so its emulators cannot be listed."))

  private def delete(id: EmulatorId)(using Frame): CallToolResult < Async =
    emulators.delete(id).map {
      case true  => success(Deleted(id.value, deleted = true))
      case false => failure(s"No running emulator has id ${id.value}.")
    }

  private def request(in: RequestInput)(using Frame): CallToolResult < Async =
    emulators.get(EmulatorId(in.emulatorId)).map {
      case Present(e) =>
        for
          now <- Clock.now
          upstream = signedRequest(e, in, now.toJava)
          result <- Abort.run[ExchangeError](e.exchange(upstream, config.requestTimeout))
        yield result match
          case Result.Success(r) =>
            val text = String(r.body, UTF_8)
            success(RequestOutput(r.status, r.headers.toSeq.toMap, text.take(MaxBodyChars), text.length > MaxBodyChars))
          case Result.Failure(err) => failure(err.message)
          case p: Result.Panic     => failure(s"The request failed: ${p.exception.getMessage}")
      case _ => Kyo.lift(failure(s"No running emulator has id ${in.emulatorId}. Emulators stop when idle; create a new one with $Create."))
    }

  private def info(e: Emulator, publicUrl: PublicUrl): EmulatorInfo =
    val endpoint = publicUrl.value
    EmulatorInfo(
      emulatorId = e.id.value,
      endpointUrl = endpoint,
      region = AppInfo.Region,
      accountId = AppInfo.AccountId,
      accessKeyId = e.credentials.accessKeyId.value,
      secretAccessKey = e.credentials.secretAccessKey.value,
      createdAt = e.createdAt.toJava.toString,
      idleTimeoutSeconds = config.idleTimeout.toSeconds,
      environment = Seq(
        s"export AWS_ENDPOINT_URL=$endpoint",
        s"export AWS_ACCESS_KEY_ID=${e.credentials.accessKeyId.value}",
        s"export AWS_SECRET_ACCESS_KEY=${e.credentials.secretAccessKey.value}",
        s"export AWS_REGION=${AppInfo.Region}",
        s"export AWS_DEFAULT_REGION=${AppInfo.Region}",
        "unset AWS_SESSION_TOKEN AWS_PROFILE",
      ).mkString("\n"),
      notes = Chunk(
        "The endpoint has a publicly trusted certificate: no CA bundle is needed.",
        "Use path-style S3 addressing (AWS CLI: aws configure set default.s3.addressing_style path; JS SDK v3: forcePathStyle: true; boto3: Config(s3={'addressing_style': 'path'})).",
        "Requests are routed to this emulator by its access key, so they must be signed with it (SigV4).",
        s"State is in memory only and is lost when the emulator stops (after ${config.idleTimeout.toMinutes} minutes without API calls, on delete, or when the server restarts).",
      ),
    )
  end info
end EmulatorTools

object EmulatorTools:
  val Create   = "emulator_create"
  val ListTool = "emulator_list"
  val Delete   = "emulator_delete"
  val Request  = "emulator_request"

  val MaxBodyChars = 100_000

  inline def inputSchema[A](using Schema[A]): Structure.Value = Structure.encode(Json.jsonSchema[A])

  def success[A: Schema](value: A)(using Frame): CallToolResult =
    CallToolResult(Chunk(TextContent(Json.encode(value))), Present(Structure.encode(value)), isError = false)

  def failure(message: String): CallToolResult = CallToolResult(Chunk(TextContent(message)), Absent, isError = true)

  /** Builds the request the AWS SDKs would send: `host`, `x-amz-date` and `x-amz-content-sha256` are added and signed with every
    * caller-supplied header.
    */
  def signedRequest(e: Emulator, in: RequestInput, now: java.time.Instant): Http1.Request =
    val method  = in.method.map(_.trim.toUpperCase).filter(_.nonEmpty).getOrElse("POST")
    val path    = in.path.map(_.trim).filter(_.nonEmpty).map(p => if p.startsWith("/") then p else "/" + p).getOrElse("/")
    val query   = in.query.map(_.trim.stripPrefix("?")).filter(_.nonEmpty)
    val body    = in.body.getOrElse("").getBytes(UTF_8)
    val amzDate = SigV4.amzDate(now)
    val reserved = Set("host", "x-amz-date", "x-amz-content-sha256", "authorization", "content-length")
    val headers = Chunk.from(in.headers.getOrElse(Map.empty).toSeq.filterNot((k, _) => reserved.contains(k.toLowerCase))) ++ Chunk(
      "Host"                 -> s"127.0.0.1:${e.port}",
      "X-Amz-Date"           -> amzDate,
      "X-Amz-Content-Sha256" -> awsemulmcp.aws.Crypto.hex(awsemulmcp.aws.Crypto.sha256(body)),
    )
    val auth = SigV4.authorization(
      e.credentials,
      amzDate,
      AppInfo.Region,
      in.service.trim.toLowerCase,
      SigV4.Request(method, path, query, headers, body),
    )
    Http1.Request(method, path + query.fold("")("?" + _), headers :+ ("Authorization" -> auth), body)
  end signedRequest
end EmulatorTools

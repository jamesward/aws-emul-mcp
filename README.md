# aws-emul-mcp

A remote MCP server that gives an agent AWS documentation and a private AWS to build against, with no AWS account and
nothing installed:

- **AWS Knowledge tools** (`aws___search_documentation`, `aws___read_documentation`, `aws___list_regions`,
  `aws___get_regional_availability`, ...), relayed from the [AWS Knowledge MCP server](https://awslabs.github.io/mcp/servers/aws-knowledge-mcp-server).
- **AWS Emulator tools** that run [AWS Emulator](https://github.com/aws/aws-emulator) instances on the server:

  | Tool | What it does |
  |---|---|
  | `emulator_create` | Starts an emulator (one account `000000000000`, region `us-east-1`, in memory) and returns its endpoint, keys and `export` lines for the AWS CLI/SDKs. |
  | `emulator_request` | Sends one signed AWS API request to an emulator and returns the HTTP response, so an agent needs no local AWS tooling. |
  | `emulator_list` | Lists the emulators created in this MCP session. |
  | `emulator_delete` | Stops an emulator. |

Built with Scala 3, [Kyo](https://getkyo.io) and Scala Native; the emulator itself is AWS's binary.

## Use it

Add the MCP endpoint (Streamable HTTP, no authentication) to your agent:

```sh
claude mcp add --transport http aws-emulator https://<app>.herokuapp.com/mcp
```

Then ask, for example: "Create an AWS emulator, make an SQS queue and a DynamoDB table, and test my code against them."

To use the AWS CLI or an SDK instead of `emulator_request`, run the `export` lines from `emulator_create`:

```sh
export AWS_ENDPOINT_URL=https://<app>.herokuapp.com
export AWS_ACCESS_KEY_ID=EMULATOR...
export AWS_SECRET_ACCESS_KEY=...
export AWS_REGION=us-east-1
aws sqs create-queue --queue-name q1
```

The endpoint has a public certificate, so no CA bundle is needed. Use path-style S3 addressing.

## How it works

```
agent ──MCP──▶ /mcp ──▶ tools/list, tools/call ──▶ AWS Knowledge MCP (relayed)
                         emulator_* ─────────────▶ aws-emulator child processes (127.0.0.1, own CA, own keys)
AWS SDK ─SigV4─▶ any path ──▶ routed by the signing access key ──▶ that emulator
```

Every request signed with AWS credentials (`Authorization` header or presigned `X-Amz-Credential`) is forwarded
unchanged, `Host` included, to the emulator that issued the access key. The emulator runs with `--auth on` and checks the
signature itself. The hop to the emulator is TLS, verified against that emulator's own CA.

Emulators keep everything in memory. They stop after `EMULATOR_IDLE_TIMEOUT_SECONDS` without API calls, on
`emulator_delete`, or when the dyno restarts. The emulator is for building and testing, not production, and does not
enforce IAM policies the way AWS does.

## Configuration

| Variable | Default | |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `MAX_EMULATORS` | `2` | Emulators running at once (each about 180 MB, plus about 110 MB shared by all); `0` disables them |
| `EMULATOR_IDLE_TIMEOUT_SECONDS` | `1800` | Idle time before an emulator stops |
| `AWS_EMULATOR_BIN` | `aws-emulator` | The emulator binary (the buildpack sets it) |
| `AWS_EMULATOR_TELEMETRY` | `off` | The emulator's usage telemetry, `on` or `off` |
| `EMULATOR_HOME_ROOT` | `/tmp/aws-emulators` | Per-emulator home directories (credentials, CA) |
| `MAX_REQUEST_MB` | `64` | Largest request body |
| `PUBLIC_URL` | from `Host` and `X-Forwarded-Proto` | Endpoint URL given to clients |
| `KNOWLEDGE_MCP_URL` | `https://knowledge-mcp.global.api.aws` | Upstream MCP server |

## Deploy to Heroku

```sh
heroku create <app>
heroku buildpacks:add heroku/jvm
heroku buildpacks:add https://github.com/jamesward/buildpack-scala-native#aws-emulator
heroku ps:type web=standard-2x
git push heroku main
```

The `.aws-emulator` file makes the buildpack ship the emulator binary in the slug (about 265 MB compressed). Run exactly one
web dyno: emulators live on the dyno that started them.

## Develop

The same code builds as a JVM app for development and as a Scala Native binary for production. With the
[AWS Emulator package](https://docs.aws.amazon.com/emulator/latest/userguide/getting-started-linux.html) installed:

```sh
AWS_EMULATOR_BIN=/opt/aws-emulator/bin/aws-emulator ./sbta ~runReload
```

restarts the server on every change (add `-Dnative=false` where the Scala Native headers are installed). See
[AGENTS.md](AGENTS.md) for the build, test and validation commands.

package awsemulmcp

/** The page at `/`: how to add this server to an MCP client. */
object Landing:
  private def escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  def html(publicUrl: PublicUrl): String =
    val mcpUrl = escape(publicUrl.value + "/mcp")
    s"""<!doctype html>
       |<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
       |<title>${AppInfo.Title}</title>
       |<style>body{font-family:system-ui,sans-serif;max-width:46rem;margin:2rem auto;padding:0 1rem;line-height:1.5}code,pre{background:#f3f3f3;padding:.1rem .3rem;border-radius:4px}pre{padding:.8rem;overflow-x:auto}</style>
       |</head><body>
       |<h1>${AppInfo.Title}</h1>
       |<p>A remote MCP server with the AWS Knowledge tools (AWS documentation, regional availability) plus tools that start a private
       |<a href="https://aws.amazon.com/about-aws/whats-new/2026/11/aws-emulator-now-available/">AWS Emulator</a> for your agent: no AWS account
       |and nothing to install.</p>
       |<h2>Add it to your agent</h2>
       |<p>MCP endpoint (Streamable HTTP, no authentication): <code>$mcpUrl</code></p>
       |<pre>claude mcp add --transport http aws-emulator $mcpUrl</pre>
       |<pre>{ "mcpServers": { "aws-emulator": { "type": "http", "url": "$mcpUrl" } } }</pre>
       |<h2>Then ask your agent</h2>
       |<p>"Create an AWS emulator, then make an SQS queue and a DynamoDB table and test my code against them."</p>
       |<p>The agent calls <code>emulator_create</code>, then either <code>emulator_request</code> (no local tools needed) or the AWS CLI/SDKs
       |with the endpoint and keys it gets back. Emulators keep everything in memory and stop when idle.</p>
       |</body></html>
       |""".stripMargin
end Landing

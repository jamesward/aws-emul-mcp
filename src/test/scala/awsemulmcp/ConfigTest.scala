package awsemulmcp

import kyo.*

class ConfigTest extends kyo.test.Test[Any]:

  private def config(vars: (String, String)*) = Config.fromEnv(name => Maybe.fromOption(vars.toMap.get(name)))

  "defaults" in {
    val c = config()
    assert(c.map(c => (c.port, c.emulator.maxEmulators, c.emulator.telemetry, c.knowledgeMcpUrl)) ==
      Result.succeed((8080, 2, Telemetry.Off, "https://knowledge-mcp.global.api.aws")))
  }

  "overrides" in {
    val c = config("PORT" -> "5000", "MAX_EMULATORS" -> "0", "AWS_EMULATOR_TELEMETRY" -> "on", "PUBLIC_URL" -> "https://x.example.com/")
    assert(c.map(c => (c.port, c.emulator.maxEmulators, c.emulator.telemetry, c.publicUrl.map(_.value))) ==
      Result.succeed((5000, 0, Telemetry.On, Present("https://x.example.com"))))
  }

  "rejects bad values" in {
    assert(config("MAX_EMULATORS" -> "-1").isFailure)
    assert(config("AWS_EMULATOR_TELEMETRY" -> "maybe").isFailure)
  }
end ConfigTest

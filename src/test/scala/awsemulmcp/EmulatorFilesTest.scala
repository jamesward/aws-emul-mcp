package awsemulmcp

import awsemulmcp.emulator.EmulatorFiles
import kyo.*

class EmulatorFilesTest extends kyo.test.Test[Any]:

  // Captured from aws-emulator 1.0.322.
  private val stdout =
    """{"timestamp":"2026-11-12T14:52:28.988876Z","level":"INFO","fields":{"message":"aws-emulator emulator starting"},"target":"aws_emulator"}
      |This emulator collects usage and error telemetry: disabled (via the embedding configuration)
      |{"timestamp":"2026-11-12T14:52:29.050842Z","level":"INFO","fields":{"message":"request authentication enabled","credentials_file":"/h/.aws/emulator/40793/emulator_credentials"},"target":"aws_emulator_server"}
      |{"timestamp":"2026-11-12T14:52:29.050967Z","level":"INFO","fields":{"message":"emulator server information","endpoint_url":"https://127.0.0.1:40793","health_check_url":"https://127.0.0.1:40793/__admin/health","config_file":"/h/.aws/emulator/40793/config","ca_file":"/h/.aws/emulator/40793/ca.pem","java_truststore_file":"/h/.aws/emulator/40793/truststore.p12"},"target":"aws_emulator_server"}
      |""".stripMargin

  "finds the port and CA in the startup line" in {
    assert(EmulatorFiles.startup(stdout) == Present(EmulatorFiles.Startup(40793, "/h/.aws/emulator/40793/ca.pem")))
  }

  "no startup line yet" in {
    assert(EmulatorFiles.startup(stdout.linesIterator.take(2).mkString("\n")) == Absent)
  }

  "reads the credentials file" in {
    val ini = "[default]\naws_access_key_id = EMULATOR0001F5C13B52\naws_secret_access_key = D82AA0A2EE01B5E2266BA5EC291AD5DCA7BD630F\n"
    val creds = EmulatorFiles.credentials(ini)
    assert(creds.map(_.accessKeyId) == Present(AccessKeyId("EMULATOR0001F5C13B52")))
    assert(creds.map(_.secretAccessKey.value) == Present("D82AA0A2EE01B5E2266BA5EC291AD5DCA7BD630F"))
    assert(EmulatorFiles.credentials("[default]\n").isEmpty)
  }
end EmulatorFilesTest

package awsemulmcp

opaque type EmulatorId = String
object EmulatorId:
  def apply(value: String): EmulatorId = value
  extension (id: EmulatorId) def value: String = id
  given CanEqual[EmulatorId, EmulatorId] = CanEqual.derived

opaque type AccessKeyId = String
object AccessKeyId:
  def apply(value: String): AccessKeyId = value
  extension (id: AccessKeyId) def value: String = id
  given CanEqual[AccessKeyId, AccessKeyId] = CanEqual.derived

opaque type SecretAccessKey = String
object SecretAccessKey:
  def apply(value: String): SecretAccessKey = value
  extension (key: SecretAccessKey) def value: String = key

/** The `Mcp-Session-Id` this server hands out on `initialize`; emulators are listed per session. */
opaque type SessionId = String
object SessionId:
  def apply(value: String): SessionId = value
  extension (id: SessionId) def value: String = id
  given CanEqual[SessionId, SessionId] = CanEqual.derived

/** Heroku's router `X-Request-ID`, logged as `request_id`. */
opaque type RequestId = String
object RequestId:
  def apply(value: String): RequestId = value
  extension (id: RequestId) def value: String = id

final case class Credentials(accessKeyId: AccessKeyId, secretAccessKey: SecretAccessKey)

/** Scheme, host and optional port that clients use to reach this server, e.g. `https://example.herokuapp.com`. */
opaque type PublicUrl = String
object PublicUrl:
  def apply(value: String): PublicUrl = value.stripSuffix("/")
  extension (url: PublicUrl) def value: String = url

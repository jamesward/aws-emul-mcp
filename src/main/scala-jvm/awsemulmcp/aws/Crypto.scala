package awsemulmcp.aws

/** SHA-256 and HMAC-SHA256 from the JDK (the Scala Native build uses libcrypto instead). */
object Crypto:
  def sha256(data: Array[Byte]): Array[Byte] = java.security.MessageDigest.getInstance("SHA-256").digest(data)

  def hmacSha256(key: Array[Byte], data: Array[Byte]): Array[Byte] =
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data)

  def hex(bytes: Array[Byte]): String = java.util.HexFormat.of().formatHex(bytes)
end Crypto

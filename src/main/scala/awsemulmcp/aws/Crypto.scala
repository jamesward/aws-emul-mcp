package awsemulmcp.aws

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** SHA-256 and HMAC-SHA256 from the system libcrypto, which the binary links for TLS anyway (Scala Native's javalib has no
  * `MessageDigest`/`Mac`).
  */
object Crypto:

  @extern
  private object LibCrypto:
    def EVP_sha256(): Ptr[Byte] = extern
    def EVP_Digest(data: Ptr[Byte], count: CSize, md: Ptr[Byte], size: Ptr[CUnsignedInt], tpe: Ptr[Byte], impl: Ptr[Byte]): CInt =
      extern
    def HMAC(
        evp: Ptr[Byte],
        key: Ptr[Byte],
        keyLen: CInt,
        data: Ptr[Byte],
        dataLen: CSize,
        md: Ptr[Byte],
        mdLen: Ptr[CUnsignedInt],
    ): Ptr[Byte] = extern
  end LibCrypto

  private val DigestLength = 32

  // libcrypto reads `len` bytes from the pointer; a zero-length input still needs a valid address.
  private def nonEmpty(bytes: Array[Byte]): Array[Byte] = if bytes.isEmpty then Array[Byte](0) else bytes

  def sha256(data: Array[Byte]): Array[Byte] =
    val input = nonEmpty(data)
    val out   = Array.ofDim[Byte](DigestLength)
    val len   = stackalloc[CUnsignedInt]()
    val rc    = LibCrypto.EVP_Digest(input.at(0), data.length.toCSize, out.at(0), len, LibCrypto.EVP_sha256(), null)
    if rc != 1 then throw IllegalStateException("EVP_Digest(SHA-256) failed")
    out

  def hmacSha256(key: Array[Byte], data: Array[Byte]): Array[Byte] =
    val k   = nonEmpty(key)
    val d   = nonEmpty(data)
    val out = Array.ofDim[Byte](DigestLength)
    val len = stackalloc[CUnsignedInt]()
    val res = LibCrypto.HMAC(LibCrypto.EVP_sha256(), k.at(0), key.length, d.at(0), data.length.toCSize, out.at(0), len)
    if res == null then throw IllegalStateException("HMAC(SHA-256) failed")
    out

  def hex(bytes: Array[Byte]): String = java.util.HexFormat.of().formatHex(bytes)
end Crypto

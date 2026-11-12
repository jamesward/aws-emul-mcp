package awsemulmcp

import awsemulmcp.aws.*
import java.nio.charset.StandardCharsets.UTF_8
import kyo.*

class SigV4Test extends kyo.test.Test[Any]:

  // The worked example from the AWS Signature Version 4 documentation (IAM ListUsers).
  private val example = SigV4.Request(
    method = "GET",
    path = "/",
    rawQuery = Present("Action=ListUsers&Version=2010-05-08"),
    headers = Chunk(
      "Content-Type" -> "application/x-www-form-urlencoded; charset=utf-8",
      "Host"         -> "iam.amazonaws.com",
      "X-Amz-Date"   -> "20150830T123600Z",
    ),
    body = Array.emptyByteArray,
  )
  private val exampleCredentials =
    Credentials(AccessKeyId("AKIDEXAMPLE"), SecretAccessKey("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"))

  "sha256 and hmac match known digests" in {
    assert(Crypto.hex(Crypto.sha256(Array.emptyByteArray)) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    assert(
      Crypto.hex(Crypto.hmacSha256("key".getBytes(UTF_8), "The quick brown fox jumps over the lazy dog".getBytes(UTF_8))) ==
        "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"
    )
  }

  "canonical request matches the AWS example" in {
    val canonical = SigV4.canonicalRequest(example, "iam")
    assert(Crypto.hex(Crypto.sha256(canonical.getBytes(UTF_8))) == "f536975d06c0309214f805bb90ccff089219ecd68b2577efef23edd43b7e1a59")
  }

  "authorization header matches the AWS example" in {
    val auth = SigV4.authorization(exampleCredentials, "20150830T123600Z", "us-east-1", "iam", example)
    assert(
      auth == "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7"
    )
  }

  "S3 paths are signed as sent, other services encode them again" in {
    val request = example.copy(path = "/my%20bucket/a b", rawQuery = Absent)
    assert(SigV4.canonicalRequest(request, "s3").linesIterator.drop(1).next() == "/my%20bucket/a b")
    assert(SigV4.canonicalRequest(request, "lambda").linesIterator.drop(1).next() == "/my%2520bucket/a%20b")
  }

  "access key from a SigV4 Authorization header" in {
    val header = "AWS4-HMAC-SHA256 Credential=EMULATOR0001F5C13B52/20261112/us-east-1/sqs/aws4_request, SignedHeaders=host;x-amz-date, Signature=abc"
    assert(SigV4.accessKeyId(Present(header), Absent) == Present(AccessKeyId("EMULATOR0001F5C13B52")))
  }

  "access key from a presigned URL" in {
    val query = "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=EMULATOR7AE51DD4039A%2F20261112%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Signature=x"
    assert(SigV4.accessKeyId(Absent, Present(query)) == Present(AccessKeyId("EMULATOR7AE51DD4039A")))
  }

  "access key from SigV2" in {
    assert(SigV4.accessKeyId(Present("AWS KEY123:c2ln"), Absent) == Present(AccessKeyId("KEY123")))
    assert(SigV4.accessKeyId(Absent, Present("AWSAccessKeyId=KEY456&Signature=x")) == Present(AccessKeyId("KEY456")))
  }

  "no credentials means not an AWS request" in {
    assert(SigV4.accessKeyId(Absent, Present("a=b")) == Absent)
    assert(SigV4.accessKeyId(Present("Bearer token"), Absent) == Absent)
  }

  "amz date format" in {
    assert(SigV4.amzDate(java.time.Instant.parse("2015-08-30T12:36:00Z")) == "20150830T123600Z")
  }
end SigV4Test

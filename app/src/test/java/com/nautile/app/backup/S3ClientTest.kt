package com.nautile.app.backup

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class S3ClientTest {
    @Test fun signatureMatchesAwsExample() {
        // AWS docs, « Signature Calculations for the Authorization Header », GET Object example.
        val date = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse("20130524T000000Z")!!
        val empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val auth = S3Client.authorization(
            "GET", "/test.txt", "",
            mapOf("host" to "examplebucket.s3.amazonaws.com", "range" to "bytes=0-9", "x-amz-content-sha256" to empty, "x-amz-date" to "20130524T000000Z"),
            empty, date, "us-east-1", "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            auth,
        )
    }

    @Test fun encodesKeys() {
        assertEquals("backups/nautile%20phone.backup", S3Client.encodePath("/backups/nautile phone.backup"))
        assertEquals("a/b%C3%A9", S3Client.encodePath("a/bé"))
    }
}

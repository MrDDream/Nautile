package com.nautile.app.backup

import com.nautile.i18n.tr
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The few S3 calls a backup needs (GET, PUT with conditions, copy, delete), signed with AWS
 * Signature V4, path-style (https://endpoint/bucket/key): AWS, Backblaze B2, Cloudflare R2,
 * MinIO, Scaleway, Wasabi…
 */
class S3Client(
    endpoint: String,
    private val region: String,
    private val bucket: String,
    private val accessKey: String,
    private val secretKey: String,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(2, TimeUnit.MINUTES).build(),
) {
    class Object(val bytes: ByteArray, val etag: String?)
    class PreconditionFailed : IOException("precondition failed")

    private val base = endpoint.trim().trimEnd('/').let { if ("://" in it) it else "https://$it" }
    private val host = base.substringAfter("://").substringBefore('/')

    /** The object, or null when it does not exist. */
    fun get(key: String): Object? = call("GET", key) { r ->
        when {
            r.code == 404 -> null
            r.isSuccessful -> Object(r.body?.bytes() ?: ByteArray(0), r.header("ETag"))
            else -> fail(r.code, r.body?.string())
        }
    }

    /** Writes [bytes]; [ifMatch] only over that version, [ifNoneMatch] only if absent ([PreconditionFailed] otherwise). */
    fun put(key: String, bytes: ByteArray, ifMatch: String? = null, ifNoneMatch: Boolean = false) {
        val extra = buildMap {
            ifMatch?.let { put("if-match", it) }
            if (ifNoneMatch) put("if-none-match", "*")
        }
        call("PUT", key, bytes, extra) { r ->
            when {
                r.code == 412 || r.code == 409 -> throw PreconditionFailed()
                !r.isSuccessful -> fail(r.code, r.body?.string())
            }
        }
    }

    /** Server-side copy; false when [from] does not exist. */
    fun copy(from: String, to: String): Boolean =
        call("PUT", to, ByteArray(0), mapOf("x-amz-copy-source" to "/$bucket/${encodePath(from)}")) { r ->
            when {
                r.code == 404 -> false
                r.isSuccessful -> true
                else -> fail(r.code, r.body?.string())
            }
        }

    /** Creates the bucket (MinIO and tests; on hosted services it is made in their console). */
    fun createBucket() = call("PUT", "", ByteArray(0), bucketOnly = true) { r -> if (!r.isSuccessful && r.code != 409) fail(r.code, r.body?.string()) }

    fun delete(key: String) = call("DELETE", key) { r -> if (!r.isSuccessful && r.code != 404) fail(r.code, r.body?.string()) }

    private fun <T> call(
        method: String, key: String, body: ByteArray? = null, extra: Map<String, String> = emptyMap(), bucketOnly: Boolean = false,
        handle: (okhttp3.Response) -> T,
    ): T {
        val path = if (bucketOnly) "/$bucket" else "/$bucket/${encodePath(key)}"
        val payload = body ?: ByteArray(0)
        val now = Date()
        val headers = sortedMapOf(
            "host" to host,
            "x-amz-content-sha256" to hex(sha256(payload)),
            "x-amz-date" to amzDate(now),
        ) + extra
        val auth = authorization(method, path, "", headers, hex(sha256(payload)), now, region, accessKey, secretKey)
        val req = Request.Builder().url(base + path)
            .apply { headers.forEach { (k, v) -> if (k != "host") header(k, v) } }
            .header("Authorization", auth)
            .method(method, body?.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        return try {
            http.newCall(req).execute().use(handle)
        } catch (e: java.net.UnknownHostException) {
            throw IOException(tr("S3 : serveur introuvable (%1\$s)", host))
        }
    }

    private fun fail(code: Int, body: String?): Nothing {
        val s3code = body?.let { Regex("<Code>([^<]+)</Code>").find(it)?.groupValues?.get(1) }
        throw IOException(
            when {
                code == 403 && s3code == "SignatureDoesNotMatch" -> tr("S3 : clé secrète ou région incorrecte")
                code == 403 -> tr("S3 : accès refusé (clé ou droits sur le bucket)")
                s3code == "NoSuchBucket" -> tr("S3 : bucket introuvable")
                else -> tr("S3 : erreur %1\$s", listOfNotNull(code.toString(), s3code).joinToString(" "))
            },
        )
    }

    companion object {
        /** Each path segment percent-encoded as S3 expects (RFC 3986 unreserved kept). */
        fun encodePath(key: String) = key.trimStart('/').split('/').joinToString("/") { encode(it) }

        private fun encode(s: String) = buildString {
            s.toByteArray().forEach { b ->
                val c = b.toInt() and 0xFF
                if (c.toChar().isLetterOrDigit() && c < 128 || c.toChar() in "-_.~") append(c.toChar()) else append("%%%02X".format(c))
            }
        }

        fun amzDate(d: Date): String = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)

        /** The SigV4 « Authorization » header for this request ([headers]: lower-case names, all signed). */
        fun authorization(
            method: String, path: String, query: String, headers: Map<String, String>, payloadHash: String,
            date: Date, region: String, accessKey: String, secretKey: String, service: String = "s3",
        ): String {
            val stamp = amzDate(date)
            val day = stamp.take(8)
            val sorted = headers.toSortedMap()
            val canonical = listOf(
                method, path, query,
                sorted.entries.joinToString("") { (k, v) -> "$k:${v.trim()}\n" },
                sorted.keys.joinToString(";"),
                payloadHash,
            ).joinToString("\n")
            val scope = "$day/$region/$service/aws4_request"
            val toSign = "AWS4-HMAC-SHA256\n$stamp\n$scope\n${hex(sha256(canonical.toByteArray()))}"
            var key = hmac("AWS4$secretKey".toByteArray(), day)
            key = hmac(key, region); key = hmac(key, service); key = hmac(key, "aws4_request")
            val signature = hex(hmac(key, toSign))
            return "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=${sorted.keys.joinToString(";")}, Signature=$signature"
        }

        private fun hmac(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

        fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}

package com.nautile.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Against MinIO: scripts/s3-it.sh. */
class S3StoreIT {
    private val port = System.getProperty("nautile.s3Port")?.toIntOrNull()

    @Test fun readWriteConflictRotate() {
        assumeTrue(port != null)
        val s3 = S3Client("http://127.0.0.1:$port", "us-east-1", "nautile", "nautile", "nautile-secret")
        s3.createBucket()
        val store = S3Store(s3, "dir/nautile.backup")
        runCatching { s3.delete("dir/nautile.backup") }

        assertNull(store.read().text)
        store.write("v1".toByteArray(), store.read())
        val first = store.read()
        assertEquals("v1", first.text)
        assertTrue(first.etag != null)

        // Another device writes: the first one's write over its old version must fail.
        store.write("v2".toByteArray(), first)
        val conflict = runCatching { store.write("v3".toByteArray(), first) }.exceptionOrNull()
        assertTrue("conflict: $conflict", conflict is ConflictException)
        // Creating over an existing file must fail too.
        val created = runCatching { store.write("v4".toByteArray(), Remote(null, null)) }.exceptionOrNull()
        assertTrue("create over existing: $created", created is ConflictException)

        store.rotate()
        assertEquals("v2", s3.get("dir/nautile.backup.1")?.bytes?.decodeToString())
        store.probe()
        assertNull(s3.get("dir/nautile.backup.test"))
    }
}

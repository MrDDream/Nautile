package com.nautile.ssh

import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class AtomicWriterTest {
    private class Node(var content: ByteArray, var perms: Int, val uid: Int = ME, var gid: Int = ME, var links: Int = 1)

    /** An in-memory server; its switches make it refuse what real servers sometimes refuse. */
    private class FakeServer : AtomicWriter.Ops {
        val files = HashMap<String, Node>()
        var renameRefused = false
        var chmodRefused = false
        var targetWriteFails = false
        val created = mutableListOf<String>()
        var listings = 0
        var tempModeWhileWriting = -1
        var lstatFails = false
        var statFails = false
        /** Long names not in "ls -l" form: listed, but without counts. */
        var unknownLinks = false
        var listingFails = false

        private fun attrs(n: Node) = FileAttributes.Builder().withType(FileMode.Type.REGULAR).withPermissions(n.perms)
            .withUIDGID(n.uid, n.gid).build()

        override fun lstat(path: String): FileAttributes? {
            if (lstatFails) throw IOException("failure")
            return files[path]?.let(::attrs)
        }
        override fun stat(path: String): FileAttributes? {
            if (statFails) throw IOException("permission denied")
            return files[path]?.let(::attrs)
        }
        override fun canonicalize(path: String) = path
        override fun createExclusive(path: String): AtomicWriter.Temp? {
            if (path in files) return null
            created += path
            val node = Node(ByteArray(0), UMASK_MODE).also { files[path] = it }
            return object : AtomicWriter.Temp {
                override fun attributes() = attrs(node)
                override fun setAttributes(attributes: FileAttributes) {
                    if (attributes.has(FileAttributes.Flag.MODE)) {
                        if (chmodRefused) throw IOException("permission denied")
                        node.perms = attributes.mode.permissionsMask
                    }
                    if (attributes.has(FileAttributes.Flag.UIDGID)) node.gid = attributes.gid
                }
                override fun output(): OutputStream = object : ByteArrayOutputStream() {
                    override fun close() { tempModeWhileWriting = node.perms; node.content = toByteArray() }
                }
                override fun close() {}
            }
        }
        override fun openWrite(path: String): OutputStream {
            val node = files.getOrPut(path) { Node(ByteArray(0), UMASK_MODE) }
            node.content = ByteArray(0) // truncated at once, as O_TRUNC does
            val partName = path.substringAfterLast('/').startsWith(".")
            return object : ByteArrayOutputStream() {
                override fun close() {
                    if (targetWriteFails && !partName) throw IOException("no space left on device")
                    node.content = toByteArray()
                }
            }
        }
        override fun openRead(path: String): InputStream = ByteArrayInputStream(files.getValue(path).content)
        override fun chmod(path: String, mode: Int) {
            if (chmodRefused) throw IOException("permission denied")
            files.getValue(path).perms = mode
        }
        override fun rename(from: String, to: String, overwrite: Boolean) {
            if (renameRefused) throw IOException("operation unsupported")
            if (!overwrite && to in files) throw IOException("exists")
            files[to] = files.remove(from)!!
        }
        override fun exists(path: String) = path in files
        override fun remove(path: String) { files.remove(path) }
        override fun linkCounts(dir: String, limit: Int): AtomicWriter.Links? {
            listings++
            if (listingFails) return null
            val counts = files.filterKeys { SftpSession.parent(it) == dir }.mapKeys { it.key.substringAfterLast('/') }
                .mapValues { if (unknownLinks) null else it.value.links }
            return AtomicWriter.Links(counts, complete = true)
        }

        fun parts() = files.keys.filter { it.endsWith(".part") }
    }

    private fun AtomicWriter.put(path: String, text: String) = write(path) { it.write(text.toByteArray()) }

    @Test fun newFileWithoutProbeFile() {
        val server = FakeServer()
        val writer = AtomicWriter(server)
        assertTrue(writer.put("/srv/new.txt", "a"))
        // Only the temporary file is created: its own mode tells the server's default.
        assertEquals(1, server.created.size)
        assertEquals(PRIVATE, server.tempModeWhileWriting)
        assertEquals(UMASK_MODE, server.files.getValue("/srv/new.txt").perms)
        assertEquals("a", String(server.files.getValue("/srv/new.txt").content))
        assertTrue(server.parts().isEmpty())
        // No listing for a file that did not exist.
        assertEquals(0, server.listings)
    }

    @Test fun privateFileStaysPrivate() {
        val server = FakeServer()
        server.files["/srv/key"] = Node("old".toByteArray(), PRIVATE)
        assertTrue(AtomicWriter(server).put("/srv/key", "new"))
        assertEquals(PRIVATE, server.tempModeWhileWriting)
        assertEquals(PRIVATE, server.files.getValue("/srv/key").perms)
    }

    @Test fun refusedRenameCopiesInPlaceThenWritesDirectly() {
        val server = FakeServer()
        server.renameRefused = true
        server.files["/srv/f"] = Node("old".toByteArray(), UMASK_MODE)
        val writer = AtomicWriter(server)
        assertFalse(writer.put("/srv/f", "one"))
        assertEquals("one", String(server.files.getValue("/srv/f").content))
        assertTrue(server.parts().isEmpty())
        assertTrue(writer.renameRefused)
        val before = server.created.size
        assertFalse(writer.put("/srv/f", "two"))
        assertEquals("no temporary file once refused", before, server.created.size)
        assertEquals("two", String(server.files.getValue("/srv/f").content))
    }

    @Test fun failedCopyKeepsTheCompleteTemporaryFile() {
        val server = FakeServer()
        server.renameRefused = true
        server.targetWriteFails = true
        server.files["/srv/f"] = Node("old".toByteArray(), UMASK_MODE)
        try {
            AtomicWriter(server).put("/srv/f", "complete")
            fail("expected SftpPartialWriteException")
        } catch (e: SftpPartialWriteException) {
            val part = server.parts().single()
            assertEquals(part, e.partPath)
            assertArrayEquals("complete".toByteArray(), server.files.getValue(part).content)
        }
    }

    @Test fun refusedChmodIsRememberedAndNoTemporaryFileLeft() {
        val server = FakeServer()
        server.chmodRefused = true
        server.files["/srv/f"] = Node("old".toByteArray(), UMASK_MODE)
        val writer = AtomicWriter(server)
        assertFalse(writer.put("/srv/f", "one"))
        assertTrue(writer.chmodRefused)
        assertTrue(server.parts().isEmpty())
        assertEquals("one", String(server.files.getValue("/srv/f").content))
        val before = server.created.size
        writer.put("/srv/f", "two")
        assertEquals(before, server.created.size)
    }

    @Test fun linksAreCountedOnlyAfterCheaperChecksAndOncePerBatch() {
        val server = FakeServer()
        server.files["/srv/other"] = Node("x".toByteArray(), UMASK_MODE, uid = 0, gid = 0)
        server.files["/srv/ro"] = Node("x".toByteArray(), 0b100_100_100)
        val writer = AtomicWriter(server)
        assertFalse(writer.put("/srv/other", "y"))
        assertFalse(writer.put("/srv/ro", "y"))
        assertEquals("no listing for files the checks already exclude", 0, server.listings)
        server.files["/srv/a"] = Node("x".toByteArray(), UMASK_MODE)
        server.files["/srv/b"] = Node("x".toByteArray(), UMASK_MODE)
        assertTrue(writer.put("/srv/a", "1"))
        assertTrue(writer.put("/srv/b", "2"))
        assertEquals("one listing for the folder", 1, server.listings)
        assertTrue(server.parts().isEmpty())
    }

    @Test fun hardLinkedFileIsWrittenInPlace() {
        val server = FakeServer()
        server.files["/srv/h"] = Node("x".toByteArray(), UMASK_MODE, links = 2)
        assertFalse(AtomicWriter(server).put("/srv/h", "y"))
        assertEquals("y", String(server.files.getValue("/srv/h").content))
        assertTrue(server.parts().isEmpty())
    }

    @Test fun linkCacheExpires() {
        val server = FakeServer()
        var now = 0L
        val writer = AtomicWriter(server) { now }
        server.files["/srv/a"] = Node("x".toByteArray(), UMASK_MODE)
        writer.put("/srv/a", "1")
        now += AtomicWriter.LINK_CACHE_MS
        server.files.getValue("/srv/a").links = 2
        assertFalse(writer.put("/srv/a", "2"))
        assertEquals(2, server.listings)
    }

    @Test fun unreadableLinkCountsAreListedOncePerBatch() {
        for (fails in listOf(false, true)) {
            val server = FakeServer()
            server.unknownLinks = true
            server.listingFails = fails
            server.files["/srv/a"] = Node("x".toByteArray(), UMASK_MODE)
            server.files["/srv/b"] = Node("x".toByteArray(), UMASK_MODE)
            val writer = AtomicWriter(server)
            writer.put("/srv/a", "1")
            writer.put("/srv/b", "2")
            writer.put("/srv/a", "3")
            assertEquals("listing failed: $fails", 1, server.listings)
            assertEquals("3", String(server.files.getValue("/srv/a").content))
        }
    }

    @Test fun failedLstatNeverCountsAsMissing() {
        // lstat fails, stat works: the file is known to exist, its mode is kept.
        val server = FakeServer()
        server.files["/srv/key"] = Node("old".toByteArray(), PRIVATE)
        server.lstatFails = true
        AtomicWriter(server).put("/srv/key", "new")
        assertEquals(PRIVATE, server.files.getValue("/srv/key").perms)
        assertEquals("new", String(server.files.getValue("/srv/key").content))
        assertTrue(server.parts().isEmpty())

        // Neither works: written in place, no temporary file renamed over it.
        val blind = FakeServer()
        blind.files["/srv/key"] = Node("old".toByteArray(), PRIVATE)
        blind.lstatFails = true
        blind.statFails = true
        assertFalse(AtomicWriter(blind).put("/srv/key", "new"))
        assertTrue(blind.created.isEmpty())
        assertEquals(PRIVATE, blind.files.getValue("/srv/key").perms)
        assertEquals("new", String(blind.files.getValue("/srv/key").content))
    }

    private companion object {
        const val ME = 1000
        const val PRIVATE = 0b110_000_000
        const val UMASK_MODE = 0b110_100_100
    }
}

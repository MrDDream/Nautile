package com.nautile.ssh

import com.nautile.i18n.tr
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.PacketType
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Request
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.TimeUnit

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isLink: Boolean,
    val size: Long,
    /** Seconds since the epoch. */
    val modified: Long,
    /** Unix permission bits, e.g. 0755. */
    val permissions: Int,
)

/** A thin, thread-safe facade over an SFTP subsystem channel. */
class SftpSession internal constructor(private val sftp: SFTPClient) : Closeable {

    fun home(): String = sftp.canonicalize(".")

    fun canonical(path: String): String = sftp.canonicalize(path)

    /** Directories first, then files, both by name (case-insensitive); "." and ".." omitted. */
    fun list(path: String): List<RemoteEntry> = sftp.ls(path)
        .filter { it.name != "." && it.name != ".." }
        .map { info ->
            val a = info.attributes
            val isLink = a.type == FileMode.Type.SYMLINK
            val isDir = info.isDirectory || (isLink && runCatching { sftp.stat(info.path).type == FileMode.Type.DIRECTORY }.getOrDefault(false))
            RemoteEntry(info.name, info.path, isDir, isLink, a.size, a.mtime, a.mode.permissionsMask)
        }
        .sortedWith(compareBy<RemoteEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })

    fun stat(path: String): RemoteEntry {
        val a = sftp.stat(path)
        return RemoteEntry(path.substringAfterLast('/'), path, a.type == FileMode.Type.DIRECTORY, false, a.size, a.mtime, a.mode.permissionsMask)
    }

    fun exists(path: String): Boolean = sftp.statExistence(path) != null

    fun mkdir(path: String) = sftp.mkdir(path)

    fun rename(from: String, to: String) = sftp.rename(from, to)

    fun chmod(path: String, permissions: Int) = sftp.chmod(path, permissions)

    /** Deletes a file, or a directory with its content when [recursive]. */
    fun delete(path: String, recursive: Boolean = false) {
        val a = sftp.lstat(path)
        if (a.type != FileMode.Type.DIRECTORY) { sftp.rm(path); return }
        if (recursive) list(path).forEach { delete(it.path, it.isDirectory && !it.isLink) }
        sftp.rmdir(path)
    }

    fun openRead(path: String, offset: Long = 0): InputStream {
        val file = sftp.open(path, EnumSet.of(OpenMode.READ))
        val stream = file.ReadAheadRemoteFileInputStream(16, offset)
        return object : FilterInputStream(stream) {
            override fun close() { runCatching { super.close() }; file.close() }
        }
    }

    /** Opens [path] for writing, created if missing and truncated otherwise. */
    fun openWrite(path: String): OutputStream =
        stream(sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)))

    private fun stream(file: RemoteFile): OutputStream {
        val stream = file.RemoteFileOutputStream(0, 16)
        return object : FilterOutputStream(stream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() { runCatching { super.close() }; file.close() }
        }
    }

    /** Deletes a file, ignoring errors (cleanup of a temporary file). */
    fun removeQuietly(path: String) { runCatching { sftp.rm(path) } }

    /** Replaces the content of [path]; see [writeFile]. True when the replacement was atomic. */
    fun replaceContent(path: String, bytes: ByteArray): Boolean = writeFile(path) { it.write(bytes) }

    /**
     * Writes [path] with what [write] produces so that a dropped connection or a cancelled transfer
     * never leaves it truncated: written to a hidden temporary file beside it (made 0600 before any
     * byte is written, so a private file is never readable by others meanwhile), given the final mode
     * and owner, then renamed over it. A symlink is followed and stays a link.
     *
     * The temporary file is only used when the result is the same file for everyone: [path] is
     * missing, or is a regular file with a single link that we own, can write, and whose group we
     * can keep. Otherwise (file of another account or service, sticky folder, hard links, read-only
     * file, link that cannot be resolved, folder not writable, rename or chmod refused) the content
     * is written in place, as a plain editor would; a server that refused a rename or a chmod once is
     * written in place directly afterwards. Trade-off: the atomic path creates a new inode, so ACLs,
     * extended attributes and SELinux labels set on the old one (not visible over SFTP v3) are not
     * carried over; the folder's defaults apply. True when the replacement was atomic.
     *
     * Throws [SftpPartialWriteException] when the finished temporary file could not be copied in
     * place: it is kept, as it may be the only complete copy left.
     */
    fun writeFile(path: String, write: (OutputStream) -> Unit): Boolean = writer.write(path, write)

    private val writer = AtomicWriter(object : AtomicWriter.Ops {
        override fun lstat(path: String) = missingAsNull { sftp.lstat(path) }
        override fun stat(path: String) = missingAsNull { sftp.stat(path) }
        override fun canonicalize(path: String) = runCatching { sftp.canonicalize(path) }.getOrNull()
        override fun createExclusive(path: String): AtomicWriter.Temp? {
            // No attributes: the server applies its umask, which tells the mode a new file gets.
            val file = runCatching { sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)) }.getOrNull() ?: return null
            return object : AtomicWriter.Temp {
                override fun attributes(): FileAttributes = file.fetchAttributes()
                override fun setAttributes(attributes: FileAttributes) = file.setAttributes(attributes)
                override fun output(): OutputStream = stream(file)
                override fun close() = file.close()
            }
        }
        override fun openWrite(path: String) = this@SftpSession.openWrite(path)
        override fun openRead(path: String) = this@SftpSession.openRead(path)
        override fun chmod(path: String, mode: Int) = sftp.chmod(path, mode)
        override fun rename(from: String, to: String, overwrite: Boolean) =
            if (overwrite) sftp.rename(from, to, EnumSet.of(RenameFlags.OVERWRITE)) else sftp.rename(from, to)
        override fun exists(path: String) = sftp.statExistence(path) != null
        override fun remove(path: String) = sftp.rm(path)
        override fun linkCounts(dir: String, limit: Int) = linkCountsOf(dir, limit)
    })

    /** Null when the server answers « no such file »; other failures are thrown. */
    private inline fun <T> missingAsNull(block: () -> T): T? = try {
        block()
    } catch (e: SFTPException) {
        if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) null else throw e
    }

    /**
     * Hard link counts of the entries of [dir], read from the "ls -l" long names the server sends
     * in a directory listing (SFTP v3 attributes do not carry them; null for an entry whose long
     * name is not in that form); at most [limit] entries read, so a huge folder does not stall a
     * save. Null when the listing fails.
     */
    private fun linkCountsOf(dir: String, limit: Int): AtomicWriter.Links? = runCatching {
        val engine = sftp.sftpEngine
        val cs = engine.subsystem.remoteCharset
        fun call(req: Request): Response = engine.request(req).retrieve(engine.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        val handle = call(engine.newRequest(PacketType.OPENDIR).putString(dir, cs)).ensurePacketTypeIs(PacketType.HANDLE).readBytes()
        val counts = HashMap<String, Int?>()
        try {
            // Entries read, not parsed: long names not in "ls -l" form must not make it list everything.
            while (counts.size < limit) {
                val res = call(engine.newRequest(PacketType.READDIR).putBytes(handle))
                // STATUS (EOF) ends the listing.
                if (res.type != PacketType.NAME) return@runCatching AtomicWriter.Links(counts, complete = true)
                repeat(res.readUInt32AsInt()) {
                    val entry = res.readString(cs)
                    val longName = res.readString(cs)
                    res.readFileAttributes()
                    counts[entry] = parseLinkCount(longName)
                }
            }
            AtomicWriter.Links(counts, complete = false)
        } finally {
            runCatching { call(engine.newRequest(PacketType.CLOSE).putBytes(handle)) }
        }
    }.getOrNull()

    override fun close() = sftp.close()

    companion object {
        /**
         * Whether a file can be replaced through a temporary file renamed over it ([existing]: its
         * attributes, null when missing; [links]: its hard link count, null when unknown). The
         * owner is checked later, once the temporary file shows who we are.
         */
        fun atomicAllowed(existing: FileAttributes?, links: Int?): Boolean {
            if (existing == null) return true
            if (existing.type != FileMode.Type.REGULAR) return false
            // Read-only for its owner: a rename would silently bypass that; writing in place fails as it should.
            if (existing.mode.permissionsMask and 0b010_000_000 == 0) return false // u+w
            // Renaming over one name would split it from its other hard links.
            return links == null || links <= 1
        }

        /** The link count in an "ls -l" style line ("-rw-r--r--  1 user group …"), or null. */
        fun parseLinkCount(longName: String): Int? =
            longName.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()

        /** A hidden temporary name next to [path], for a file being written. */
        fun partName(path: String): String {
            val dir = parent(path)
            val name = path.trimEnd('/').substringAfterLast('/')
            val tag = UUID.randomUUID().toString().take(8)
            return join(dir, ".$name.nautile-$tag.part")
        }

        fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"
        fun parent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
    }
}

fun SshConnection.openSftp(): SftpSession = SftpSession(client.newSFTPClient())

/** Copies [input] to [output] reporting progress; returns bytes copied. */
fun copyWithProgress(input: InputStream, output: OutputStream, onProgress: (Long) -> Unit = {}, isCancelled: () -> Boolean = { false }): Long {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    var lastReport = 0L
    while (true) {
        if (isCancelled()) throw InterruptedException(tr("Transfert annulé"))
        val n = input.read(buf)
        if (n < 0) break
        output.write(buf, 0, n)
        total += n
        if (total - lastReport >= 256 * 1024) { onProgress(total); lastReport = total }
    }
    output.flush()
    onProgress(total)
    return total
}

package com.nautile.ssh

import com.nautile.i18n.tr

import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

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

    fun openWrite(path: String): OutputStream {
        val file: RemoteFile = sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
        val stream = file.RemoteFileOutputStream(0, 16)
        return object : FilterOutputStream(stream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() { runCatching { super.close() }; file.close() }
        }
    }

    override fun close() = sftp.close()

    companion object {
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

package com.nautile.ssh

import com.nautile.i18n.tr
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** The temporary copy could not be put in place: [partPath] holds the complete new content, kept on the server. */
class SftpPartialWriteException(val partPath: String, val target: String, cause: Throwable) : IOException(
    tr("Écriture de %1\$s interrompue : le fichier est peut-être incomplet. La version complète est conservée dans %2\$s", target, partPath),
    cause,
)

/**
 * The replacement strategy of [SftpSession.writeFile], over the few SFTP operations it needs (a
 * fake in tests). Remembers, for the session, a server that refuses renames or chmods, and the
 * link counts of a folder for a short while (a batch of transfers lists it once).
 */
internal class AtomicWriter(private val ops: Ops, private val clock: () -> Long = System::currentTimeMillis) {
    interface Ops {
        /** Null only when [path] does not exist; throws when that cannot be told (permission, server error). */
        fun lstat(path: String): FileAttributes?
        /** Like [lstat], following links. */
        fun stat(path: String): FileAttributes?
        fun canonicalize(path: String): String?
        /** Creates [path] exclusively, without attributes (the server's default mode); null when refused. */
        fun createExclusive(path: String): Temp?
        /** Created if missing, truncated otherwise. */
        fun openWrite(path: String): OutputStream
        fun openRead(path: String): InputStream
        fun chmod(path: String, mode: Int)
        fun rename(from: String, to: String, overwrite: Boolean)
        fun exists(path: String): Boolean
        fun remove(path: String)
        fun linkCounts(dir: String, limit: Int): Links?
    }

    /** An open temporary file; [output] closes it once written. */
    interface Temp : Closeable {
        fun attributes(): FileAttributes
        fun setAttributes(attributes: FileAttributes)
        fun output(): OutputStream
    }

    /**
     * Link counts by entry name (null for an entry listed without a readable count); [complete]
     * when the whole folder was listed.
     */
    class Links(val counts: Map<String, Int?>, val complete: Boolean)

    /** The server refused a rename or a chmod once: later writes go in place directly. */
    @Volatile var renameRefused = false
        private set
    @Volatile var chmodRefused = false
        private set

    private class Cached(val at: Long, val links: Links)
    private val linkCache = HashMap<String, Cached>()

    fun write(path: String, write: (OutputStream) -> Unit): Boolean {
        // Only a definite « no such file » counts as missing: renaming over a file that is there
        // but could not be examined would drop its mode and owner.
        val lst = try {
            ops.lstat(path)
        } catch (_: Exception) {
            // Whether it is a link is unknown: replace the file it resolves to, if that can be told.
            val st = runCatching { ops.stat(path) }.getOrNull() ?: return inPlace(path, write)
            val real = runCatching { ops.canonicalize(path) }.getOrNull() ?: return inPlace(path, write)
            return replace(real, st, write)
        }
        val target = if (lst?.type == FileMode.Type.SYMLINK) runCatching { ops.canonicalize(path) }.getOrNull() else path
        // A link that cannot be resolved: written through, as a plain editor would.
        if (target == null) return inPlace(path, write)
        val existing = if (target == path) lst else try {
            ops.stat(target)
        } catch (_: Exception) {
            return inPlace(path, write)
        }
        return replace(target, existing, write)
    }

    /** Replaces [target] ([existing]: its attributes, null when missing) through a temporary file when allowed. */
    private fun replace(target: String, existing: FileAttributes?, write: (OutputStream) -> Unit): Boolean {
        if (renameRefused || chmodRefused || !SftpSession.atomicAllowed(existing, null)) return inPlace(target, write)
        val tmp = SftpSession.partName(target)
        // Folder not writable (or not allowing new files): in place.
        val file = ops.createExclusive(tmp) ?: return inPlace(target, write)
        val mode: Int
        try {
            val mine = file.attributes()
            if (existing != null) {
                if (mine.uid != existing.uid) error("owner")
                if (mine.gid != existing.gid) {
                    runCatching { file.setAttributes(FileAttributes.Builder().withUIDGID(existing.uid, existing.gid).build()) }
                    check(sameOwner(file.attributes(), existing)) { "group" }
                }
                // Only now, the cheaper checks passed: the link count costs a directory listing.
                if (!SftpSession.atomicAllowed(existing, linkCount(target))) error("links")
            }
            // A new file gets what the server gives a new file (its umask), read on the temporary one.
            mode = existing?.mode?.permissionsMask ?: mine.mode.permissionsMask
            try {
                file.setAttributes(FileAttributes.Builder().withPermissions(PRIVATE).build())
            } catch (e: Exception) {
                chmodRefused = true
                throw e
            }
        } catch (_: Exception) {
            // Nothing written yet: drop the temporary file and write in place instead.
            runCatching { file.close() }
            removeQuietly(tmp)
            return inPlace(target, write)
        }
        try {
            file.output().use(write)
        } catch (e: Throwable) {
            // The old file is untouched.
            removeQuietly(tmp)
            throw e
        }
        // After the chown: changing the owner clears set-id bits.
        if (runCatching { ops.chmod(tmp, mode) }.isFailure) {
            chmodRefused = true
        } else if (runCatching { moveOver(tmp, target, existing == null) }.isSuccess) {
            return true
        } else {
            renameRefused = true
        }
        // Copy the finished temporary file in place. If that fails (disk full, connection lost), the
        // target may be cut off: the temporary file is then the only complete copy, keep it.
        try {
            ops.openRead(tmp).use { inp -> ops.openWrite(target).use { out -> inp.copyTo(out, 64 * 1024) } }
        } catch (e: Exception) {
            throw SftpPartialWriteException(tmp, target, e)
        }
        removeQuietly(tmp)
        return false
    }

    private fun inPlace(path: String, write: (OutputStream) -> Unit): Boolean {
        ops.openWrite(path).use(write)
        return false
    }

    /** Renames [from] over [to] in one step; a plain rename is enough when [to] does not exist. */
    private fun moveOver(from: String, to: String, missing: Boolean) {
        val atomic = runCatching { ops.rename(from, to, overwrite = true) }
        if (atomic.isSuccess) return
        if (!missing || ops.exists(to)) throw atomic.exceptionOrNull()!!
        ops.rename(from, to, overwrite = false)
    }

    /** The hard link count of [path], from a listing of its folder kept [LINK_CACHE_MS]; null when unknown. */
    private fun linkCount(path: String): Int? {
        val dir = SftpSession.parent(path)
        val name = path.trimEnd('/').substringAfterLast('/')
        val now = clock()
        synchronized(linkCache) {
            val c = linkCache[dir]?.takeIf { now - it.at < LINK_CACHE_MS }
            // A name missing from a complete listing is newer than it: list again.
            if (c != null && (c.links.counts.containsKey(name) || !c.links.complete)) return c.links.counts[name]
        }
        // A failed listing is remembered too (as unknown), so that a batch does not retry it per file.
        val links = ops.linkCounts(dir, LINK_SCAN_LIMIT) ?: Links(emptyMap(), complete = false)
        synchronized(linkCache) {
            linkCache.entries.removeIf { now - it.value.at >= LINK_CACHE_MS }
            linkCache[dir] = Cached(now, links)
        }
        return links.counts[name]
    }

    private fun removeQuietly(path: String) {
        runCatching { ops.remove(path) }
    }

    private fun sameOwner(a: FileAttributes, b: FileAttributes) = a.uid == b.uid && a.gid == b.gid

    companion object {
        const val LINK_SCAN_LIMIT = 1000
        /** How long a folder's link counts are reused: long enough for a batch, short against new hard links. */
        const val LINK_CACHE_MS = 10_000L
        private const val PRIVATE = 0b110_000_000 // 0600
    }
}

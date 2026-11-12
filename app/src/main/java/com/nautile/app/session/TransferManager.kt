package com.nautile.app.session

import com.nautile.i18n.tr

import android.content.Context
import android.net.Uri
import com.nautile.ssh.SftpSession
import com.nautile.ssh.copyWithProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class Transfer(
    val id: Long,
    val label: String,
    val detail: String,
    val total: Long,
    val done: Long = 0,
    val state: State = State.RUNNING,
    val error: String? = null,
) {
    enum class State { RUNNING, DONE, FAILED, CANCELLED }
    val progress: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** File transfers that keep running while the app is in the background (foreground service). */
class TransferManager(private val context: Context, private val pool: SftpPool, private val scope: CoroutineScope) {
    private val ids = AtomicLong()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    /** Called with the remote directory that changed, so open browsers can refresh. */
    var onRemoteChanged: (hostId: Long, dir: String) -> Unit = { _, _ -> }

    fun upload(hostId: Long, uri: Uri, remoteDir: String, name: String, size: Long) =
        launch(name, tr("Envoi vers %1\$s", remoteDir), size) { t ->
            pool.with(hostId) { sftp ->
                val input = context.contentResolver.openInputStream(uri) ?: error(tr("Fichier illisible"))
                input.use { inp -> sftp.openWrite(SftpSession.join(remoteDir, name)).use { out -> copy(t, inp, out) } }
            }
            onRemoteChanged(hostId, remoteDir)
        }

    fun download(hostId: Long, remotePath: String, size: Long, uri: Uri) =
        launch(remotePath.substringAfterLast('/'), tr("Téléchargement"), size) { t ->
            pool.with(hostId) { sftp ->
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Destination inaccessible")
                output.use { out -> sftp.openRead(remotePath).use { inp -> copy(t, inp, out) } }
            }
        }

    /** Server-to-server copy, streamed through the phone. */
    fun copyBetween(fromHost: Long, fromPath: String, size: Long, toHost: Long, toDir: String, toLabel: String) =
        launch(fromPath.substringAfterLast('/'), tr("Copie vers %1\$s:%2\$s", toLabel, toDir), size) { t ->
            pool.with(fromHost) { src ->
                pool.with(toHost) { dst ->
                    src.openRead(fromPath).use { inp ->
                        dst.openWrite(SftpSession.join(toDir, fromPath.substringAfterLast('/'))).use { out -> copy(t, inp, out) }
                    }
                }
            }
            onRemoteChanged(toHost, toDir)
        }

    fun cancel(id: Long) {
        jobs.remove(id)?.cancel()
        set(id) { it.copy(state = Transfer.State.CANCELLED) }
    }

    fun clearFinished() = _transfers.update { list -> list.filter { it.state == Transfer.State.RUNNING } }

    private fun copy(t: Long, inp: java.io.InputStream, out: java.io.OutputStream) {
        val job = jobs[t]
        copyWithProgress(inp, out, onProgress = { n -> set(t) { it.copy(done = n) } }, isCancelled = { job?.isCancelled == true })
    }

    private fun launch(label: String, detail: String, size: Long, block: suspend (Long) -> Unit): Long {
        val id = ids.incrementAndGet()
        _transfers.update { it + Transfer(id, label, detail, size) }
        jobs[id] = scope.launch(Dispatchers.IO) {
            runCatching { block(id) }
                .onSuccess { set(id) { t -> t.copy(state = Transfer.State.DONE, done = maxOf(t.done, t.total)) } }
                .onFailure { e ->
                    if (_transfers.value.firstOrNull { it.id == id }?.state != Transfer.State.CANCELLED)
                        set(id) { it.copy(state = Transfer.State.FAILED, error = ChannelBackend.describe(e)) }
                }
            jobs.remove(id)
        }
        return id
    }

    private fun set(id: Long, f: (Transfer) -> Transfer) = _transfers.update { list -> list.map { if (it.id == id) f(it) else it } }
}

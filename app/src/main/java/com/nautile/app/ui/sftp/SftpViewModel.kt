package com.nautile.app.ui.sftp

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nautile.app.NautileApp
import com.nautile.app.session.ChannelBackend
import com.nautile.app.session.SftpPool
import com.nautile.i18n.tr
import com.nautile.ssh.RemoteEntry
import com.nautile.ssh.SftpSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SftpUiState(
    val hostAlias: String = "",
    val path: String = "",
    val entries: List<RemoteEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val showHidden: Boolean = false,
    /** Paths of the entries picked by long-press; not empty = selection mode. */
    val selected: Set<String> = emptySet(),
)

class SftpViewModel(private val app: NautileApp, handle: SavedStateHandle) : ViewModel() {
    val hostId: Long = handle.get<Long>("hostId") ?: 0L
    private val pool = app.sessions.sftp
    private var lease: SftpPool.Lease? = null
    private var sftp: SftpSession? = null
    private val _state = MutableStateFlow(SftpUiState())
    val state: StateFlow<SftpUiState> = _state.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    val transfers = app.sessions.transfers.transfers

    init {
        viewModelScope.launch {
            app.sessions.transfers.remoteChanges.collect { (h, dir) -> if (h == hostId && dir == _state.value.path) refresh() }
        }
        viewModelScope.launch {
            val alias = app.hostRepository.get(hostId)?.alias.orEmpty()
            _state.update { it.copy(hostAlias = alias) }
            runCatching { pool.acquire(hostId) }
                .onSuccess { l ->
                    lease = l
                    val s = l.sftp
                    sftp = s
                    val start = handle.get<String>("path")?.takeIf { it.isNotBlank() } ?: withContext(Dispatchers.IO) { s.home() }
                    open(start)
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = ChannelBackend.describe(e)) } }
        }
    }

    fun consumeMessage() { _message.value = null }
    fun toggleHidden() = _state.update { it.copy(showHidden = !it.showHidden) }

    /** The folders visited before the current one, for the back button. */
    private val visited = ArrayDeque<String>()
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    /** Opens [path]; [remember]: the folder left is kept to come back to. */
    fun open(path: String, remember: Boolean = true) {
        val s = sftp ?: return
        val from = _state.value.path
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { s.canonical(path) to s.list(path) } }
                .onSuccess { (p, list) ->
                    if (remember && from.isNotEmpty() && p != from && visited.lastOrNull() != from) visited.addLast(from)
                    _canGoBack.value = visited.isNotEmpty()
                    _state.update { st ->
                        // A selection belongs to its folder; after a refresh, only what still exists stays picked.
                        val keep = if (p == st.path) st.selected.intersect(list.mapTo(HashSet()) { it.path }) else emptySet()
                        st.copy(path = p, entries = list, loading = false, selected = keep)
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = ChannelBackend.describe(e)) } }
        }
    }

    fun refresh() = open(_state.value.path, remember = false)
    fun up() = open(SftpSession.parent(_state.value.path))

    /** Back to the folder seen before; false when there is none (the screen closes). */
    fun back(): Boolean {
        val previous = visited.removeLastOrNull() ?: return false
        _canGoBack.value = visited.isNotEmpty()
        open(previous, remember = false)
        return true
    }

    private fun op(success: String, block: (SftpSession) -> Unit) {
        val s = sftp ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { block(s) } }
                .onSuccess { _message.value = success; refresh() }
                .onFailure { _message.value = ChannelBackend.describe(it) }
        }
    }

    fun toggleSelected(e: RemoteEntry) = _state.update {
        it.copy(selected = if (e.path in it.selected) it.selected - e.path else it.selected + e.path)
    }
    fun selectAll(entries: List<RemoteEntry>) = _state.update { it.copy(selected = entries.mapTo(LinkedHashSet()) { e -> e.path }) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    /**
     * Runs [block] on each of [entries] in turn, then reports how many went through: the first failure is
     * shown, the others are still tried (one locked file must not stop the rest).
     */
    private fun opEach(entries: List<RemoteEntry>, done: (Int) -> String, block: (SftpSession, RemoteEntry) -> Unit) {
        val s = sftp ?: return
        clearSelection()
        viewModelScope.launch {
            var ok = 0
            var first: Throwable? = null
            withContext(Dispatchers.IO) {
                entries.forEach { e -> runCatching { block(s, e) }.onSuccess { ok++ }.onFailure { if (first == null) first = it } }
            }
            _message.value = first?.let { tr("%1\$s sur %2\$s : %3\$s", ok, entries.size, ChannelBackend.describe(it)) } ?: done(ok)
            refresh()
        }
    }

    fun deleteMany(entries: List<RemoteEntry>) =
        opEach(entries, { n -> if (n == 1) tr("Supprimé") else tr("%1\$s éléments supprimés", n) }) { s, e -> s.delete(e.path, recursive = e.isDirectory) }

    fun chmodMany(entries: List<RemoteEntry>, octal: String) =
        opEach(entries, { tr("Permissions modifiées") }) { s, e -> s.chmod(e.path, octal.toInt(8) and 0xFFF) }

    fun mkdir(name: String) = op(tr("Dossier créé")) { it.mkdir(SftpSession.join(_state.value.path, name)) }
    fun rename(e: RemoteEntry, name: String) = op(tr("Renommé")) { it.rename(e.path, SftpSession.join(SftpSession.parent(e.path), name)) }
    fun delete(e: RemoteEntry) = op(tr("Supprimé")) { it.delete(e.path, recursive = e.isDirectory) }
    fun chmod(e: RemoteEntry, octal: String) = op(tr("Permissions modifiées")) { it.chmod(e.path, octal.toInt(8) and 0xFFF) }

    /** Sends the files picked on the phone (one or several) into the folder shown. */
    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val dir = _state.value.path
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    uri to (app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) (c.getString(0) ?: tr("fichier")) to (if (c.isNull(1)) -1L else c.getLong(1)) else null
                    } ?: (tr("fichier") to -1L))
                }
            }
            picked.forEach { (uri, info) -> app.sessions.transfers.upload(hostId, uri, dir, info.first, info.second) }
            _message.value = if (picked.size == 1) tr("Envoi de %1\$s lancé", picked[0].second.first) else tr("Envoi de %1\$s fichiers lancé", picked.size)
        }
    }

    fun download(e: RemoteEntry, uri: Uri) {
        app.sessions.transfers.download(hostId, e.path, e.size, uri)
        _message.value = tr("Téléchargement de %1\$s lancé", e.name)
    }

    /**
     * Downloads the selected files into the phone folder [tree] (picked once for all of them). A name already
     * there gets the provider's own « (1) » suffix; folders are left out (one file per transfer).
     */
    fun downloadMany(entries: List<RemoteEntry>, tree: Uri) {
        val files = entries.filter { !it.isDirectory }
        clearSelection()
        viewModelScope.launch {
            val failed = withContext(Dispatchers.IO) {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                files.count { e ->
                    val uri = runCatching { DocumentsContract.createDocument(app.contentResolver, parent, "application/octet-stream", e.name) }.getOrNull()
                    if (uri != null) app.sessions.transfers.download(hostId, e.path, e.size, uri)
                    uri == null
                }
            }
            _message.value = when {
                failed > 0 -> tr("%1\$s fichier(s) impossible(s) à créer dans ce dossier", failed)
                else -> skippedFolders(entries, tr("Téléchargement de %1\$s fichier(s) lancé", files.size))
            }
        }
    }

    fun copyTo(entries: List<RemoteEntry>, toHost: Long, toDir: String, toLabel: String) {
        val files = entries.filter { !it.isDirectory }
        clearSelection()
        files.forEach { e -> app.sessions.transfers.copyBetween(hostId, e.path, e.size, toHost, toDir, toLabel) }
        _message.value = skippedFolders(entries, tr("Copie vers %1\$s lancée", toLabel))
    }

    /** [message], plus a word on the folders a file-only action did not take. */
    private fun skippedFolders(entries: List<RemoteEntry>, message: String): String {
        val dirs = entries.count { it.isDirectory }
        return if (dirs == 0) message else tr("%1\$s (dossiers ignorés : %2\$s)", message, dirs)
    }

    override fun onCleared() {
        lease?.let { l -> app.appScope.launch { withContext(NonCancellable) { pool.release(l) } } }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { SftpViewModel(this[APPLICATION_KEY] as NautileApp, createSavedStateHandle()) }
        }
    }
}

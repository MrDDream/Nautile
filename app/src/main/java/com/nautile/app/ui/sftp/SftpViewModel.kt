package com.nautile.app.ui.sftp

import com.nautile.i18n.tr

import android.net.Uri
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
)

class SftpViewModel(private val app: NautileApp, handle: SavedStateHandle) : ViewModel() {
    val hostId: Long = handle.get<Long>("hostId") ?: 0L
    private val pool = app.sessions.sftp
    private var sftp: SftpSession? = null
    private val _state = MutableStateFlow(SftpUiState())
    val state: StateFlow<SftpUiState> = _state.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    val transfers = app.sessions.transfers.transfers

    init {
        app.sessions.transfers.onRemoteChanged = { h, dir -> if (h == hostId && dir == _state.value.path) refresh() }
        viewModelScope.launch {
            val alias = app.hostRepository.get(hostId)?.alias.orEmpty()
            _state.update { it.copy(hostAlias = alias) }
            runCatching { pool.acquire(hostId) }
                .onSuccess { s ->
                    sftp = s
                    val start = handle.get<String>("path")?.takeIf { it.isNotBlank() } ?: withContext(Dispatchers.IO) { s.home() }
                    open(start)
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = ChannelBackend.describe(e)) } }
        }
    }

    fun consumeMessage() { _message.value = null }
    fun toggleHidden() = _state.update { it.copy(showHidden = !it.showHidden) }

    fun open(path: String) {
        val s = sftp ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { s.canonical(path) to s.list(path) } }
                .onSuccess { (p, list) -> _state.update { it.copy(path = p, entries = list, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = ChannelBackend.describe(e)) } }
        }
    }

    fun refresh() = open(_state.value.path)
    fun up() = open(SftpSession.parent(_state.value.path))

    private fun op(success: String, block: (SftpSession) -> Unit) {
        val s = sftp ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { block(s) } }
                .onSuccess { _message.value = success; refresh() }
                .onFailure { _message.value = ChannelBackend.describe(it) }
        }
    }

    fun mkdir(name: String) = op(tr("Dossier créé")) { it.mkdir(SftpSession.join(_state.value.path, name)) }
    fun rename(e: RemoteEntry, name: String) = op(tr("Renommé")) { it.rename(e.path, SftpSession.join(SftpSession.parent(e.path), name)) }
    fun delete(e: RemoteEntry) = op(tr("Supprimé")) { it.delete(e.path, recursive = e.isDirectory) }
    fun chmod(e: RemoteEntry, octal: String) = op(tr("Permissions modifiées")) { it.chmod(e.path, octal.toInt(8)) }

    fun upload(uri: Uri) {
        viewModelScope.launch {
            val (name, size) = withContext(Dispatchers.IO) {
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) (c.getString(0) ?: tr("fichier")) to (if (c.isNull(1)) -1L else c.getLong(1)) else null
                } ?: (tr("fichier") to -1L)
            }
            app.sessions.transfers.upload(hostId, uri, _state.value.path, name, size)
            _message.value = tr("Envoi de %1\$s lancé", name)
        }
    }

    fun download(e: RemoteEntry, uri: Uri) {
        app.sessions.transfers.download(hostId, e.path, e.size, uri)
        _message.value = tr("Téléchargement de %1\$s lancé", e.name)
    }

    fun copyTo(e: RemoteEntry, toHost: Long, toDir: String, toLabel: String) {
        app.sessions.transfers.copyBetween(hostId, e.path, e.size, toHost, toDir, toLabel)
        _message.value = tr("Copie vers %1\$s lancée", toLabel)
    }

    override fun onCleared() {
        if (sftp != null) app.appScope.launch(NonCancellable) { pool.release(hostId) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { SftpViewModel(this[APPLICATION_KEY] as NautileApp, createSavedStateHandle()) }
        }
    }
}

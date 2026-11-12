package com.nautile.app.ui.keys

import com.nautile.i18n.tr

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nautile.app.NautileApp
import com.nautile.app.keys.DuplicateKeyException
import com.nautile.data.model.Identity
import com.nautile.data.model.KnownHost
import com.nautile.data.model.SshKey
import com.nautile.ssh.KeyAlgorithm
import com.nautile.ssh.PassphraseRequiredException
import com.nautile.ssh.WrongPassphraseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportDraft(val name: String, val text: String, val needsPassphrase: Boolean = false, val error: String? = null)

class KeychainViewModel(private val app: NautileApp) : ViewModel() {
    private val repo = app.keyRepository
    private val knownDao = app.database.knownHostDao()

    val keys: StateFlow<List<SshKey>> = repo.keys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val usage: StateFlow<Map<Long, Int>> = repo.usage.map { l -> l.associate { it.keyId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val identities: StateFlow<List<Identity>> = repo.identities.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val knownHosts: StateFlow<List<KnownHost>> = knownDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()
    private val _draft = MutableStateFlow<ImportDraft?>(null)
    val draft: StateFlow<ImportDraft?> = _draft.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() { _message.value = null }

    fun generate(name: String, algorithm: KeyAlgorithm, onDone: () -> Unit) {
        _generating.value = true
        viewModelScope.launch {
            runCatching { repo.generate(name, algorithm) }
                .onSuccess { _message.value = tr("Clé « %1\$s » générée", it.name); onDone() }
                .onFailure { _message.value = tr("Génération impossible : %1\$s", it.message) }
            _generating.value = false
        }
    }

    fun startPaste() { _draft.value = ImportDraft(tr("Clé importée"), "") }
    fun cancelImport() { _draft.value = null }

    /** Reads a key file and imports it directly, asking for a passphrase only if needed. */
    fun importFile(uri: Uri) {
        viewModelScope.launch {
            val (name, text) = withContext(Dispatchers.IO) {
                val display = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
                (display ?: tr("Clé importée")) to app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
            }
            submitImport(name, text, null, false)
        }
    }

    fun submitImport(name: String, text: String, passphrase: String?, remember: Boolean) {
        viewModelScope.launch {
            runCatching { repo.import(name, text, passphrase, remember) }
                .onSuccess { _draft.value = null; _message.value = tr("Clé « %1\$s » importée", it.name) }
                .onFailure { e ->
                    _draft.value = when (e) {
                        is PassphraseRequiredException -> ImportDraft(name, text, needsPassphrase = true)
                        is WrongPassphraseException -> ImportDraft(name, text, needsPassphrase = true, error = tr("Phrase de passe incorrecte"))
                        is DuplicateKeyException -> ImportDraft(name, text, passphrase != null, e.message)
                        else -> ImportDraft(name, text, passphrase != null, e.message ?: tr("Clé illisible"))
                    }
                }
        }
    }

    fun rename(key: SshKey, name: String) = viewModelScope.launch { repo.rename(key, name) }
    fun forgetPassphrase(key: SshKey) = viewModelScope.launch { repo.forgetPassphrase(key); _message.value = tr("Phrase de passe oubliée") }
    fun delete(key: SshKey) = viewModelScope.launch { repo.delete(key); _message.value = tr("Clé supprimée") }
    fun canReencrypt(key: SshKey) = repo.canReencrypt(key)

    fun exportPrivate(key: SshKey, passphrase: String?, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val text = repo.exportPrivate(key, passphrase)
                withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } }
            }.onSuccess { _message.value = tr("Clé privée exportée") }
                .onFailure { _message.value = tr("Export impossible : %1\$s", it.message) }
        }
    }

    fun saveIdentity(identity: Identity) = viewModelScope.launch { repo.saveIdentity(identity) }
    fun deleteIdentity(identity: Identity) = viewModelScope.launch { repo.deleteIdentity(identity) }
    fun forgetHost(k: KnownHost) = viewModelScope.launch { knownDao.delete(k) }

    companion object {
        val Factory = viewModelFactory { initializer { KeychainViewModel(this[APPLICATION_KEY] as NautileApp) } }
    }
}

package com.nautile.app.ui.hosts

import com.nautile.i18n.tr

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nautile.app.NautileApp
import com.nautile.data.importer.CsvHostParser
import com.nautile.data.importer.ImportedHost
import com.nautile.data.importer.SshConfigParser
import com.nautile.data.model.AuthMethod
import com.nautile.data.model.DEFAULT_COLOR
import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import com.nautile.data.model.Protocol
import com.nautile.data.model.defaultPort
import com.nautile.data.model.ProxyKind
import com.nautile.data.model.Identity
import com.nautile.data.model.SshKey
import com.nautile.ssh.PassphraseRequiredException
import com.nautile.data.repo.HostRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HostForm(
    /** SSH (Mosh is a flag of SSH), TELNET, RDP, SERIAL or LOCAL. */
    val kind: Protocol = Protocol.SSH,
    val knock: String = "",
    val baud: String = "115200",
    val alias: String = "",
    val hostname: String = "",
    val port: String = "22",
    val username: String = "",
    val auth: AuthMethod = AuthMethod.PASSWORD,
    val password: String = "",
    val keyId: Long? = null,
    val identityId: Long? = null,
    val agentForwarding: Boolean = false,
    val proxyType: ProxyKind? = null,
    val proxyHost: String = "",
    val proxyPort: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val group: String = "",
    val color: Long = DEFAULT_COLOR,
    val tags: String = "",
    val jumpHostId: Long? = null,
    val mosh: Boolean = false,
    val startupCommand: String = "",
    val rdpDomain: String = "",
    /** `WIDTHxHEIGHT`, empty for the screen size. */
    val rdpResolution: String = "",
    /** Percent, empty to pick from the screen density. */
    val rdpScale: String = "",
    /** NLA, TLS, RDP or null to negotiate. */
    val rdpSecurity: String? = null,
    val rdpAudio: Boolean = true,
    val rdpConsole: Boolean = false,
) {
    val portValue: Int? get() = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val proxyPortValue: Int? get() = proxyPort.toIntOrNull()?.takeIf { it in 1..65535 }
    val needsAddress: Boolean get() = kind == Protocol.SSH || kind == Protocol.TELNET || kind == Protocol.RDP || kind == Protocol.VNC
    val rdpResolutionValid: Boolean get() = rdpResolution.isBlank() || com.nautile.app.rdp.RdpManager.parseResolution(rdpResolution) != null
    val rdpScaleValue: Int? get() = rdpScale.toIntOrNull()?.takeIf { it in 100..500 }
    val knockValid: Boolean get() = knock.isBlank() || runCatching { com.nautile.ssh.PortKnocker.parse(knock) }.isSuccess
    val isValid: Boolean
        get() = (!needsAddress || (hostname.isNotBlank() && portValue != null && knockValid)) &&
            (kind != Protocol.SERIAL || baud.toIntOrNull() != null) &&
            (kind != Protocol.RDP || (rdpResolutionValid && (rdpScale.isBlank() || rdpScaleValue != null))) &&
            (proxyType == null || (proxyHost.isNotBlank() && (proxyPort.isEmpty() || proxyPortValue != null)))
}

class HostEditViewModel(
    private val app: NautileApp,
    private val repo: HostRepository,
    handle: SavedStateHandle,
) : ViewModel() {
    val hostId: Long = handle.get<Long>("id") ?: 0L
    private var original: Host? = null

    private val _form = MutableStateFlow(HostForm())
    val form: StateFlow<HostForm> = _form.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val groups: StateFlow<List<HostGroup>> = repo.groups.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val keys: StateFlow<List<SshKey>> = app.keyRepository.keys.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val identities: StateFlow<List<Identity>> = app.keyRepository.identities.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val jumpCandidates: StateFlow<List<Host>> = repo.hosts
        .map { list -> list.filter { it.id != hostId && it.protocol in setOf(Protocol.SSH, Protocol.MOSH) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        if (hostId == 0L) {
            // Type chosen with « + », and an address when saving a quick connection from the history.
            val kind = handle.get<String>("kind")?.let { k -> runCatching { Protocol.valueOf(k) }.getOrNull() } ?: Protocol.SSH
            val base = HostForm(
                kind = if (kind == Protocol.MOSH) Protocol.SSH else kind,
                mosh = kind == Protocol.MOSH,
                port = kind.defaultPort.takeIf { it > 0 }?.toString() ?: "22",
            )
            val target = handle.get<String>("target")?.let { QuickConnect.parse(it, kind) }
            _form.value = if (target == null) base
            else base.copy(hostname = target.hostname, username = target.username, port = target.port.toString())
        }
        if (hostId != 0L) viewModelScope.launch {
            val host = repo.get(hostId) ?: return@launch
            original = host
            val groupName = host.groupId?.let { id -> repo.groups.first().firstOrNull { it.id == id }?.name }
            _form.value = HostForm(
                alias = host.alias,
                hostname = host.hostname,
                port = host.port.toString(),
                username = host.username,
                auth = if (host.authMethod == AuthMethod.AGENT) AuthMethod.PASSWORD else host.authMethod,
                password = host.password.orEmpty(),
                keyId = host.keyId,
                identityId = host.identityId,
                agentForwarding = host.agentForwarding,
                proxyType = host.proxyType,
                proxyHost = host.proxyHost.orEmpty(),
                proxyPort = host.proxyPort?.toString().orEmpty(),
                proxyUsername = host.proxyUsername.orEmpty(),
                proxyPassword = host.proxyPassword.orEmpty(),
                group = groupName.orEmpty(),
                color = host.color,
                tags = host.tags,
                jumpHostId = host.jumpHostId,
                mosh = host.protocol == Protocol.MOSH,
                kind = if (host.protocol == Protocol.MOSH) Protocol.SSH else host.protocol,
                knock = host.knockSequence.orEmpty(),
                baud = (host.serialBaud ?: 115_200).toString(),
                startupCommand = host.startupCommand.orEmpty(),
                rdpDomain = host.rdpDomain.orEmpty(),
                rdpResolution = host.rdpResolution.orEmpty(),
                rdpScale = host.rdpScale?.toString().orEmpty(),
                rdpSecurity = host.rdpSecurity,
                rdpAudio = host.rdpAudio,
                rdpConsole = host.rdpConsole,
            )
        }
    }

    fun update(transform: (HostForm) -> HostForm) = _form.update(transform)
    fun consumeMessage() { _message.value = null }

    fun save(onSaved: (Long) -> Unit) {
        val f = _form.value
        if (!f.isValid) return
        viewModelScope.launch {
            val groupId = f.group.trim().takeIf { it.isNotEmpty() }?.let { repo.groupId(it) }
            val base = original ?: Host(alias = "", hostname = "")
            val id = repo.save(
                base.copy(
                    alias = f.alias.trim().ifEmpty { f.hostname.trim() }.ifEmpty { if (f.kind == Protocol.LOCAL) tr("Ce téléphone") else tr("Port série") },
                    hostname = when (f.kind) { Protocol.LOCAL -> "localhost"; Protocol.SERIAL -> "usb"; else -> f.hostname.trim() },
                    port = if (f.needsAddress) f.portValue!! else 0,
                    username = f.username.trim(),
                    protocol = if (f.kind == Protocol.SSH && f.mosh) Protocol.MOSH else f.kind,
                    knockSequence = f.knock.trim().takeIf { f.needsAddress && it.isNotEmpty() },
                    serialBaud = f.baud.toIntOrNull().takeIf { f.kind == Protocol.SERIAL },
                    authMethod = if ((f.kind == Protocol.RDP && f.auth == AuthMethod.KEY) || f.kind == Protocol.VNC) AuthMethod.PASSWORD else f.auth,
                    // Also used by the terminal's key button to answer sudo prompts.
                    password = f.password.takeIf { it.isNotEmpty() },
                    keyId = f.keyId.takeIf { f.auth == AuthMethod.KEY },
                    identityId = f.identityId.takeIf { f.auth == AuthMethod.IDENTITY },
                    agentForwarding = f.agentForwarding,
                    proxyType = f.proxyType,
                    proxyHost = f.proxyHost.trim().takeIf { f.proxyType != null && it.isNotEmpty() },
                    proxyPort = f.proxyPortValue.takeIf { f.proxyType != null },
                    proxyUsername = f.proxyUsername.trim().takeIf { f.proxyType != null && it.isNotEmpty() },
                    proxyPassword = f.proxyPassword.takeIf { f.proxyType != null && it.isNotEmpty() },
                    groupId = groupId,
                    jumpHostId = f.jumpHostId,
                    color = f.color,
                    tags = f.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","),
                    startupCommand = f.startupCommand.trim().ifEmpty { null }.takeIf { f.kind != Protocol.RDP && f.kind != Protocol.VNC },
                    rdpDomain = f.rdpDomain.trim().takeIf { f.kind == Protocol.RDP && it.isNotEmpty() },
                    rdpResolution = f.rdpResolution.takeIf { f.kind == Protocol.RDP }
                        ?.let(com.nautile.app.rdp.RdpManager::parseResolution)?.let { (w, h) -> "${w}x$h" },
                    rdpScale = f.rdpScaleValue.takeIf { f.kind == Protocol.RDP },
                    rdpSecurity = f.rdpSecurity.takeIf { f.kind == Protocol.RDP },
                    rdpAudio = f.rdpAudio,
                    rdpConsole = f.rdpConsole && f.kind == Protocol.RDP,
                ),
            )
            onSaved(id)
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val host = original ?: return
        viewModelScope.launch { repo.delete(host); onDeleted() }
    }

    /** Imports a key file into the keychain and selects it. */
    fun importKey(uri: Uri) {
        viewModelScope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
            }.getOrNull()
            if (text == null || !looksLikePrivateKey(text)) {
                _message.value = tr("Ce fichier n'est pas une clé privée")
                return@launch
            }
            val name = _form.value.alias.ifBlank { _form.value.hostname }.ifBlank { tr("Clé importée") }
            runCatching { app.keyRepository.import(name, text, null, false) }
                .onSuccess { key -> _form.update { it.copy(auth = AuthMethod.KEY, keyId = key.id) }; _message.value = tr("Clé ajoutée au trousseau") }
                .onFailure { e ->
                    _message.value = when (e) {
                        is PassphraseRequiredException -> tr("Clé chiffrée : importez-la depuis le Trousseau pour saisir sa phrase de passe")
                        is com.nautile.app.keys.DuplicateKeyException -> {
                            _form.update { it.copy(auth = AuthMethod.KEY, keyId = e.existing.id) }
                            tr("Déjà dans le trousseau : « %1\$s » sélectionnée", e.existing.name)
                        }
                        else -> e.message ?: tr("Clé illisible")
                    }
                }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as NautileApp
                HostEditViewModel(app, app.hostRepository, createSavedStateHandle())
            }
        }

        fun looksLikePrivateKey(text: String) =
            text.contains("PRIVATE KEY-----") || text.trimStart().startsWith("PuTTY-User-Key-File")

        internal fun parseImport(text: String): List<ImportedHost> {
            val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().lowercase()
            val looksCsv = ',' in firstLine && Regex("\\b(hostname|host|address)\\b").containsMatchIn(firstLine) &&
                !firstLine.trimStart().startsWith("host ")
            return if (looksCsv) CsvHostParser.parse(text) else SshConfigParser.parse(text)
        }
    }
}

package com.nautile.app.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.nautile.app.NautileApp
import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import com.nautile.data.repo.HostRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class HostSection(val title: String, val hosts: List<Host>, val color: Long? = null)

data class HostsUiState(
    val query: String = "",
    /** null = « Tous ». */
    val selectedGroup: String? = null,
    val groupNames: List<String> = emptyList(),
    val groups: List<HostGroup> = emptyList(),
    /** Number of hosts per group id. */
    val counts: Map<Long, Int> = emptyMap(),
    val sections: List<HostSection> = emptyList(),
    val isEmpty: Boolean = true,
    /** Number of hosts per group name (« Sans groupe » included), and in all. */
    val nameCounts: Map<String, Int> = emptyMap(),
    val total: Int = 0,
)

class HostsViewModel(private val app: NautileApp, private val repo: HostRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val selected = MutableStateFlow<String?>(null)

    val state: StateFlow<HostsUiState> =
        combine(repo.hosts, repo.groups, query, selected) { hosts, groups, q, sel ->
            build(hosts, groups, q, sel)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HostsUiState())

    fun setQuery(q: String) { query.value = q }
    fun selectGroup(name: String?) { selected.value = name }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun consumeMessage() { _message.value = null }

    fun saveGroup(group: HostGroup) = viewModelScope.launch {
        if (group.name.isBlank()) return@launch
        if (group.id == 0L) repo.groupId(group.name.trim()).let { id -> repo.updateGroup(group.copy(id = id, name = group.name.trim(), sortOrder = repo.groups.first().first { it.id == id }.sortOrder)) }
        else if (!repo.updateGroup(group.copy(name = group.name.trim()))) _message.value = com.nautile.i18n.tr("Un groupe porte déjà ce nom")
    }
    fun deleteGroup(group: HostGroup) = viewModelScope.launch {
        repo.deleteGroup(group)
        if (selected.value == group.name) selected.value = null
    }
    fun moveGroup(group: HostGroup, delta: Int) = viewModelScope.launch { repo.moveGroup(group, delta) }
    fun moveHost(host: Host, groupName: String?) = viewModelScope.launch { repo.moveHost(host.id, groupName) }
    fun deleteHost(host: Host) = viewModelScope.launch { repo.delete(host) }

    /** Imports an OpenSSH config or a CSV file picked by the user. */
    fun import(uri: android.net.Uri) = viewModelScope.launch {
        val result = runCatching {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            } ?: error(com.nautile.i18n.tr("Fichier illisible"))
            val items = HostEditViewModel.parseImport(text)
            if (items.isEmpty()) error(com.nautile.i18n.tr("Aucun hôte trouvé dans ce fichier"))
            repo.import(items) to items.size
        }
        _message.value = result.fold(
            { (added, total) -> com.nautile.i18n.tr("%1\$s hôte(s) importé(s)", added) + if (added < total) com.nautile.i18n.tr(", %1\$s déjà présent(s)", total - added) else "" },
            { com.nautile.i18n.tr("Import impossible : %1\$s", it.message) },
        )
    }

    companion object {
        const val UNGROUPED = "Sans groupe"

        val Factory = viewModelFactory {
            initializer { (this[APPLICATION_KEY] as NautileApp).let { HostsViewModel(it, it.hostRepository) } }
        }

        internal fun build(hosts: List<Host>, groups: List<HostGroup>, q: String, sel: String?): HostsUiState {
            val groupName = groups.associate { it.id to it.name }
            val needle = q.trim()
            val filtered = hosts.filter { h ->
                needle.isEmpty() || listOf(h.alias, h.hostname, h.username, h.tags).any { it.contains(needle, ignoreCase = true) }
            }
            val byGroup = filtered.groupBy { h -> h.groupId?.let(groupName::get) ?: UNGROUPED }
            val order = groups.map { it.name } + UNGROUPED
            val sections = order
                .filter { sel == null || it == sel }
                .mapNotNull { name -> byGroup[name]?.let { HostSection(name, it, groups.firstOrNull { g -> g.name == name }?.color) } }
            val names = groups.map { it.name } + if (hosts.any { it.groupId == null }) listOf(UNGROUPED) else emptyList()
            val counts = hosts.mapNotNull { it.groupId }.groupingBy { it }.eachCount()
            val nameCounts = hosts.groupingBy { h -> h.groupId?.let(groupName::get) ?: UNGROUPED }.eachCount()
            return HostsUiState(needle, sel, names, groups, counts, sections, hosts.isEmpty(), nameCounts, hosts.size)
        }
    }
}

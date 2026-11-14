package com.nautile.app.ui.hosts

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nautile.app.NautileApp
import com.nautile.app.readText
import com.nautile.data.importer.HostImport
import com.nautile.data.importer.SshConfigParser
import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import com.nautile.data.repo.HostRepository
import com.nautile.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One block of the list: the hosts of a group, or those without one ([groupId] null).
 * [title] is the group's name as typed (never translated); « Sans groupe » for the hosts without one.
 */
data class HostSection(val groupId: Long?, val title: String, val hosts: List<Host>, val color: Long? = null, val icon: String? = null) {
    /** Unique in the list whatever the groups are called. */
    val key: String get() = "group:${groupId ?: "none"}"
}

data class HostsUiState(
    val query: String = "",
    /** null = « Tous », [HostsViewModel.NO_GROUP] = « Sans groupe », else a group id. */
    val selectedGroup: Long? = null,
    /** The choices after « Tous »: each group id, then [HostsViewModel.NO_GROUP] when some hosts have no group. */
    val groupFilters: List<Long> = emptyList(),
    val groups: List<HostGroup> = emptyList(),
    /** Number of hosts per group id ([HostsViewModel.NO_GROUP] for those without one). */
    val counts: Map<Long, Int> = emptyMap(),
    val sections: List<HostSection> = emptyList(),
    val isEmpty: Boolean = true,
    val total: Int = 0,
)

class HostsViewModel(private val app: NautileApp, private val repo: HostRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    /** By id: a renamed group stays selected. */
    private val selected = MutableStateFlow<Long?>(null)

    val state: StateFlow<HostsUiState> =
        combine(repo.hosts, repo.groups, query, selected) { hosts, groups, q, sel ->
            build(hosts, groups, q, sel)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HostsUiState())

    fun setQuery(q: String) { query.value = q }
    fun selectGroup(id: Long?) { selected.value = id }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun consumeMessage() { _message.value = null }

    fun saveGroup(group: HostGroup) = viewModelScope.launch {
        val named = group.copy(name = group.name.trim())
        if (named.name.isEmpty()) return@launch
        val saved = if (named.id == 0L) repo.createGroup(named) else repo.updateGroup(named)
        if (!saved) _message.value = tr("Un groupe porte déjà ce nom")
    }
    fun deleteGroup(group: HostGroup) = viewModelScope.launch {
        repo.deleteGroup(group)
        if (selected.value == group.id) selected.value = null
    }
    fun moveGroup(group: HostGroup, delta: Int) = viewModelScope.launch { repo.moveGroup(group, delta) }
    fun moveHost(host: Host, groupName: String?) = viewModelScope.launch { repo.moveHost(host.id, groupName) }
    fun deleteHost(host: Host) = viewModelScope.launch { repo.delete(host) }

    /** Imports an OpenSSH config or a CSV file picked by the user. */
    fun import(uri: Uri) = viewModelScope.launch {
        val result = runCatching {
            val text = withContext(Dispatchers.IO) {
                app.contentResolver.readText(uri)
            } ?: error(tr("Fichier illisible"))
            val items = HostImport.parse(text)
            if (items.isEmpty()) error(tr("Aucun hôte trouvé dans ce fichier"))
            Triple(repo.import(items), items.size, !HostImport.looksCsv(text) && SshConfigParser.hasInclude(text))
        }
        _message.value = result.fold(
            { (r, total, includes) ->
                buildString {
                    append(tr("%1\$s hôte(s) importé(s)", r.added))
                    if (r.added < total) append(tr(", %1\$s déjà présent(s)", total - r.added))
                    if (r.missingKeys > 0) append(tr(" ; %1\$s clé(s) à importer dans le trousseau (mot de passe en attendant)", r.missingKeys))
                    if (includes) append(tr(" ; lignes Include non suivies"))
                }
            },
            { tr("Import impossible : %1\$s", it.message) },
        )
    }

    companion object {
        /** Name of the section of hosts without a group (shown translated). */
        const val UNGROUPED = "Sans groupe"
        /** Filter value of « Sans groupe »: never a group id (they start at 1). */
        const val NO_GROUP = -1L

        val Factory = viewModelFactory {
            initializer { (this[APPLICATION_KEY] as NautileApp).let { HostsViewModel(it, it.hostRepository) } }
        }

        internal fun build(hosts: List<Host>, groups: List<HostGroup>, q: String, selected: Long?): HostsUiState {
            val needle = q.trim()
            val ids = groups.mapTo(HashSet()) { it.id }
            // A host whose group is gone (being deleted) counts as without a group.
            fun groupOf(h: Host) = h.groupId?.takeIf { it in ids } ?: NO_GROUP
            val counts = hosts.groupingBy(::groupOf).eachCount()
            val filters = groups.map { it.id } + if (NO_GROUP in counts) listOf(NO_GROUP) else emptyList()
            // A group deleted or emptied meanwhile (here or by a synchronisation): back to « Tous ».
            val sel = selected?.takeIf { it in filters }
            val byGroup = hosts.filter { h ->
                needle.isEmpty() || listOf(h.alias, h.hostname, h.username, h.tags).any { it.contains(needle, ignoreCase = true) }
            }.groupBy(::groupOf)
            val sections = groups.mapNotNull { g -> byGroup[g.id]?.let { HostSection(g.id, g.name, it, g.color, g.icon) } } +
                listOfNotNull(byGroup[NO_GROUP]?.let { HostSection(null, UNGROUPED, it) })
            return HostsUiState(
                query = needle,
                selectedGroup = sel,
                groupFilters = filters,
                groups = groups,
                counts = counts,
                sections = sections.filter { sel == null || (it.groupId ?: NO_GROUP) == sel },
                isEmpty = hosts.isEmpty(),
                total = hosts.size,
            )
        }
    }
}

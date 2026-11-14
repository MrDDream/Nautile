package com.nautile.app.ai

import android.content.SharedPreferences
import com.nautile.i18n.I18n
import com.nautile.i18n.tr
import com.nautile.security.FileCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One exchange with the assistant: what was asked (and sent), what came back. */
data class AiTurn(
    val request: String,
    /** The full message sent (host, screen, files), replayed as context for the next questions. */
    val sent: String,
    val files: List<String>,
    val screen: Boolean,
    val provider: AiProviderKind,
    val model: String,
    val raw: String,
    val answer: AiAssistant.Suggestion,
    val at: Long = System.currentTimeMillis(),
    /**
     * Where it was sent ([AiAssistant.endpointOf]): a local model's or another server's address may be
     * changed, and what went to one must never go along to another. Null when unknown: never sent again.
     */
    val endpoint: String? = null,
)

/** A system prompt the user wrote, chosen in the terminal before asking. */
data class AiPrompt(val id: String, val name: String, val text: String)

/**
 * Turns a request in plain language into a shell command, with the provider the user chose.
 * [secrets] holds the API keys (encrypted); [prefs] holds the system prompts, which are not secret.
 */
class AiAssistant(
    private val secrets: Secrets,
    private val prefs: SharedPreferences,
    private val conversationsDir: File? = null,
    /** Encrypts the saved conversations (they hold the terminal's screen and attached files); null keeps them plain. */
    private val cipher: FileCipher? = null,
) {

    /** Where API keys and provider settings live (the Keystore-backed store in the app). */
    interface Secrets {
        fun get(name: String): String?
        fun put(name: String, value: String?)
    }

    data class Suggestion(val command: String?, val explanation: String)

    var provider: AiProviderKind
        get() = secrets.get(KEY_PROVIDER)?.let { runCatching { AiProviderKind.valueOf(it) }.getOrNull() } ?: AiProviderKind.CLAUDE
        set(v) = secrets.put(KEY_PROVIDER, v.name)

    /** Set up: a key (or, for a keyless one, a saved address) and a model. */
    fun isConfigured(kind: AiProviderKind): Boolean =
        isReady(config(kind)) && (kind.needsKey || secrets.get("url_${kind.name}") != null || secrets.get("model_${kind.name}") != null)

    private val _enabled = MutableStateFlow(AiProviderKind.entries.associateWith { loadEnabled(it) })
    /** Providers switched on in the settings; only those are offered in the terminal. */
    val enabled: StateFlow<Map<AiProviderKind, Boolean>> = _enabled.asStateFlow()

    /** Before the switches existed, a provider set up was in use: it stays on. */
    private fun loadEnabled(kind: AiProviderKind): Boolean =
        if (prefs.contains("enabled_${kind.name}")) prefs.getBoolean("enabled_${kind.name}", false) else isConfigured(kind)

    fun setEnabled(kind: AiProviderKind, on: Boolean) {
        prefs.edit().putBoolean("enabled_${kind.name}", on).apply()
        _enabled.value = _enabled.value + (kind to on)
        if (on && !isOn(provider)) provider = kind
        if (!on && provider == kind) readyProviders().firstOrNull()?.let { provider = it }
    }

    private fun isOn(kind: AiProviderKind) = _enabled.value[kind] == true && isConfigured(kind)

    /** Providers switched on and able to answer, the last used first. */
    fun readyProviders(): List<AiProviderKind> = AiProviderKind.entries.filter { isOn(it) }.sortedBy { if (it == provider) 0 else 1 }

    private val _prompts = MutableStateFlow(loadPrompts())
    /** The user's own system prompts; the built-in one is [defaultPrompt]. */
    val prompts: StateFlow<List<AiPrompt>> = _prompts.asStateFlow()

    private val _promptId = MutableStateFlow(prefs.getString(KEY_PROMPT, DEFAULT_ID) ?: DEFAULT_ID)
    /** The prompt used last ([DEFAULT_ID] for the built-in one). */
    val promptId: StateFlow<String> = _promptId.asStateFlow()

    fun selectPrompt(id: String) {
        _promptId.value = id
        prefs.edit().putString(KEY_PROMPT, id).apply()
    }

    fun savePrompt(prompt: AiPrompt) {
        val list = _prompts.value
        _prompts.value = if (list.any { it.id == prompt.id }) list.map { if (it.id == prompt.id) prompt else it } else list + prompt
        storePrompts()
    }

    fun deletePrompt(id: String) {
        _prompts.value = _prompts.value.filterNot { it.id == id }
        storePrompts()
        if (_promptId.value == id) selectPrompt(DEFAULT_ID)
    }

    /** Text of the prompt [id], the built-in one when it no longer exists. */
    fun promptText(id: String): String = _prompts.value.firstOrNull { it.id == id }?.text ?: defaultPrompt

    private fun loadPrompts(): List<AiPrompt> = runCatching {
        val a = JSONArray(prefs.getString(KEY_PROMPTS, "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { AiPrompt(it.getString("id"), it.getString("name"), it.getString("text")) } }
    }.getOrDefault(emptyList())

    private fun storePrompts() {
        val a = JSONArray()
        _prompts.value.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("text", it.text)) }
        prefs.edit().putString(KEY_PROMPTS, a.toString()).apply()
    }

    fun config(kind: AiProviderKind = provider) = AiConfig(
        kind = kind,
        apiKey = secrets.get("key_${kind.name}")?.takeIf { it.isNotBlank() },
        model = secrets.get("model_${kind.name}")?.takeIf { it.isNotBlank() } ?: kind.defaultModel,
        baseUrl = secrets.get("url_${kind.name}")?.takeIf { it.isNotBlank() } ?: kind.defaultBaseUrl,
    )

    fun save(kind: AiProviderKind, apiKey: String?, model: String, baseUrl: String?) {
        if (apiKey != null) secrets.put("key_${kind.name}", apiKey.trim())
        secrets.put("model_${kind.name}", model.trim())
        if (kind.editableUrl) secrets.put("url_${kind.name}", baseUrl?.trim())
    }

    fun forgetKey(kind: AiProviderKind) = secrets.put("key_${kind.name}", null)

    fun isReady(c: AiConfig = config()) = (!c.kind.needsKey || c.apiKey != null) && (c.kind != AiProviderKind.CUSTOM || c.model.isNotBlank())

    val configured: Boolean get() = readyProviders().isNotEmpty()

    suspend fun models(c: AiConfig): List<String> = withContext(Dispatchers.IO) { AiProviders.create(c).models() }

    /** A tiny request to check the configuration. */
    suspend fun test(c: AiConfig): String = withContext(Dispatchers.IO) {
        AiProviders.create(c).complete(tr("Réponds uniquement par le mot : OK"), tr("Test de connexion")).trim().take(80)
    }

    /**
     * [screen] is sent only when the user chose to include it (last lines of the terminal).
     */
    private val conversations = ConcurrentHashMap<String, MutableStateFlow<List<AiTurn>>>()

    /**
     * Conversations whose sealed file could not be opened yet (the Keystore failing for a moment): read again
     * at the next look, and never overwritten meanwhile, or the turns kept in it would be lost.
     */
    private val unopened = ConcurrentHashMap<String, Long>()

    /**
     * The conversation with server [key] (see [conversationKey]): one per server, kept on the phone until cleared.
     * Reads its file the first time (a Keystore read, slow when it fails): off the main thread, or see [conversationFlow].
     */
    fun conversation(key: String): StateFlow<List<AiTurn>> = stateOf(key)

    /** [conversation] for a composable: its file is read on [Dispatchers.IO], never during the composition. */
    fun conversationFlow(key: String): Flow<List<AiTurn>> =
        kotlinx.coroutines.flow.flow { emitAll(stateOf(key)) }.flowOn(Dispatchers.IO)

    /** The conversation when it was read already, null otherwise: never reads a file (for a first value). */
    fun loadedConversation(key: String): List<AiTurn>? = conversations[key]?.value

    /** [conversation] read on [Dispatchers.IO]. */
    suspend fun loadConversation(key: String): List<AiTurn> = withContext(Dispatchers.IO) { stateOf(key).value }

    private fun stateOf(key: String): MutableStateFlow<List<AiTurn>> {
        val f = conversations.getOrPut(key) { MutableStateFlow(loadOrMark(key)) }
        val triedAt = unopened[key]
        // Unreadable for now at the last try: again (not at every look: the Keystore is slow when it fails),
        // keeping what was asked meanwhile after the saved turns.
        if (triedAt != null && System.currentTimeMillis() - triedAt >= RETRY_UNOPENED_MS) {
            unopened.replace(key, triedAt, System.currentTimeMillis())
            val r = load(key)
            if (r is Loaded.Turns) synchronized(f) {
                if (unopened.remove(key) != null) {
                    f.value = (r.turns + f.value).takeLast(MAX_KEPT_TURNS)
                    if (r.turns.isNotEmpty() || f.value.isNotEmpty()) store(key, f.value)
                }
            }
        }
        return f
    }

    private fun loadOrMark(key: String): List<AiTurn> = when (val r = load(key)) {
        is Loaded.Turns -> r.turns
        Loaded.NotNow -> { unopened[key] = System.currentTimeMillis(); emptyList() }
    }

    fun clearConversation(key: String) {
        val f = conversations.getOrPut(key) { MutableStateFlow(emptyList()) }
        synchronized(f) {
            f.value = emptyList()
            file(key)?.delete()
            legacyFile(key)?.delete()
            unopened.remove(key)
        }
    }

    /**
     * Drops the conversations of the saved hosts that no longer exist (deleted here or by a synchronisation):
     * exactly those whose file (named after the host's id) or loaded conversation belongs to an id not in
     * [existing]. Files of earlier versions, named by a hash, are renamed after their host first when found.
     * Not to call while the database was just set aside (empty until restored): every conversation would go.
     */
    fun forgetDeletedHosts(existing: Set<Long>) {
        val dir = conversationsDir ?: return
        migrateLegacyFiles(dir, existing)
        val ids = HashSet<Long>()
        dir.listFiles()?.forEach { f -> HOST_FILE.matchEntire(f.name)?.let { m -> m.groupValues[1].toLongOrNull()?.let(ids::add) } }
        conversations.keys.forEach { k -> k.removePrefix(HOST_PREFIX).takeIf { k.startsWith(HOST_PREFIX) }?.toLongOrNull()?.let(ids::add) }
        for (id in ids) if (id !in existing) clearConversation(conversationKey(id, ""))
    }

    @Volatile private var legacyMigrated = false

    /**
     * Earlier versions named every file by a hash of its key: those of saved hosts are renamed after their id
     * (the ids are tried up to well past the largest one). Files left unmatched (quick connections) stay as they are.
     */
    private fun migrateLegacyFiles(dir: File, existing: Set<Long>) {
        if (legacyMigrated) return
        val legacy = dir.listFiles()?.filter { LEGACY_FILE.matches(it.name) }.orEmpty()
        if (legacy.isNotEmpty()) {
            val byName = legacy.associateBy { it.name.removeSuffix(".json") }
            val top = maxOf(existing.maxOrNull() ?: 0L, 0L) + LEGACY_IDS_PAST_TOP
            var left = byName.size
            var id = 1L
            while (id <= top && left > 0) {
                val key = conversationKey(id, "")
                byName[hashName(key)]?.let { f ->
                    left--
                    val target = File(dir, "${fileName(key)}.json")
                    if (!target.exists()) f.renameTo(target)
                }
                id++
            }
        }
        legacyMigrated = true
    }
    internal fun record(key: String, turn: AiTurn) {
        val f = stateOf(key)
        synchronized(f) {
            f.value = (f.value + turn).takeLast(MAX_KEPT_TURNS)
            store(key, f.value)
        }
    }

    /** A saved host's file is named after its id (so that [forgetDeletedHosts] knows it), any other by a hash. */
    private fun fileName(key: String) =
        key.takeIf { it.startsWith(HOST_PREFIX) }?.removePrefix(HOST_PREFIX)?.toLongOrNull()?.let { "host-$it" } ?: hashName(key)

    private fun hashName(key: String) =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    private fun file(key: String): File? = conversationsDir?.let { dir -> File(dir.apply { mkdirs() }, "${fileName(key)}.json") }

    /** Where earlier versions kept a saved host's conversation; null when it is named the same way. */
    private fun legacyFile(key: String): File? =
        conversationsDir?.takeIf { fileName(key) != hashName(key) }?.let { dir -> File(dir, "${hashName(key)}.json") }

    private sealed interface Loaded {
        class Turns(val turns: List<AiTurn>) : Loaded
        /** Sealed, and its key cannot be read now: not an empty conversation. */
        data object NotNow : Loaded
    }

    private fun load(key: String): Loaded {
        val target = file(key) ?: return Loaded.Turns(emptyList())
        legacyFile(key)?.takeIf { !target.exists() && it.exists() }?.renameTo(target)
        val f = target.takeIf { it.exists() } ?: return Loaded.Turns(emptyList())
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return Loaded.NotNow
        // Files saved before encryption are plain JSON and still read as such.
        val text = if (cipher == null) bytes.decodeToString() else when (val r = cipher.read(bytes)) {
            is FileCipher.Opened.Plain -> r.bytes.decodeToString()
            FileCipher.Opened.Unavailable -> return Loaded.NotNow
            FileCipher.Opened.Unreadable -> {
                // Never readable again (another key, damaged): kept aside under a name of its own (never over an
                // earlier one), the conversation starts afresh.
                runCatching { f.renameTo(unusedName(f.path + ".unreadable")) }
                return Loaded.Turns(emptyList())
            }
        }
        return Loaded.Turns(runCatching { parseTurns(text) }.getOrDefault(emptyList()))
    }

    private fun unusedName(base: String): File =
        generateSequence(0) { it + 1 }.map { File(if (it == 0) base else "$base-$it") }.first { !it.exists() }

    private fun parseTurns(text: String): List<AiTurn> {
        val a = JSONArray(text)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val raw = o.getString("raw")
            val kind = runCatching { AiProviderKind.valueOf(o.getString("provider")) }.getOrNull()
            AiTurn(
                request = o.getString("request"), sent = o.optString("sent", o.getString("request")),
                files = o.optJSONArray("files")?.let { fa -> (0 until fa.length()).map { fa.getString(it) } }.orEmpty(),
                screen = o.optBoolean("screen"),
                // An unknown provider is shown as « Autre » but its endpoint stays unknown: never sent again.
                provider = kind ?: AiProviderKind.CUSTOM,
                model = o.optString("model"), raw = raw, answer = parse(raw), at = o.optLong("at"),
                endpoint = o.optString("endpoint").ifEmpty { null } ?: kind?.let(::legacyEndpoint),
            )
        }
    }

    private fun store(key: String, turns: List<AiTurn>) {
        val f = file(key) ?: return
        // A sealed file not opened yet holds turns this list lacks: kept as it is until it can be read.
        if (unopened.containsKey(key) && f.exists()) return
        val a = JSONArray()
        turns.forEach { t ->
            a.put(
                JSONObject().put("request", t.request).put("sent", t.sent.take(MAX_SENT_CHARS))
                    .put("files", JSONArray(t.files)).put("screen", t.screen).put("provider", t.provider.name)
                    .put("model", t.model).put("raw", t.raw).put("at", t.at)
                    .apply { t.endpoint?.let { put("endpoint", it) } },
            )
        }
        runCatching {
            val bytes = a.toString().toByteArray()
            // Written aside then renamed: a crash mid-write never leaves half a file.
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(cipher?.seal(bytes) ?: bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /**
     * Asks within [conversation] (null: a one-off question): the earlier exchanges
     * go along as context, the last two in full, older ones as their questions only.
     */
    suspend fun ask(
        request: String,
        hostDescription: String,
        screen: String?,
        kind: AiProviderKind = provider,
        system: String = promptText(promptId.value),
        files: List<AiFile> = emptyList(),
        conversation: String? = null,
    ): Suggestion = withContext(Dispatchers.IO) {
        val c = config(kind)
        if (!isReady(c)) throw AiException(tr("Configurez l'assistant dans Réglages › Assistant IA"))
        val user = buildString {
            append(if (I18n.english) "Machine: " else "Machine : ").append(hostDescription).append("\n\n")
            if (screen != null) append(tr("Fin de l'écran du terminal :\n```\n")).append(screen).append("\n```\n\n")
            files.forEach { f ->
                append(tr("Fichier joint « %1\$s »", f.name))
                if (f.cut) append(if (f.tail) tr(" (fin du fichier, %1\$s sur %2\$s)", AiFiles.sizeLabel(AiFiles.MAX_BYTES.toLong()), AiFiles.sizeLabel(f.size))
                    else tr(" (début du fichier, %1\$s sur %2\$s)", AiFiles.sizeLabel(AiFiles.MAX_BYTES.toLong()), AiFiles.sizeLabel(f.size)))
                append(" :\n```\n").append(f.text).append("\n```\n\n")
            }
            append(if (I18n.english) "Request: " else "Demande : ").append(request)
        }
        // Only the exchanges held with this same provider at this same address: what was sent to a local
        // model never goes to a cloud one, nor to another server typed in its place.
        val endpoint = endpointOf(kind, c.baseUrl)
        val history = contextTurns(conversation?.let { conversation(it).value }.orEmpty(), kind, endpoint)
        val messages = history.flatMapIndexed { i, t ->
            val recent = i >= history.size - 2
            listOf(AiMessage(true, if (recent) t.sent else t.request), AiMessage(false, t.raw))
        } + AiMessage(true, user)
        val raw = AiProviders.create(c).chat(system, messages).trim()
        val answer = parse(raw)
        conversation?.let { key -> record(key, AiTurn(request, user, files.map { it.name.substringAfterLast('/') }, screen != null, kind, c.model, raw, answer, endpoint = endpoint)) }
        answer
    }

    /** The conversation as Markdown, for copying, sharing or saving. */
    fun markdown(turns: List<AiTurn>, host: String): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        append("# ").append(tr("Assistant Nautile")).append(" — ").append(host)
        turns.firstOrNull()?.let { append(" (").append(fmt.format(Date(it.at))).append(")") }
        append("\n\n")
        turns.forEach { t ->
            append("## ").append(tr("Vous")).append("\n\n").append(t.request.trim()).append("\n")
            if (t.files.isNotEmpty()) append("\n_").append(tr("Fichiers joints : %1\$s", t.files.joinToString(", "))).append("_\n")
            if (t.screen) append("\n_").append(tr("Écran du terminal joint")).append("_\n")
            append("\n## ").append(t.provider.shortLabel).append(" · ").append(prettyModel(t.model)).append("\n\n")
            append(t.raw.trim()).append("\n\n")
        }
    }

    companion object {
        /** Earlier exchanges sent along with a new question. */
        private const val MAX_CONTEXT_TURNS = 8
        /** Exchanges kept per server. */
        private const val MAX_KEPT_TURNS = 40
        /** What is kept of a sent message (its attached files may be large). */
        private const val MAX_SENT_CHARS = 80_000
        /** Host ids past the largest one tried when renaming the files of earlier versions (hosts deleted since). */
        private const val LEGACY_IDS_PAST_TOP = 100_000L
        private const val HOST_PREFIX = "host:"
        private val HOST_FILE = Regex("^host-(\\d+)\\.json$")
        private val LEGACY_FILE = Regex("^[0-9a-f]{24}\\.json$")
        /** How often a conversation whose file could not be opened is read again. */
        private const val RETRY_UNOPENED_MS = 15_000L

        /**
         * Identifies where a request goes: the provider and its base address, normalised (case of the scheme
         * and host, trailing slashes), hashed so that the saved conversation does not spell it out.
         */
        fun endpointOf(kind: AiProviderKind, baseUrl: String?): String {
            val url = baseUrl?.trim()?.trimEnd('/').orEmpty()
            val normal = Regex("^([A-Za-z][A-Za-z0-9+.-]*://)([^/]*)(.*)$").find(url)
                ?.let { m -> m.groupValues[1].lowercase() + m.groupValues[2].lowercase() + m.groupValues[3] } ?: url
            val digest = MessageDigest.getInstance("SHA-256").digest("${kind.name}|$normal".toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }

        /** Turns saved before endpoints were: known only for a provider whose address cannot be changed. */
        private fun legacyEndpoint(kind: AiProviderKind): String? = if (kind.editableUrl) null else endpointOf(kind, kind.defaultBaseUrl)

        /** The earlier exchanges that may go along: same provider, same address (an unknown one never). */
        internal fun contextTurns(turns: List<AiTurn>, kind: AiProviderKind, endpoint: String): List<AiTurn> =
            turns.filter { it.provider == kind && it.endpoint != null && it.endpoint == endpoint }.takeLast(MAX_CONTEXT_TURNS)

        /** The conversation of a server: a saved host by its id, a quick connection by its address. */
        fun conversationKey(hostId: Long, address: String) = if (hostId != 0L) "host:$hostId" else "quick:$address"
        private const val KEY_PROVIDER = "ai_provider"
        private const val KEY_PROMPTS = "prompts"
        private const val KEY_PROMPT = "prompt"
        const val DEFAULT_ID = "default"

        /** The built-in system prompt, in the app's language. */
        val defaultPrompt: String get() = if (I18n.english) SYSTEM_EN else SYSTEM

        private val SYSTEM = """
            Tu es l'assistant d'un client SSH sur téléphone. L'utilisateur décrit ce qu'il veut faire sur le serveur
            auquel il est connecté, demande d'expliquer une erreur visible à l'écran, ou joint des fichiers
            (configuration, journaux, JSON…) à analyser ou corriger.
            Réponds en français, brièvement :
            - d'abord UNE commande shell prête à coller, dans un seul bloc ```bash``` (plusieurs commandes enchaînées
              avec && si nécessaire) ; omets le bloc si aucune commande ne convient ;
            - puis une explication structurée et lisible sur un téléphone : une phrase de synthèse, puis si utile des
              puces courtes (une idée par puce, **gras** pour les éléments clés, `code` pour les noms et valeurs),
              et un court titre « ### » seulement pour séparer des parties distinctes ; pas de longs paragraphes ;
            - signale clairement tout effet destructeur ou irréversible.
            N'invente pas de chemins ou de noms de services absents de la demande ou de l'écran : utilise des
            emplacements génériques explicites (<service>) quand une information manque.
        """.trimIndent()

        private val SYSTEM_EN = """
            You are the assistant of an SSH client on a phone. The user describes what they want to do on the server
            they are connected to, asks to explain an error visible on screen, or attaches files
            (configuration, logs, JSON…) to analyse or fix.
            Answer in English, briefly:
            - first ONE shell command ready to paste, in a single ```bash``` block (several commands chained with &&
              if needed); leave the block out if no command fits;
            - then a structured explanation that reads well on a phone: one summary sentence, then if useful short
              bullets (one idea per bullet, **bold** for key items, `code` for names and values), and a short « ### »
              heading only to separate distinct parts; no long paragraphs;
            - clearly flag anything destructive or irreversible.
            Do not invent paths or service names absent from the request or the screen: use explicit generic
            placeholders (<service>) when information is missing.
        """.trimIndent()

        private val BLOCK = Regex("```(?:bash|sh|shell|zsh)?\\s*\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)

        internal fun parse(text: String): Suggestion {
            val m = BLOCK.find(text)
            val command = m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            val explanation = (if (m != null) text.removeRange(m.range) else text).trim()
            return Suggestion(command, explanation)
        }
    }
}

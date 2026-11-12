package com.nautile.app.ai

import com.nautile.i18n.tr

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
)

/** A system prompt the user wrote, chosen in the terminal before asking. */
data class AiPrompt(val id: String, val name: String, val text: String)

/**
 * Turns a request in plain language into a shell command, with the provider the user chose.
 * [secrets] holds the API keys (encrypted); [prefs] holds the system prompts, which are not secret.
 */
class AiAssistant(private val secrets: Secrets, private val prefs: SharedPreferences, private val conversationsDir: java.io.File? = null) {

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

    fun movePrompt(from: Int, to: Int) {
        val list = _prompts.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        _prompts.value = list
        storePrompts()
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
    val providerLabel: String get() = (readyProviders().firstOrNull() ?: provider).label

    suspend fun models(c: AiConfig): List<String> = withContext(Dispatchers.IO) { AiProviders.create(c).models() }

    /** A tiny request to check the configuration. */
    suspend fun test(c: AiConfig): String = withContext(Dispatchers.IO) {
        AiProviders.create(c).complete(tr("Réponds uniquement par le mot : OK"), tr("Test de connexion")).trim().take(80)
    }

    /**
     * [screen] is sent only when the user chose to include it (last lines of the terminal).
     */
    private val conversations = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<List<AiTurn>>>()

    /** The conversation with server [key] (see [conversationKey]): one per server, kept on the phone until cleared. */
    fun conversation(key: String): StateFlow<List<AiTurn>> = flow(key)

    private fun flow(key: String) = conversations.getOrPut(key) { MutableStateFlow(load(key)) }

    fun clearConversation(key: String) {
        flow(key).value = emptyList()
        file(key)?.delete()
    }

    internal fun record(key: String, turn: AiTurn) {
        val f = flow(key)
        f.value = (f.value + turn).takeLast(MAX_KEPT_TURNS)
        store(key, f.value)
    }

    private fun file(key: String): java.io.File? = conversationsDir?.let { dir ->
        val name = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        java.io.File(dir.apply { mkdirs() }, "$name.json")
    }

    private fun load(key: String): List<AiTurn> = runCatching {
        val f = file(key)?.takeIf { it.exists() } ?: return@runCatching emptyList()
        val a = JSONArray(f.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val raw = o.getString("raw")
            AiTurn(
                request = o.getString("request"), sent = o.optString("sent", o.getString("request")),
                files = o.optJSONArray("files")?.let { fa -> (0 until fa.length()).map { fa.getString(it) } }.orEmpty(),
                screen = o.optBoolean("screen"),
                provider = runCatching { AiProviderKind.valueOf(o.getString("provider")) }.getOrDefault(AiProviderKind.CUSTOM),
                model = o.optString("model"), raw = raw, answer = parse(raw), at = o.optLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    private fun store(key: String, turns: List<AiTurn>) {
        val f = file(key) ?: return
        val a = JSONArray()
        turns.forEach { t ->
            a.put(
                JSONObject().put("request", t.request).put("sent", t.sent.take(MAX_SENT_CHARS))
                    .put("files", JSONArray(t.files)).put("screen", t.screen).put("provider", t.provider.name)
                    .put("model", t.model).put("raw", t.raw).put("at", t.at),
            )
        }
        runCatching { f.writeText(a.toString()) }
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
            append(if (com.nautile.i18n.I18n.english) "Machine: " else "Machine : ").append(hostDescription).append("\n\n")
            if (screen != null) append(tr("Fin de l'écran du terminal :\n```\n")).append(screen).append("\n```\n\n")
            files.forEach { f ->
                append(tr("Fichier joint « %1\$s »", f.name))
                if (f.cut) append(if (f.tail) tr(" (fin du fichier, %1\$s sur %2\$s)", AiFiles.sizeLabel(AiFiles.MAX_BYTES.toLong()), AiFiles.sizeLabel(f.size))
                    else tr(" (début du fichier, %1\$s sur %2\$s)", AiFiles.sizeLabel(AiFiles.MAX_BYTES.toLong()), AiFiles.sizeLabel(f.size)))
                append(" :\n```\n").append(f.text).append("\n```\n\n")
            }
            append(if (com.nautile.i18n.I18n.english) "Request: " else "Demande : ").append(request)
        }
        val history = conversation?.let { conversation(it).value }.orEmpty().takeLast(MAX_CONTEXT_TURNS)
        val messages = history.flatMapIndexed { i, t ->
            val recent = i >= history.size - 2
            listOf(AiMessage(true, if (recent) t.sent else t.request), AiMessage(false, t.raw))
        } + AiMessage(true, user)
        val raw = AiProviders.create(c).chat(system, messages).trim()
        val answer = parse(raw)
        conversation?.let { key -> record(key, AiTurn(request, user, files.map { it.name.substringAfterLast('/') }, screen != null, kind, c.model, raw, answer)) }
        answer
    }

    /** The conversation as Markdown, for copying, sharing or saving. */
    fun markdown(turns: List<AiTurn>, host: String): String = buildString {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT)
        append("# ").append(tr("Assistant Nautile")).append(" — ").append(host)
        turns.firstOrNull()?.let { append(" (").append(fmt.format(java.util.Date(it.at))).append(")") }
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

        /** The conversation of a server: a saved host by its id, a quick connection by its address. */
        fun conversationKey(hostId: Long, address: String) = if (hostId != 0L) "host:$hostId" else "quick:$address"
        private const val KEY_PROVIDER = "ai_provider"
        private const val KEY_PROMPTS = "prompts"
        private const val KEY_PROMPT = "prompt"
        const val DEFAULT_ID = "default"

        /** The built-in system prompt, in the app's language. */
        val defaultPrompt: String get() = if (com.nautile.i18n.I18n.english) SYSTEM_EN else SYSTEM

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

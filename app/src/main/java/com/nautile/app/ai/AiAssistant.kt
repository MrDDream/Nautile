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

/** A system prompt the user wrote, chosen in the terminal before asking. */
data class AiPrompt(val id: String, val name: String, val text: String)

/**
 * Turns a request in plain language into a shell command, with the provider the user chose.
 * [secrets] holds the API keys (encrypted); [prefs] holds the system prompts, which are not secret.
 */
class AiAssistant(private val secrets: Secrets, private val prefs: SharedPreferences) {

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
    suspend fun ask(
        request: String,
        hostDescription: String,
        screen: String?,
        kind: AiProviderKind = provider,
        system: String = promptText(promptId.value),
        files: List<AiFile> = emptyList(),
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
        parse(AiProviders.create(c).complete(system, user).trim())
    }

    companion object {
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
            - puis 1 à 3 phrases d'explication, en signalant clairement tout effet destructeur ou irréversible.
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
            - then 1 to 3 sentences of explanation, clearly flagging anything destructive or irreversible.
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

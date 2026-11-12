package com.nautile.app.ai

import com.nautile.i18n.tr

import com.nautile.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turns a request in plain language into a shell command, with the provider the user chose. */
class AiAssistant(private val secrets: SecretStore) {

    data class Suggestion(val command: String?, val explanation: String)

    var provider: AiProviderKind
        get() = secrets.get(KEY_PROVIDER)?.let { runCatching { AiProviderKind.valueOf(it) }.getOrNull() } ?: AiProviderKind.CLAUDE
        set(v) = secrets.put(KEY_PROVIDER, v.name)

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

    val configured: Boolean get() = isReady()
    val providerLabel: String get() = provider.label

    suspend fun models(c: AiConfig): List<String> = withContext(Dispatchers.IO) { AiProviders.create(c).models() }

    /** A tiny request to check the configuration. */
    suspend fun test(c: AiConfig): String = withContext(Dispatchers.IO) {
        AiProviders.create(c).complete(tr("Réponds uniquement par le mot : OK"), tr("Test de connexion")).trim().take(80)
    }

    /**
     * [screen] is sent only when the user chose to include it (last lines of the terminal).
     */
    suspend fun ask(request: String, hostDescription: String, screen: String?): Suggestion = withContext(Dispatchers.IO) {
        val c = config()
        if (!isReady(c)) throw AiException(tr("Configurez l'assistant dans Réglages › Assistant IA"))
        val user = buildString {
            append(if (com.nautile.i18n.I18n.english) "Machine: " else "Machine : ").append(hostDescription).append("\n\n")
            if (screen != null) append(tr("Fin de l'écran du terminal :\n```\n")).append(screen).append("\n```\n\n")
            append(if (com.nautile.i18n.I18n.english) "Request: " else "Demande : ").append(request)
        }
        parse(AiProviders.create(c).complete(if (com.nautile.i18n.I18n.english) SYSTEM_EN else SYSTEM, user).trim())
    }

    companion object {
        private const val KEY_PROVIDER = "ai_provider"

        private val SYSTEM = """
            Tu es l'assistant d'un client SSH sur téléphone. L'utilisateur décrit ce qu'il veut faire sur le serveur
            auquel il est connecté (ou demande d'expliquer une erreur visible à l'écran).
            Réponds en français, brièvement :
            - d'abord UNE commande shell prête à coller, dans un seul bloc ```bash``` (plusieurs commandes enchaînées
              avec && si nécessaire) ; omets le bloc si aucune commande ne convient ;
            - puis 1 à 3 phrases d'explication, en signalant clairement tout effet destructeur ou irréversible.
            N'invente pas de chemins ou de noms de services absents de la demande ou de l'écran : utilise des
            emplacements génériques explicites (<service>) quand une information manque.
        """.trimIndent()

        private val SYSTEM_EN = """
            You are the assistant of an SSH client on a phone. The user describes what they want to do on the server
            they are connected to (or asks to explain an error visible on screen).
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

package com.nautile.app.session

import com.nautile.app.ai.AiAssistant
import com.nautile.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The assistant request of one terminal session. Owned by the session rather than by the sheet:
 * locking the app takes the sheet out of the composition, and the request must go on meanwhile.
 */
class AssistantJob internal constructor() {
    // Created on the first request: plain unit tests have no main dispatcher.
    private val scopeLazy = lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private val scope by scopeLazy
    private var job: Job? = null

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _result = MutableStateFlow<AiAssistant.Suggestion?>(null)
    /** The answer of the last request, until the next one. */
    val result: StateFlow<AiAssistant.Suggestion?> = _result.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Starts [request] unless one is running (main thread). */
    fun start(request: suspend () -> AiAssistant.Suggestion) {
        if (job != null) return
        _error.value = null
        _result.value = null
        _busy.value = true
        job = scope.launch {
            try {
                _result.value = request()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: tr("Échec de la requête")
            } finally {
                job = null
                _busy.value = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun clearResult() { _result.value = null }

    fun setError(message: String?) { _error.value = message }

    /** The session is gone: nothing will show the answer. */
    internal fun close() {
        if (scopeLazy.isInitialized()) scope.cancel()
    }
}

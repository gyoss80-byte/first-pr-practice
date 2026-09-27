package com.voiceprompter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.vosk.android.RecognitionListener

sealed interface ModelStatus {
    data object Preparing : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

/** State for the recognition test screen: shows live Vosk partial and final results. */
class RecognitionTest(private val engine: SpeechEngine) {
    var lang by mutableStateOf(Lang.EN)
        private set
    var status by mutableStateOf<ModelStatus>(ModelStatus.Preparing)
        private set
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    val phrases = mutableStateListOf<String>()

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            partial = field(hypothesis, "partial")
        }

        override fun onResult(hypothesis: String?) = addPhrase(hypothesis)

        override fun onFinalResult(hypothesis: String?) = addPhrase(hypothesis)

        override fun onError(exception: Exception?) {
            stopListening()
            status = ModelStatus.Failed(exception?.message ?: "The microphone stopped unexpectedly.")
        }

        override fun onTimeout() = stopListening()
    }

    fun prepare() {
        val requested = lang
        status = ModelStatus.Preparing
        engine.loadModel(
            requested,
            onReady = { if (lang == requested) status = ModelStatus.Ready },
            onError = { if (lang == requested) status = ModelStatus.Failed(it) },
        )
    }

    fun selectLang(newLang: Lang) {
        if (newLang == lang) return
        stopListening()
        lang = newLang
        prepare()
    }

    fun startListening() {
        if (status != ModelStatus.Ready) return
        listening = engine.start(lang, listener)
    }

    fun stopListening() {
        engine.stop()
        listening = false
        partial = ""
    }

    fun clear() {
        phrases.clear()
        partial = ""
    }

    private fun addPhrase(hypothesis: String?) {
        val text = field(hypothesis, "text")
        if (text.isNotBlank()) phrases.add(0, text)
        partial = ""
    }

    private fun field(json: String?, key: String): String =
        json?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() }.orEmpty()
}

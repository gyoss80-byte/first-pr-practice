package com.voiceprompter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface ModelStatus {
    data object Preparing : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

/** State for the recognition test screen: shows live partial and final results. */
class RecognitionTest(private val engine: SpeechEngine) {
    var lang by mutableStateOf(Lang.EN)
        private set
    var status by mutableStateOf<ModelStatus>(ModelStatus.Preparing)
        private set
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    var level by mutableFloatStateOf(0f)
        private set
    val phrases = mutableStateListOf<String>()

    private val listener = object : SpeechListener {
        override fun onPartial(text: String) {
            partial = text
        }

        override fun onFinal(text: String) {
            if (text.isNotBlank()) phrases.add(0, text)
            partial = ""
        }

        override fun onLevel(level: Float) {
            this@RecognitionTest.level = level
        }

        override fun onError(message: String) {
            stopListening()
            status = ModelStatus.Failed(message)
        }
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
        level = 0f
    }

    fun clear() {
        phrases.clear()
        partial = ""
    }
}

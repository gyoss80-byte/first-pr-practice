package com.voiceprompter

import android.content.Context
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

enum class Lang(val code: String, val label: String) {
    EN("en", "English"),
    ES("es", "Español"),
}

/**
 * Offline speech recognition through Vosk. Models ship in assets/model-<code> and are
 * copied to app storage on first use. All callbacks arrive on the main thread.
 */
class SpeechEngine(private val context: Context) {
    private val models = mutableMapOf<Lang, Model>()
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null

    fun loadModel(lang: Lang, onReady: () -> Unit, onError: (String) -> Unit) {
        if (models.containsKey(lang)) {
            onReady()
            return
        }
        val dir = "model-${lang.code}"
        StorageService.unpack(
            context, dir, dir,
            { model ->
                models[lang] = model
                onReady()
            },
            { e -> onError(e.message ?: "Could not prepare the ${lang.label} speech model.") },
        )
    }

    /** Starts listening with an already loaded model. Returns false if the model isn't ready. */
    fun start(lang: Lang, listener: RecognitionListener): Boolean {
        val model = models[lang] ?: return false
        stop()
        val rec = Recognizer(model, SAMPLE_RATE)
        recognizer = rec
        service = SpeechService(rec, SAMPLE_RATE).also { it.startListening(listener) }
        return true
    }

    fun stop() {
        service?.let {
            it.stop()
            it.shutdown()
        }
        service = null
        recognizer?.close()
        recognizer = null
    }

    fun release() {
        stop()
        models.values.forEach { it.close() }
        models.clear()
    }

    private companion object {
        const val SAMPLE_RATE = 16000f
    }
}

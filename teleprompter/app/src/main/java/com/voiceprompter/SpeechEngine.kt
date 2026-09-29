package com.voiceprompter

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

enum class Lang(val code: String, val label: String) {
    EN("en", "English"),
    ES("es", "Español"),
}

/** Receives recognition events on the main thread. */
interface SpeechListener {
    fun onPartial(text: String)
    fun onFinal(text: String)

    /** Microphone loudness from 0 to 1, about ten times a second. */
    fun onLevel(level: Float) {}
    fun onError(message: String)

    /**
     * The mic has delivered pure digital silence for a few seconds. Real microphones always
     * pick up some noise, so this means Android is giving the audio to someone else (for
     * example, the video recording) and recognition can't hear the speaker.
     */
    fun onSilenced() {}
}

/**
 * Offline speech recognition through Vosk. Models ship in assets/model-<code> and are
 * copied to app storage on first use. Reads the microphone itself (16 kHz mono) so it can
 * report a level for the mic meter. All callbacks arrive on the main thread.
 */
class SpeechEngine(private val context: Context) {
    private val models = mutableMapOf<Lang, Model>()
    private val main = Handler(Looper.getMainLooper())
    private var session = 0
    private var running: AtomicBoolean? = null
    private var thread: Thread? = null
    private var recognizer: Recognizer? = null

    val isListening: Boolean get() = running?.get() == true

    fun isLoaded(lang: Lang) = models.containsKey(lang)

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

    /** Starts listening with an already loaded model. Returns false if it couldn't start. */
    @SuppressLint("MissingPermission") // callers request RECORD_AUDIO first
    /**
     * [vocabulary], when given, tells the recognizer which words to expect. Anything else is
     * heard as unknown and dropped, which helps with jargon and mixed-language scripts.
     */
    fun start(lang: Lang, listener: SpeechListener, vocabulary: Collection<String>? = null): Boolean {
        val model = models[lang] ?: return false
        stop()
        val rec = if (vocabulary.isNullOrEmpty()) {
            Recognizer(model, SAMPLE_RATE.toFloat())
        } else {
            // Words the model doesn't know are ignored by Vosk; [unk] catches everything else.
            val grammar = JSONArray((vocabulary + "[unk]").toList()).toString()
            runCatching { Recognizer(model, SAMPLE_RATE.toFloat(), grammar) }
                .getOrElse { Recognizer(model, SAMPLE_RATE.toFloat()) }
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, CHUNK * 4),
            )
        } catch (e: Exception) {
            rec.close()
            listener.onError("Couldn't open the microphone.")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            rec.close()
            listener.onError("Couldn't open the microphone. Another app may be using it.")
            return false
        }

        val id = ++session
        val flag = AtomicBoolean(true)
        fun post(block: () -> Unit) = main.post { if (id == session) block() }

        recognizer = rec
        running = flag
        thread = Thread({
            val buffer = ShortArray(CHUNK)
            var silentChunks = 0
            try {
                record.startRecording()
                while (flag.get()) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) {
                        post { listener.onError("The microphone stopped unexpectedly.") }
                        break
                    }
                    if (n == 0) continue
                    if ((0 until n).all { buffer[it].toInt() == 0 }) {
                        if (++silentChunks == SILENCED_CHUNKS) post { listener.onSilenced() }
                    } else {
                        silentChunks = 0
                    }
                    val level = level(buffer, n)
                    val final = rec.acceptWaveForm(buffer, n)
                    val text = if (final) field(rec.result, "text") else field(rec.partialResult, "partial")
                    post {
                        listener.onLevel(level)
                        if (final) listener.onFinal(text) else listener.onPartial(text)
                    }
                }
            } catch (e: Exception) {
                post { listener.onError(e.message ?: "The microphone stopped unexpectedly.") }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }, "speech").also { it.start() }
        return true
    }

    fun stop() {
        session++
        running?.set(false)
        thread?.join(1000)
        thread = null
        running = null
        recognizer?.close()
        recognizer = null
    }

    fun release() {
        stop()
        models.values.forEach { it.close() }
        models.clear()
    }

    private fun field(json: String, key: String): String =
        runCatching { JSONObject(json).optString(key) }.getOrDefault("")
            .replace("[unk]", " ").trim().replace(Regex("\\s+"), " ")

    /** RMS loudness mapped from roughly -50 dB..0 dB onto 0..1. */
    private fun level(buffer: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) {
            val s = buffer[i] / 32768.0
            sum += s * s
        }
        val rms = sqrt(sum / n)
        if (rms <= 0.0) return 0f
        return ((20 * log10(rms) + 50) / 50).coerceIn(0.0, 1.0).toFloat()
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK = 1600 // 0.1 s of audio
        const val SILENCED_CHUNKS = 30 // 3 s of exact zeros
    }
}

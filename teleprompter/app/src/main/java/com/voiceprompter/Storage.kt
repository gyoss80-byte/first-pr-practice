package com.voiceprompter

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.voiceprompter.tracker.ReadTime
import com.voiceprompter.tracker.Sensitivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Script(
    val id: String,
    val title: String,
    val lang: Lang,
    val text: String,
    val updatedAt: Long,
) {
    val displayTitle: String get() = title.ifBlank { "Untitled script" }
}

/** Scripts saved as one JSON file in app-private storage, newest first. */
class ScriptStore(context: Context) {
    private val file = File(context.filesDir, "scripts.json")
    val scripts = mutableStateListOf<Script>()

    init {
        if (file.exists()) load() else {
            scripts += Samples.all(System.currentTimeMillis())
            save()
        }
    }

    fun get(id: String): Script? = scripts.firstOrNull { it.id == id }

    fun create(title: String = "", lang: Lang = Lang.EN, text: String = ""): Script {
        val script = Script(UUID.randomUUID().toString(), title, lang, text, System.currentTimeMillis())
        scripts.add(0, script)
        save()
        return script
    }

    fun update(script: Script) {
        val index = scripts.indexOfFirst { it.id == script.id }
        if (index < 0) return
        scripts.removeAt(index)
        scripts.add(0, script.copy(updatedAt = System.currentTimeMillis()))
        save()
    }

    fun duplicate(id: String): Script? {
        val original = get(id) ?: return null
        return create("${original.displayTitle} (copy)", original.lang, original.text)
    }

    fun delete(id: String) {
        scripts.removeAll { it.id == id }
        save()
    }

    private fun load() {
        runCatching {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                scripts += Script(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    lang = if (o.optString("lang") == Lang.ES.code) Lang.ES else Lang.EN,
                    text = o.optString("text"),
                    updatedAt = o.optLong("updatedAt"),
                )
            }
            scripts.sortByDescending { it.updatedAt }
        }
    }

    private fun save() {
        val array = JSONArray()
        scripts.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("lang", it.lang.code)
                    .put("text", it.text)
                    .put("updatedAt", it.updatedAt),
            )
        }
        // Write to a temp file first so a crash mid-write can't wipe the scripts.
        val tmp = File(file.parentFile, "scripts.json.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }
}

data class PrompterSettings(
    val fontSize: Float = 36f,
    val lineSpacing: Float = 1.35f,
    val sideMargin: Float = 24f,
    val textColor: Int = TEXT_COLORS[0],
    val highlightColor: Int = HIGHLIGHT_COLORS[0],
    val cuePosition: Float = 0.3f,
    val mirror: Boolean = false,
    val countdown: Boolean = true,
    val sensitivity: Sensitivity = Sensitivity.NORMAL,
    val autoScroll: Boolean = false,
    /** Auto-scroll speed in dp per second. */
    val autoSpeed: Float = 40f,
    val showPace: Boolean = true,
    /** Words per minute; the pace indicator warns above this. */
    val targetPace: Float = 150f,
    /** Measured personal pace per language, in words per minute; 0 until measured. */
    val paceEn: Int = 0,
    val paceEs: Int = 0,
) {
    fun measuredPace(lang: Lang): Int? = (if (lang == Lang.ES) paceEs else paceEn).takeIf { it > 0 }

    fun withMeasuredPace(lang: Lang, wpm: Int) =
        if (lang == Lang.ES) copy(paceEs = wpm) else copy(paceEn = wpm)

    /** "about 2:40 at your pace", using the measured pace when there is one. */
    fun readTimeLabel(script: Script): String? {
        val measured = measuredPace(script.lang)
        val seconds = ReadTime.estimateSeconds(script.text, measured ?: ReadTime.DEFAULT_WPM)
        if (seconds == 0) return null
        return "about ${ReadTime.format(seconds)}" + if (measured != null) " at your pace" else ""
    }

    companion object {
        val TEXT_COLORS = listOf(0xFFF2EFE6.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFE680.toInt(), 0xFF9BE8A6.toInt())
        val HIGHLIGHT_COLORS = listOf(0xFFFFB020.toInt(), 0xFF4FC3F7.toInt(), 0xFF66E08A.toInt(), 0xFFFF7AB6.toInt())
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("prompter", Context.MODE_PRIVATE)

    fun load(): PrompterSettings {
        val d = PrompterSettings()
        return PrompterSettings(
            fontSize = prefs.getFloat("fontSize", d.fontSize),
            lineSpacing = prefs.getFloat("lineSpacing", d.lineSpacing),
            sideMargin = prefs.getFloat("sideMargin", d.sideMargin),
            textColor = prefs.getInt("textColor", d.textColor),
            highlightColor = prefs.getInt("highlightColor", d.highlightColor),
            cuePosition = prefs.getFloat("cuePosition", d.cuePosition),
            mirror = prefs.getBoolean("mirror", d.mirror),
            countdown = prefs.getBoolean("countdown", d.countdown),
            sensitivity = runCatching { Sensitivity.valueOf(prefs.getString("sensitivity", null)!!) }
                .getOrDefault(d.sensitivity),
            autoScroll = prefs.getBoolean("autoScroll", d.autoScroll),
            autoSpeed = prefs.getFloat("autoSpeed", d.autoSpeed),
            showPace = prefs.getBoolean("showPace", d.showPace),
            targetPace = prefs.getFloat("targetPace", d.targetPace),
            paceEn = prefs.getInt("paceEn", d.paceEn),
            paceEs = prefs.getInt("paceEs", d.paceEs),
        )
    }

    fun save(s: PrompterSettings) {
        prefs.edit()
            .putFloat("fontSize", s.fontSize)
            .putFloat("lineSpacing", s.lineSpacing)
            .putFloat("sideMargin", s.sideMargin)
            .putInt("textColor", s.textColor)
            .putInt("highlightColor", s.highlightColor)
            .putFloat("cuePosition", s.cuePosition)
            .putBoolean("mirror", s.mirror)
            .putBoolean("countdown", s.countdown)
            .putString("sensitivity", s.sensitivity.name)
            .putBoolean("autoScroll", s.autoScroll)
            .putFloat("autoSpeed", s.autoSpeed)
            .putBoolean("showPace", s.showPace)
            .putFloat("targetPace", s.targetPace)
            .putInt("paceEn", s.paceEn)
            .putInt("paceEs", s.paceEs)
            .apply()
    }
}

/** Two starter scripts, so the app opens with something to read. */
private object Samples {
    fun all(now: Long) = listOf(
        Script(
            UUID.randomUUID().toString(), "Try it out (English)", Lang.EN,
            """
            Welcome to Prompter. Read this out loud at your normal pace, and the text will follow your voice.

            If you stop talking, the text waits for you. [pause] See? It picks up again as soon as you continue.

            If you skip a sentence, it catches up. If you want to read something again, tap the word where you want to start.

            Numbers like ${'$'}14.99 or 50% are skipped over, so you can say them however you like.

            When you reach the end, the prompter stops listening and shows Done.
            """.trimIndent(),
            now,
        ),
        Script(
            UUID.randomUUID().toString(), "Prueba (Español)", Lang.ES,
            """
            Bienvenido a Prompter. Lee este texto en voz alta a tu ritmo normal, y el texto seguirá tu voz.

            Si dejas de hablar, el texto te espera. [pausa] ¿Ves? Continúa en cuanto sigues leyendo.

            Si te saltas una oración, se pone al día. Si quieres leer algo otra vez, toca la palabra donde quieres empezar.

            Los números como ${'$'}14.99 o 50% se saltan, así que puedes decirlos como quieras.

            Cuando llegas al final, deja de escuchar y muestra Done.
            """.trimIndent(),
            now - 1,
        ),
    )
}

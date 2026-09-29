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

    /** All scripts as one JSON backup file. */
    fun exportJson(): String = toJson(scripts).toString(2)

    /**
     * Adds the scripts from a backup made with [exportJson]. A script that's already here
     * (same id and text) is skipped; one with the same id but different text is added as a
     * copy, so nothing on the phone is overwritten. Returns how many scripts were added.
     */
    fun importJson(json: String): Int {
        val incoming = parse(json)
        var added = 0
        for (script in incoming) {
            val existing = get(script.id)
            when {
                existing == null -> scripts += script
                existing.text == script.text -> continue
                else -> scripts += script.copy(id = UUID.randomUUID().toString(), title = "${script.displayTitle} (restored)")
            }
            added++
        }
        scripts.sortByDescending { it.updatedAt }
        if (added > 0) save()
        return added
    }

    private fun load() {
        runCatching { scripts += parse(file.readText()) }
        scripts.sortByDescending { it.updatedAt }
    }

    private fun parse(json: String): List<Script> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Script(
                id = o.getString("id"),
                title = o.optString("title"),
                lang = if (o.optString("lang") == Lang.ES.code) Lang.ES else Lang.EN,
                text = o.optString("text"),
                updatedAt = o.optLong("updatedAt"),
            )
        }
    }

    private fun toJson(list: List<Script>): JSONArray {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("lang", it.lang.code)
                    .put("text", it.text)
                    .put("updatedAt", it.updatedAt),
            )
        }
        return array
    }

    private fun save() {
        // Write to a temp file first so a crash mid-write can't wipe the scripts.
        val tmp = File(file.parentFile, "scripts.json.tmp")
        tmp.writeText(toJson(scripts).toString())
        tmp.renameTo(file)
    }
}

/** How the prompter moves: following your voice, at a fixed speed, or only by hand. */
enum class ScrollMode(val label: String) {
    VOICE("Voice"),
    AUTO("Auto"),
    MANUAL("Manual"),
}

data class PrompterSettings(
    val fontSize: Float = 36f,
    val lineSpacing: Float = 1.35f,
    val sideMargin: Float = 24f,
    val textColor: Int = TEXT_COLORS[0],
    val highlightColor: Int = HIGHLIGHT_COLORS[0],
    val cuePosition: Float = 0.3f,
    val mirror: Boolean = false,
    /** Seconds of countdown before starting; 0 turns it off. */
    val countdownSeconds: Int = 3,
    val sensitivity: Sensitivity = Sensitivity.NORMAL,
    val scrollMode: ScrollMode = ScrollMode.VOICE,
    /** Auto-scroll speed in dp per second. */
    val autoSpeed: Float = 40f,
    val showPace: Boolean = true,
    /** Words per minute; the pace indicator warns above this. */
    val targetPace: Float = 150f,
    /** Measured personal pace per language, in words per minute; 0 until measured. */
    val paceEn: Int = 0,
    val paceEs: Int = 0,
    /** Show the camera behind the script, with the script in a band at the top. */
    val cameraOn: Boolean = false,
    val cameraFront: Boolean = true,
    /** Height of the script band over the camera, as a share of the screen. */
    val cameraBand: Float = 0.4f,
    /** How dark the band behind the script is, 0 (clear) to 1 (black). */
    val bandOpacity: Float = 0.6f,
    /** Record in 4K when the camera supports it, instead of 1080p. */
    val video4k: Boolean = false,
    /** Tell the speech recognizer to expect the script's own words. */
    val scriptWords: Boolean = false,
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
            // Older versions stored an on/off countdown and an auto-scroll switch.
            countdownSeconds = prefs.getInt("countdownSeconds", if (prefs.getBoolean("countdown", true)) 3 else 0),
            sensitivity = runCatching { Sensitivity.valueOf(prefs.getString("sensitivity", null)!!) }
                .getOrDefault(d.sensitivity),
            scrollMode = runCatching { ScrollMode.valueOf(prefs.getString("scrollMode", null)!!) }
                .getOrDefault(if (prefs.getBoolean("autoScroll", false)) ScrollMode.AUTO else d.scrollMode),
            autoSpeed = prefs.getFloat("autoSpeed", d.autoSpeed),
            showPace = prefs.getBoolean("showPace", d.showPace),
            targetPace = prefs.getFloat("targetPace", d.targetPace),
            paceEn = prefs.getInt("paceEn", d.paceEn),
            paceEs = prefs.getInt("paceEs", d.paceEs),
            cameraOn = prefs.getBoolean("cameraOn", d.cameraOn),
            cameraFront = prefs.getBoolean("cameraFront", d.cameraFront),
            cameraBand = prefs.getFloat("cameraBand", d.cameraBand),
            bandOpacity = prefs.getFloat("bandOpacity", d.bandOpacity),
            video4k = prefs.getBoolean("video4k", d.video4k),
            scriptWords = prefs.getBoolean("scriptWords", d.scriptWords),
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
            .putInt("countdownSeconds", s.countdownSeconds)
            .putString("sensitivity", s.sensitivity.name)
            .putString("scrollMode", s.scrollMode.name)
            .putFloat("autoSpeed", s.autoSpeed)
            .putBoolean("showPace", s.showPace)
            .putFloat("targetPace", s.targetPace)
            .putInt("paceEn", s.paceEn)
            .putInt("paceEs", s.paceEs)
            .putBoolean("cameraOn", s.cameraOn)
            .putBoolean("cameraFront", s.cameraFront)
            .putFloat("cameraBand", s.cameraBand)
            .putFloat("bandOpacity", s.bandOpacity)
            .putBoolean("video4k", s.video4k)
            .putBoolean("scriptWords", s.scriptWords)
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

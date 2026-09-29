package com.voiceprompter

import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceprompter.tracker.ReadTime
import com.voiceprompter.tracker.Sensitivity
import kotlin.math.roundToInt

val Amber = Color(0xFFFFB020)
val Dim = Color(0xFF8A8F98)
private val Surface = Color(0xFF15181D)
private val Rule = Color(0xFF262A31)

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

@Composable
private fun Header(title: String, left: @Composable () -> Unit = {}, right: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        left()
        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        right()
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), color = Dim, fontSize = 12.sp, letterSpacing = 1.sp)
}

// ---------------------------------------------------------------- Script list

@Composable
fun ScriptListScreen(
    store: ScriptStore,
    settings: PrompterSettings,
    onOpen: (Script) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf<Script?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching { readTextFile(context, uri) }
        result.onSuccess { (name, text) -> onOpen(store.create(name, guessLang(text), text)) }
        result.onFailure { importError = "Couldn't read that file. Pick a plain .txt file." }
    }
    var backupNote by remember { mutableStateOf<String?>(null) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupNote = runCatching {
            context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(store.exportJson()) }
            "Backed up ${store.scripts.size} scripts."
        }.getOrElse { "Couldn't save the backup there. Try another folder." }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupNote = runCatching {
            val json = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            when (val added = store.importJson(json)) {
                0 -> "Nothing new in that backup; your scripts are already here."
                1 -> "Restored 1 script."
                else -> "Restored $added scripts."
            }
        }.getOrElse { "That file isn't a Prompter backup." }
    }

    Page {
        Header("Scripts", right = { TextButton(onClick = onSettings) { Text("Settings") } })

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onOpen(store.create()) }, modifier = Modifier.weight(1f)) { Text("New script") }
            OutlinedButton(onClick = { importer.launch(arrayOf("text/plain")) }, modifier = Modifier.weight(1f)) {
                Text("Import .txt", color = Color.White)
            }
        }
        importError?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(backupNote ?: "Scripts live only in this app. Keep a backup on Drive or your phone.", color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                backup.launch("Prompter backup $date.json")
            }) { Text("Back up") }
            TextButton(onClick = { restore.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) { Text("Restore") }
        }

        if (store.scripts.isEmpty()) {
            Text("No scripts yet. Tap New script or import a .txt file.", color = Dim, fontSize = 16.sp)
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(store.scripts, key = { _, s -> s.id }) { index, script ->
                if (index > 0) HorizontalDivider(color = Rule)
                ScriptRow(
                    script = script,
                    settings = settings,
                    onOpen = { onOpen(script) },
                    onDuplicate = { store.duplicate(script.id) },
                    onDelete = { confirmDelete = script },
                )
            }
        }
    }

    confirmDelete?.let { script ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${script.displayTitle}”?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(script.id)
                    confirmDelete = null
                }) { Text("Delete", color = Color(0xFFFF8A80)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ScriptRow(
    script: Script,
    settings: PrompterSettings,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(script.displayTitle, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2)
            val edited = DateUtils.getRelativeTimeSpanString(script.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            val readTime = remember(script.text, script.lang, settings.paceEn, settings.paceEs) {
                settings.readTimeLabel(script)
            }
            Text(
                listOfNotNull(script.lang.label, readTime, "Edited $edited").joinToString(" · "),
                color = Dim,
                fontSize = 14.sp,
            )
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("•••", color = Dim) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                    menu = false
                    onDuplicate()
                })
                DropdownMenuItem(text = { Text("Delete") }, onClick = {
                    menu = false
                    onDelete()
                })
            }
        }
    }
}

private fun readTextFile(context: android.content.Context, uri: Uri): Pair<String, String> {
    val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
    var name = "Imported script"
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) name = c.getString(0).substringBeforeLast('.')
    }
    return name to text.replace("\r\n", "\n")
}

/** Spanish if the text has Spanish letters or enough common Spanish words. */
private fun guessLang(text: String): Lang {
    if (text.any { it in "ñÑ¿¡" }) return Lang.ES
    val words = text.lowercase().split(Regex("\\W+"))
    val es = words.count { it in setOf("el", "la", "los", "las", "que", "de", "y", "es", "por", "para", "con") }
    val en = words.count { it in setOf("the", "and", "is", "of", "to", "that", "for", "with", "you", "this") }
    return if (es > en) Lang.ES else Lang.EN
}

// ---------------------------------------------------------------- Editor

@Composable
fun EditorScreen(
    script: Script,
    settings: PrompterSettings,
    onChange: (Script) -> Unit,
    onStart: () -> Unit,
    onRecord: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Scripts") }
            Spacer(Modifier.weight(1f))
            Lang.entries.forEach { lang ->
                FilterChip(
                    selected = script.lang == lang,
                    onClick = { onChange(script.copy(lang = lang)) },
                    label = { Text(lang.code.uppercase()) },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        OutlinedTextField(
            value = script.title,
            onValueChange = { onChange(script.copy(title = it)) },
            placeholder = { Text("Title") },
            singleLine = true,
            textStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Color.White),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = script.text,
            onValueChange = { onChange(script.copy(text = it)) },
            placeholder = {
                Text(
                    "Type or paste your script. Put stage notes in brackets, like [pause]. " +
                        "Start a line with # to make a section, like # Intro.",
                )
            },
            textStyle = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, color = Color.White),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        val words = ReadTime.spokenWords(script.text)
        if (words > 0) {
            val readTime = settings.readTimeLabel(script)
            Text(
                listOfNotNull(readTime?.replaceFirstChar { it.uppercase() }, "$words words").joinToString(" · "),
                color = Dim,
                fontSize = 14.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onStart,
                enabled = script.text.isNotBlank(),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) { Text("Start", fontSize = 18.sp) }
            OutlinedButton(
                onClick = onRecord,
                enabled = script.text.isNotBlank(),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFE5383B)))
                Spacer(Modifier.size(10.dp))
                Text("Record", fontSize = 18.sp, color = Color.White)
            }
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(
    settings: PrompterSettings,
    onChange: (PrompterSettings) -> Unit,
    onMicTest: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Header("Settings", left = { TextButton(onClick = onBack) { Text("‹") } })

        // A live sample of the text settings.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B0C0E))
                .padding(horizontal = settings.sideMargin.dp.coerceAtMost(64.dp), vertical = 16.dp),
        ) {
            Text(
                "Already read. Now reading this line.",
                color = Color(settings.textColor),
                fontSize = (settings.fontSize * 0.6f).sp,
                lineHeight = (settings.fontSize * 0.6f * settings.lineSpacing).sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(settings.highlightColor)).padding(horizontal = 4.dp)) {
                    Text("Next", color = Color.Black, fontSize = (settings.fontSize * 0.6f).sp)
                }
                Text(" word.", color = Color(settings.textColor), fontSize = (settings.fontSize * 0.6f).sp)
            }
        }

        Label("Text")
        SliderRow("Font size", "${settings.fontSize.roundToInt()}", settings.fontSize, 20f..96f) {
            onChange(settings.copy(fontSize = it))
        }
        SliderRow("Line spacing", "%.2f×".format(settings.lineSpacing), settings.lineSpacing, 1f..2f) {
            onChange(settings.copy(lineSpacing = it))
        }
        SliderRow("Side margins", "${settings.sideMargin.roundToInt()} dp", settings.sideMargin, 0f..120f) {
            onChange(settings.copy(sideMargin = it))
        }
        ColorRow("Text color", PrompterSettings.TEXT_COLORS, settings.textColor) { onChange(settings.copy(textColor = it)) }
        ColorRow("Highlight color", PrompterSettings.HIGHLIGHT_COLORS, settings.highlightColor) {
            onChange(settings.copy(highlightColor = it))
        }

        HorizontalDivider(color = Rule)
        Label("Prompter")
        SliderRow(
            "Cue line position", "${(settings.cuePosition * 100).roundToInt()}% from top",
            settings.cuePosition, 0.1f..0.6f,
        ) { onChange(settings.copy(cuePosition = it)) }
        SwitchRow("Mirror mode", "Flip the text for a teleprompter glass.", settings.mirror) {
            onChange(settings.copy(mirror = it))
        }
        Text("Countdown before starting", color = Color.White, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Off", 3 to "3 s", 5 to "5 s", 10 to "10 s").forEach { (seconds, label) ->
                FilterChip(
                    selected = settings.countdownSeconds == seconds,
                    onClick = { onChange(settings.copy(countdownSeconds = seconds)) },
                    label = { Text(label) },
                )
            }
        }

        HorizontalDivider(color = Rule)
        Label("Camera")
        Text(
            "Tap Camera on the prompter screen to read and record yourself at the same time. " +
                "The script sits in a band at the top, near the front camera.",
            color = Dim, fontSize = 14.sp,
        )
        Text("Default camera", color = Color.White, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = settings.cameraFront, onClick = { onChange(settings.copy(cameraFront = true)) }, label = { Text("Front") })
            FilterChip(selected = !settings.cameraFront, onClick = { onChange(settings.copy(cameraFront = false)) }, label = { Text("Back") })
        }
        SliderRow("Script band height", "${(settings.cameraBand * 100).roundToInt()}% of the screen", settings.cameraBand, 0.2f..0.7f) {
            onChange(settings.copy(cameraBand = it))
        }
        SliderRow("Band darkness", "${(settings.bandOpacity * 100).roundToInt()}%", settings.bandOpacity, 0f..0.9f) {
            onChange(settings.copy(bandOpacity = it))
        }
        SwitchRow("Record in 4K", "Uses 4K when your camera supports it; otherwise the best it can. Bigger files.", settings.video4k) {
            onChange(settings.copy(video4k = it))
        }

        HorizontalDivider(color = Rule)
        Label("Voice following")
        Text("Matching sensitivity", color = Color.White, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sensitivity.entries.forEach { s ->
                FilterChip(
                    selected = settings.sensitivity == s,
                    onClick = { onChange(settings.copy(sensitivity = s)) },
                    label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        Text(
            "Relaxed follows loosely and moves on sooner. Strict waits for a closer match.",
            color = Dim, fontSize = 14.sp,
        )
        SwitchRow(
            "Listen for script words",
            "Tells the recognizer which words your script uses. Can help with trading terms and " +
                "English words in Spanish scripts. Try it; turn it off if tracking gets worse.",
            settings.scriptWords,
        ) { onChange(settings.copy(scriptWords = it)) }
        OutlinedButton(onClick = onMicTest, modifier = Modifier.fillMaxWidth()) {
            Text("Test recognition", color = Color.White)
        }

        HorizontalDivider(color = Rule)
        Label("Pace")
        SwitchRow("Show pace while reading", "Words per minute, with a warning when you rush.", settings.showPace) {
            onChange(settings.copy(showPace = it))
        }
        SliderRow("Target pace", "${settings.targetPace.roundToInt()} wpm", settings.targetPace, 100f..200f) {
            onChange(settings.copy(targetPace = it.roundToInt().toFloat()))
        }
        Text(
            "\"Slow down\" appears when you go more than 10% over the target. Around 150 wpm is a comfortable pace on camera.",
            color = Dim, fontSize = 14.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your measured pace", color = Color.White, fontSize = 16.sp)
                Text(
                    Lang.entries.joinToString("  ·  ") { lang ->
                        "${lang.label} " + (settings.measuredPace(lang)?.let { "$it wpm" } ?: "not yet")
                    },
                    color = Dim, fontSize = 14.sp,
                )
            }
            TextButton(
                onClick = { onChange(settings.copy(paceEn = 0, paceEs = 0)) },
                enabled = settings.paceEn > 0 || settings.paceEs > 0,
            ) { Text("Reset") }
        }
        Text(
            "Learned from your readings and used for the read-time estimates.",
            color = Dim, fontSize = 14.sp,
        )

        HorizontalDivider(color = Rule)
        Label("Scrolling")
        Text(
            "Voice follows you as you read. Auto scrolls at a fixed speed. Manual only moves when " +
                "you drag the text or use a remote. You can also switch on the prompter screen.",
            color = Dim, fontSize = 14.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScrollMode.entries.forEach { m ->
                FilterChip(selected = settings.scrollMode == m, onClick = { onChange(settings.copy(scrollMode = m)) }, label = { Text(m.label) })
            }
        }
        SliderRow("Auto-scroll speed", "${settings.autoSpeed.roundToInt()} dp/s", settings.autoSpeed, 10f..150f) {
            onChange(settings.copy(autoSpeed = it))
        }
        Spacer(Modifier.heightIn(min = 24.dp))
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(title, color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Text(value, color = Dim, fontSize = 15.sp)
        }
        Slider(value = current, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(detail, color = Dim, fontSize = 14.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ColorRow(title: String, colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        colors.forEach { c ->
            Box(
                Modifier
                    .padding(start = 10.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(3.dp, if (c == selected) Color.White else Color.Transparent, CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}

// ---------------------------------------------------------------- Recognition test

@Composable
fun MicTestScreen(test: RecognitionTest, permissionDenied: Boolean, onToggle: () -> Unit, onBack: () -> Unit) {
    Page {
        Header("Recognition test", left = { TextButton(onClick = onBack) { Text("‹") } })
        Text(
            "Read a few sentences out loud and check what the phone hears. Everything runs on the phone.",
            color = Dim, fontSize = 15.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Lang.entries.forEach { lang ->
                FilterChip(selected = test.lang == lang, onClick = { test.selectLang(lang) }, label = { Text(lang.label) })
            }
        }
        val (color, status) = when {
            permissionDenied -> Color(0xFFFF8A80) to "Microphone access is off. Allow it in Settings → Apps → Prompter → Permissions."
            test.status is ModelStatus.Failed -> Color(0xFFFF8A80) to (test.status as ModelStatus.Failed).message
            test.status == ModelStatus.Preparing -> Dim to "Preparing the ${test.lang.label} model. The first time takes up to a minute."
            test.listening -> Color(0xFF4CD37A) to "Listening"
            else -> Dim to "Ready"
        }
        Text(status, color = color, fontSize = 15.sp)
        Button(
            onClick = onToggle,
            enabled = test.status == ModelStatus.Ready,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            colors = if (test.listening) {
                ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3F47), contentColor = Color.White)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) { Text(if (test.listening) "Stop listening" else "Start listening", fontSize = 18.sp) }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Label("Hearing now")
            Text(
                test.partial.ifEmpty { if (test.listening) "…" else "" },
                color = Color.White, fontSize = 24.sp, lineHeight = 30.sp,
                modifier = Modifier.heightIn(min = 60.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Finished phrases")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = test::clear, enabled = test.phrases.isNotEmpty()) { Text("Clear") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(test.phrases) { phrase ->
                Text(phrase, color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp))
                HorizontalDivider(color = Rule)
            }
        }
    }
}

package com.voiceprompter

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.voiceprompter.tracker.ScriptTracker
import com.voiceprompter.tracker.Sensitivity
import com.voiceprompter.tracker.TokenKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Phase { Idle, Preparing, Countdown, Listening, Paused, Scrolling, Done }

private val NoteColor = Color(0xFF7FA7FF)
private val Panel = Color(0xE6161A20)
private val Listening = Color(0xFF4CD37A)

/** Connects the speech engine to the script tracker for one prompter session. */
class PrompterController(
    val script: Script,
    sensitivity: Sensitivity,
    private val engine: SpeechEngine,
) : SpeechListener {
    val tracker = ScriptTracker(script.text, sensitivity)
    var cursorToken by mutableIntStateOf(tracker.cursorToken)
        private set
    var nextToken by mutableIntStateOf(tracker.nextToken)
        private set
    var phase by mutableStateOf(Phase.Idle)
    var level by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)

    val running: Boolean
        get() = phase == Phase.Preparing || phase == Phase.Countdown ||
            phase == Phase.Listening || phase == Phase.Scrolling

    /** Loads the script's speech model if needed, then calls [onReady]. */
    fun prepare(onReady: () -> Unit) {
        if (engine.isLoaded(script.lang)) {
            onReady()
            return
        }
        phase = Phase.Preparing
        engine.loadModel(
            script.lang,
            onReady = { if (phase == Phase.Preparing) onReady() },
            onError = {
                error = it
                phase = Phase.Paused
            },
        )
    }

    fun startListening() {
        error = null
        phase = if (engine.start(script.lang, this)) Phase.Listening else Phase.Paused
    }

    fun stop(next: Phase = Phase.Paused) {
        engine.stop()
        level = 0f
        phase = next
    }

    fun jumpTo(tokenIndex: Int) {
        tracker.jumpTo(tokenIndex)
        sync()
        if (phase == Phase.Done) phase = Phase.Paused
    }

    fun restart() {
        if (running) stop()
        tracker.restart()
        sync()
        phase = Phase.Idle
    }

    private fun sync() {
        cursorToken = tracker.cursorToken
        nextToken = tracker.nextToken
    }

    private fun moved() {
        sync()
        if (tracker.isDone) stop(Phase.Done)
    }

    override fun onPartial(text: String) {
        if (tracker.onPartial(text)) moved()
    }

    override fun onFinal(text: String) {
        if (tracker.onFinal(text)) moved()
    }

    override fun onLevel(level: Float) {
        this.level = level
    }

    override fun onError(message: String) {
        error = message
        stop()
    }
}

@Composable
fun PrompterScreen(
    script: Script,
    settings: PrompterSettings,
    onSettingsChange: (PrompterSettings) -> Unit,
    engine: SpeechEngine,
    foreground: Boolean,
    onBack: () -> Unit,
) {
    val controller = remember(script.id, script.text, script.lang, settings.sensitivity) {
        PrompterController(script, settings.sensitivity, engine)
    }
    DisposableEffect(controller) { onDispose { engine.stop() } }
    ImmersiveMode()

    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var countdown by remember { mutableIntStateOf(0) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var touches by remember { mutableIntStateOf(0) }
    val autoMode = settings.autoScroll
    val tokens = controller.tracker.tokens
    val phase = controller.phase

    fun beginListening() {
        controller.prepare {
            if (settings.countdown) {
                controller.phase = Phase.Countdown
                countdownJob?.cancel()
                countdownJob = scope.launch {
                    for (n in 3 downTo 1) {
                        countdown = n
                        delay(1000)
                    }
                    if (controller.phase == Phase.Countdown) controller.startListening()
                }
            } else {
                controller.startListening()
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            beginListening()
        } else {
            controller.error = "Prompter needs the microphone to follow your voice. Allow it in " +
                "Settings → Apps → Prompter → Permissions, or turn on auto-scroll in Settings."
        }
    }

    fun play() {
        controller.error = null
        if (controller.phase == Phase.Done) controller.restart()
        if (autoMode) {
            if (controller.phase == Phase.Done || scroll.value >= scroll.maxValue) {
                scope.launch { scroll.scrollTo(0) }
            }
            controller.phase = Phase.Scrolling
            return
        }
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) beginListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun pause() {
        countdownJob?.cancel()
        controller.stop()
    }

    fun showControls() {
        controlsVisible = true
        touches++
    }

    fun lineTopOf(token: Int): Int {
        val l = layout ?: return 0
        if (token < 0 || tokens.isEmpty()) return 0
        return l.getLineTop(l.getLineForOffset(tokens[token].start)).toInt()
    }

    fun onWordTap(index: Int) {
        showControls()
        if (autoMode) {
            scope.launch { scroll.animateScrollTo(lineTopOf(index), tween(320, easing = FastOutSlowInEasing)) }
        } else {
            controller.jumpTo(index)
        }
    }

    // Leaving the app pauses; coming back picks up where it was.
    var resumeOnReturn by remember { mutableStateOf(false) }
    LaunchedEffect(foreground) {
        if (!foreground && controller.running) {
            resumeOnReturn = true
            pause()
        } else if (foreground && resumeOnReturn) {
            resumeOnReturn = false
            play()
        }
    }

    // Controls hide 3 s after the last touch while the prompter runs.
    val running = controller.running
    LaunchedEffect(controlsVisible, touches, running) {
        if (!running) {
            controlsVisible = true
        } else if (controlsVisible) {
            delay(3000)
            controlsVisible = false
        }
    }

    // Voice mode: keep the next word's line on the cue line.
    val focus = if (controller.nextToken >= 0) controller.nextToken else controller.cursorToken
    val target = if (autoMode) 0 else lineTopOf(focus)
    LaunchedEffect(target, autoMode) {
        if (!autoMode) scroll.animateScrollTo(target, tween(320, easing = FastOutSlowInEasing))
    }

    // Auto-scroll mode: fixed speed until the last line reaches the cue line.
    val speed by rememberUpdatedState(settings.autoSpeed)
    LaunchedEffect(phase == Phase.Scrolling) {
        if (phase != Phase.Scrolling) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val px = with(density) { speed.dp.toPx() } * (now - last) / 1_000_000_000f
            last = now
            scroll.dispatchRawDelta(px)
            val l = layout ?: continue
            if (scroll.value >= l.getLineTop(l.lineCount - 1)) {
                controller.phase = Phase.Done
                break
            }
        }
    }

    val textColor = Color(settings.textColor)
    val highlight = Color(settings.highlightColor)
    val text = remember(script.text, controller.cursorToken, controller.nextToken, textColor, highlight, autoMode) {
        buildAnnotatedString {
            append(script.text)
            val readEnd = if (!autoMode && controller.cursorToken >= 0) tokens[controller.cursorToken].end else 0
            if (readEnd > 0) addStyle(SpanStyle(color = textColor.copy(alpha = 0.35f)), 0, readEnd)
            tokens.filter { it.kind == TokenKind.NOTE }.forEach {
                val color = if (it.end <= readEnd) NoteColor.copy(alpha = 0.35f) else NoteColor
                addStyle(SpanStyle(color = color, fontStyle = FontStyle.Italic), it.start, it.end)
            }
            if (!autoMode && controller.nextToken >= 0) {
                val t = tokens[controller.nextToken]
                addStyle(SpanStyle(color = Color.Black, background = highlight), t.start, t.end)
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { if (controlsVisible && controller.running) controlsVisible = false else showControls() }
            },
    ) {
        val viewport = maxHeight
        val cue = viewport * settings.cuePosition
        val lineHeight = (settings.fontSize * settings.lineSpacing).sp

        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = if (settings.mirror) -1f else 1f }) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = phase != Phase.Listening && phase != Phase.Scrolling)) {
                Spacer(Modifier.height(cue))
                Text(
                    text,
                    style = TextStyle(
                        color = textColor,
                        fontSize = settings.fontSize.sp,
                        lineHeight = lineHeight,
                        fontWeight = FontWeight.Medium,
                    ),
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = settings.sideMargin.dp)
                        .pointerInput(tokens, autoMode) {
                            detectTapGestures { pos ->
                                val l = layout ?: return@detectTapGestures
                                val offset = l.getOffsetForPosition(pos)
                                val index = tokens.indexOfFirst { offset >= it.start && offset < it.end }
                                if (index >= 0) onWordTap(index) else showControls()
                            }
                        },
                )
                Spacer(Modifier.height(viewport))
            }

            // Cue marker: a small arrow and a faint rule at the reading line.
            val markerY = with(density) { cue.toPx() + lineHeight.toPx() / 2 }
            Canvas(Modifier.fillMaxSize()) {
                drawLine(highlight.copy(alpha = 0.12f), Offset(0f, markerY), Offset(size.width, markerY), 2f)
                val s = 9.dp.toPx()
                val arrow = Path().apply {
                    moveTo(0f, markerY - s)
                    lineTo(s * 1.2f, markerY)
                    lineTo(0f, markerY + s)
                    close()
                }
                drawPath(arrow, highlight)
            }
        }

        MicIndicator(
            listening = phase == Phase.Listening,
            level = controller.level,
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(14.dp),
        )

        CenterMessage(
            controller, countdown, highlight,
            onRestart = {
                controller.restart()
                scope.launch { scroll.animateScrollTo(0) }
            },
            onBack = onBack,
        )

        if (controlsVisible) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                PillButton("‹ Edit", Modifier.align(Alignment.TopStart), onClick = onBack)
                Column(
                    Modifier.align(Alignment.BottomCenter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (phase == Phase.Idle) {
                        Text(
                            if (autoMode) "Auto-scroll is on. Tap play to start." else "Tap play, then start reading. Tap any word to start there.",
                            color = Color(0xFFB9BEC6),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Panel).padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Restart") {
                            controller.restart()
                            scope.launch { scroll.animateScrollTo(0) }
                        }
                        PillButton("A−") { onSettingsChange(settings.copy(fontSize = (settings.fontSize - 4).coerceAtLeast(20f))) }
                        PlayButton(
                            playing = running,
                            color = highlight,
                            onClick = { if (running) pause() else play() },
                        )
                        PillButton("A+") { onSettingsChange(settings.copy(fontSize = (settings.fontSize + 4).coerceAtMost(96f))) }
                        PillButton(if (autoMode) "Auto" else "Voice") {
                            if (running) pause()
                            onSettingsChange(settings.copy(autoScroll = !autoMode))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(
    controller: PrompterController,
    countdown: Int,
    highlight: Color,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    val error = controller.error
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            controller.phase == Phase.Countdown ->
                Text("$countdown", color = highlight, fontSize = 120.sp, fontWeight = FontWeight.Bold)

            controller.phase == Phase.Preparing -> Message(
                "Preparing the ${controller.script.lang.label} speech model. The first time takes up to a minute.",
            )

            controller.phase == Phase.Done -> Column(
                Modifier.clip(RoundedCornerShape(16.dp)).background(Panel).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Done.", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                Button(onClick = onRestart) { Text("Read again") }
                OutlinedButton(onClick = onBack) { Text("Back to editor", color = Color.White) }
            }

            error != null -> Message(error, Color(0xFFFF8A80))
        }
    }
}

@Composable
private fun Message(text: String, color: Color = Color.White) {
    Text(
        text,
        color = color,
        fontSize = 18.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(32.dp).clip(RoundedCornerShape(12.dp)).background(Panel).padding(20.dp),
    )
}

@Composable
private fun PillButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PlayButton(playing: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(68.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp)) {
            val w = size.width
            val h = size.height
            if (playing) {
                val bar = w * 0.3f
                drawRect(Color.Black, Offset(w * 0.1f, 0f), androidx.compose.ui.geometry.Size(bar, h))
                drawRect(Color.Black, Offset(w * 0.6f, 0f), androidx.compose.ui.geometry.Size(bar, h))
            } else {
                drawPath(
                    Path().apply {
                        moveTo(w * 0.15f, 0f)
                        lineTo(w, h / 2)
                        lineTo(w * 0.15f, h)
                        close()
                    },
                    Color.Black,
                )
            }
        }
    }
}

/** A dot plus five bars: green and moving when the mic hears you. */
@Composable
private fun MicIndicator(listening: Boolean, level: Float, modifier: Modifier = Modifier) {
    val on = if (listening) Listening else Color(0xFF5B6068)
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Panel).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(on))
        Spacer(Modifier.width(3.dp))
        for (i in 0 until 5) {
            val lit = listening && level > (i + 0.5f) / 5f
            Box(
                Modifier
                    .width(3.dp)
                    .height((6 + i * 3).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (lit) Listening else Color(0xFF30343B)),
            )
        }
    }
}

@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { insets?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

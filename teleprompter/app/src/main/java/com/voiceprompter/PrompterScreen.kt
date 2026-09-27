package com.voiceprompter

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.viewinterop.AndroidView
import com.voiceprompter.tracker.ReadTime
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.voiceprompter.tracker.PaceMeter
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
private val RecordRed = Color(0xFFE5383B)

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

    private val pace = PaceMeter()
    private var lastWord = tracker.wordCursor

    /** Live speaking pace in words per minute, or null until there's enough speech. */
    var wordsPerMinute by mutableStateOf<Int?>(null)
        private set

    /** Android is giving the mic to someone else (like the video recording), so we hear silence. */
    var micBlocked by mutableStateOf(false)
        private set

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
        micBlocked = false
        phase = if (engine.start(script.lang, this)) Phase.Listening else Phase.Paused
    }

    fun stop(next: Phase = Phase.Paused) {
        engine.stop()
        pace.pause()
        level = 0f
        phase = next
    }

    fun jumpTo(tokenIndex: Int) {
        tracker.jumpTo(tokenIndex)
        pace.pause()
        lastWord = tracker.wordCursor
        sync()
        if (phase == Phase.Done) phase = Phase.Paused
    }

    fun restart() {
        if (running) stop()
        tracker.restart()
        pace.pause()
        lastWord = tracker.wordCursor
        sync()
        phase = Phase.Idle
    }

    private fun sync() {
        cursorToken = tracker.cursorToken
        nextToken = tracker.nextToken
    }

    /** This session's average pace, once; null if too little was read to trust it. */
    fun takeSessionPace(): Int? = pace.sessionWordsPerMinute.also { pace.clearSession() }

    private fun moved() {
        pace.onAdvance(tracker.wordCursor - lastWord, SystemClock.elapsedRealtime())
        lastWord = tracker.wordCursor
        wordsPerMinute = pace.wordsPerMinute
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

    override fun onSilenced() {
        micBlocked = true
    }
}

@Composable
fun PrompterScreen(
    script: Script,
    settings: PrompterSettings,
    onSettingsChange: (PrompterSettings) -> Unit,
    onPaceMeasured: (Lang, Int) -> Unit,
    engine: SpeechEngine,
    foreground: Boolean,
    onBack: () -> Unit,
) {
    val controller = remember(script.id, script.text, script.lang, settings.sensitivity) {
        PrompterController(script, settings.sensitivity, engine)
    }
    fun savePace() {
        controller.takeSessionPace()?.let { onPaceMeasured(script.lang, it) }
    }
    DisposableEffect(controller) {
        onDispose {
            engine.stop()
            savePace()
        }
    }
    LaunchedEffect(controller.phase) { if (controller.phase == Phase.Done) savePace() }
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

    val camera = remember { PrompterCamera(context.applicationContext) }
    DisposableEffect(camera) { onDispose { camera.release() } }
    val view = LocalView.current
    fun screenRotation() = view.display?.rotation ?: Surface.ROTATION_0
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    var hasCamera by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    val cameraOn = settings.cameraOn && hasCamera
    var pendingRecord by remember { mutableStateOf(false) }

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

    val recordPermissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.toTypedArray()
    val cameraPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            hasCamera = true
            onSettingsChange(settings.copy(cameraOn = true))
        } else {
            controller.error = "Prompter needs camera access to show and record video. Allow it in " +
                "Settings → Apps → Prompter → Permissions."
        }
    }

    fun toggleCamera() {
        when {
            camera.isRecording -> return
            settings.cameraOn && hasCamera -> onSettingsChange(settings.copy(cameraOn = false))
            recordPermissions.all(::granted) -> {
                hasCamera = true
                onSettingsChange(settings.copy(cameraOn = true))
            }
            else -> cameraPermissions.launch(recordPermissions)
        }
    }

    /** Record starts the recording and the prompter together, after the countdown. */
    fun record() {
        if (camera.isRecording) {
            camera.stop()
            pause()
            return
        }
        if (!recordPermissions.all(::granted)) {
            cameraPermissions.launch(recordPermissions)
            return
        }
        if (controller.phase == Phase.Listening || controller.phase == Phase.Scrolling) {
            camera.start(screenRotation())
        } else {
            pendingRecord = true
            play()
        }
    }
    LaunchedEffect(phase, pendingRecord) {
        if (!pendingRecord) return@LaunchedEffect
        when (phase) {
            Phase.Listening, Phase.Scrolling -> {
                pendingRecord = false
                camera.start(screenRotation())
            }
            Phase.Paused -> pendingRecord = false
            else -> {}
        }
    }
    LaunchedEffect(cameraOn) {
        if (cameraOn) camera.bind(context as ComponentActivity, settings.cameraFront) else camera.release()
    }
    LaunchedEffect(settings.cameraFront) { camera.useFront(settings.cameraFront) }
    LaunchedEffect(camera.message) {
        if (camera.message != null) {
            delay(5000)
            camera.message = null
        }
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
        if (!foreground && camera.isRecording) camera.stop()
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

    // Hand scrolling works at any time. While a finger moves the text, the prompter stops
    // following; once the scroll settles, reading continues from the line on the cue arrow.
    val dragging by scroll.interactionSource.collectIsDraggedAsState()
    var handScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(dragging) { if (dragging) handScrolled = true }
    LaunchedEffect(handScrolled, dragging, scroll.isScrollInProgress) {
        if (!handScrolled || dragging || scroll.isScrollInProgress) return@LaunchedEffect
        handScrolled = false
        val l = layout
        if (!autoMode && l != null) {
            val lineMiddle = with(density) { (settings.fontSize * settings.lineSpacing).sp.toPx() } / 2
            val offset = l.getOffsetForPosition(Offset(0f, scroll.value + lineMiddle))
            val index = tokens.indexOfFirst { it.end > offset }
            if (index >= 0) controller.jumpTo(index)
        }
    }

    // Voice mode: keep the next word's line on the cue line.
    val focus = if (controller.nextToken >= 0) controller.nextToken else controller.cursorToken
    val target = if (autoMode) 0 else lineTopOf(focus)
    LaunchedEffect(target, autoMode, handScrolled) {
        if (!autoMode && !handScrolled && !dragging) {
            scroll.animateScrollTo(target, tween(320, easing = FastOutSlowInEasing))
        }
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
            if (!dragging) scroll.dispatchRawDelta(px)
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
        if (cameraOn) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        camera.attach(this)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // With the camera on, the script sits in a see-through band at the top, near the lens.
        val viewport = if (cameraOn) maxHeight * settings.cameraBand else maxHeight
        val cue = viewport * settings.cuePosition
        val lineHeight = (settings.fontSize * settings.lineSpacing).sp

        Box(
            Modifier
                .fillMaxWidth()
                .height(viewport)
                .background(if (cameraOn) Color.Black.copy(alpha = settings.bandOpacity) else Color.Black)
                .clipToBounds()
                .graphicsLayer { scaleX = if (settings.mirror && !cameraOn) -1f else 1f },
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
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
            Canvas(Modifier.matchParentSize()) {
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

        Column(
            Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(14.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MicIndicator(listening = phase == Phase.Listening, level = controller.level)
            val wpm = controller.wordsPerMinute
            if (settings.showPace && !autoMode && phase == Phase.Listening && wpm != null) {
                PacePill(wpm, settings.targetPace.toInt(), highlight)
            }
        }

        Column(
            Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 14.dp, start = 90.dp, end = 90.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (camera.isRecording) RecordingPill(camera.elapsedMs)
        }
        if (controller.micBlocked && phase == Phase.Listening) {
            Banner(
                "Your phone is giving the microphone only to the video, so voice following can't hear you. " +
                    "Tap Voice to switch to Auto for recordings.",
                Modifier.align(Alignment.Center),
            )
        }
        camera.message?.let { Banner(it, Modifier.align(Alignment.Center)) }

        CenterMessage(
            controller, countdown, highlight,
            recording = camera.isRecording,
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
                            when {
                                cameraOn -> "Tap the red button to record and start the prompter together."
                                autoMode -> "Auto-scroll is on. Tap play to start."
                                else -> "Tap play, then start reading. Tap any word to start there."
                            },
                            color = Color(0xFFB9BEC6),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Panel).padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!camera.isRecording) {
                            PillButton(if (cameraOn) "Camera off" else "Camera") { toggleCamera() }
                        }
                        if (cameraOn) {
                            PillButton(if (settings.cameraFront) "Use back camera" else "Use front camera") {
                                onSettingsChange(settings.copy(cameraFront = !settings.cameraFront))
                            }
                            RecordButton(recording = camera.isRecording, onClick = ::record)
                        }
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
    recording: Boolean,
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

            controller.phase == Phase.Done && recording -> Message("Done. Tap the red button to stop recording.")

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

@Composable
private fun RecordingPill(elapsedMs: Long) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Panel).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(RecordRed))
        Text("REC ${ReadTime.format((elapsedMs / 1000).toInt())}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** Red dot to start recording; red square to stop. */
@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(Color.White).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (recording) 22.dp else 44.dp)
                .clip(if (recording) RoundedCornerShape(4.dp) else CircleShape)
                .background(RecordRed),
        )
    }
}

@Composable
private fun Banner(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(24.dp).clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp),
    )
}

/** Live words per minute; turns into a "Slow down" warning when you're over the target. */
@Composable
private fun PacePill(wpm: Int, target: Int, highlight: Color) {
    val fast = wpm > target * 1.1
    Text(
        if (fast) "Slow down · $wpm wpm" else "$wpm wpm",
        color = if (fast) Color.Black else Color(0xFFD5D8DD),
        fontSize = 15.sp,
        fontWeight = if (fast) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (fast) highlight else Panel)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
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

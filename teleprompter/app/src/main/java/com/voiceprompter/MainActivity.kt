package com.voiceprompter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.voiceprompter.tracker.ReadTime

private sealed interface Screen {
    data object List : Screen
    data class Editor(val id: String) : Screen
    data class Prompter(val id: String) : Screen
    data object Settings : Screen
    data object MicTest : Screen
}

class MainActivity : ComponentActivity() {
    /** Set by the prompter screen to handle Bluetooth remote and keyboard keys. */
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        keyHandler?.invoke(event) == true || super.dispatchKeyEvent(event)

    private lateinit var engine: SpeechEngine
    private lateinit var scripts: ScriptStore
    private lateinit var settingsStore: SettingsStore
    private lateinit var micTest: RecognitionTest

    private var screen by mutableStateOf<Screen>(Screen.List)
    private var settings by mutableStateOf(PrompterSettings())
    private var foreground by mutableStateOf(true)
    private var micDenied by mutableStateOf(false)

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micDenied = !granted
            if (granted) micTest.startListening()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine = SpeechEngine(applicationContext)
        scripts = ScriptStore(applicationContext)
        settingsStore = SettingsStore(applicationContext)
        settings = settingsStore.load()
        micTest = RecognitionTest(engine)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Amber, onPrimary = Color.Black)) {
                App()
            }
        }
    }

    @Composable
    private fun App() {
        BackHandler(enabled = screen != Screen.List) { back() }
        when (val s = screen) {
            Screen.List -> ScriptListScreen(
                store = scripts,
                settings = settings,
                onOpen = { screen = Screen.Editor(it.id) },
                onSettings = { screen = Screen.Settings },
            )

            is Screen.Editor -> {
                val script = scripts.get(s.id)
                if (script == null) {
                    screen = Screen.List
                } else {
                    EditorScreen(
                        script = script,
                        settings = settings,
                        onChange = scripts::update,
                        onStart = { screen = Screen.Prompter(s.id) },
                        onRecord = {
                            updateSettings(settings.copy(cameraOn = true))
                            screen = Screen.Prompter(s.id)
                        },
                        onBack = ::back,
                    )
                }
            }

            is Screen.Prompter -> {
                val script = scripts.get(s.id)
                if (script == null) {
                    screen = Screen.List
                } else {
                    PrompterScreen(
                        script = script,
                        settings = settings,
                        onSettingsChange = ::updateSettings,
                        onPaceMeasured = { lang, wpm ->
                            updateSettings(settings.withMeasuredPace(lang, ReadTime.learn(settings.measuredPace(lang), wpm)))
                        },
                        engine = engine,
                        foreground = foreground,
                        onBack = ::back,
                    )
                }
            }

            Screen.Settings -> SettingsScreen(
                settings = settings,
                onChange = ::updateSettings,
                onMicTest = {
                    screen = Screen.MicTest
                    micTest.prepare()
                },
                onBack = ::back,
            )

            Screen.MicTest -> MicTestScreen(
                test = micTest,
                permissionDenied = micDenied,
                onToggle = ::toggleMicTest,
                onBack = ::back,
            )
        }
    }

    private fun back() {
        screen = when (val s = screen) {
            Screen.List -> Screen.List
            is Screen.Editor -> Screen.List
            is Screen.Prompter -> Screen.Editor(s.id)
            Screen.Settings -> Screen.List
            Screen.MicTest -> {
                micTest.stopListening()
                Screen.Settings
            }
        }
    }

    private fun updateSettings(new: PrompterSettings) {
        settings = new
        settingsStore.save(new)
    }

    private fun toggleMicTest() {
        when {
            micTest.listening -> micTest.stopListening()
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> micTest.startListening()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onStop() {
        super.onStop()
        foreground = false
        micTest.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
    }
}

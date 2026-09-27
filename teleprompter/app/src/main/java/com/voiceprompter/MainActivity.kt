package com.voiceprompter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Amber = Color(0xFFFFB020)
private val Dim = Color(0xFF8A8F98)

class MainActivity : ComponentActivity() {
    private lateinit var engine: SpeechEngine
    private lateinit var test: RecognitionTest
    private var permissionDenied by mutableStateOf(false)

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionDenied = !granted
            if (granted) test.startListening()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine = SpeechEngine(applicationContext)
        test = RecognitionTest(engine)
        test.prepare()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Amber, onPrimary = Color.Black)) {
                RecognitionTestScreen(
                    test = test,
                    permissionDenied = permissionDenied,
                    onToggleListening = ::toggleListening,
                )
            }
        }
    }

    private fun toggleListening() {
        when {
            test.listening -> test.stopListening()
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> test.startListening()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        super.onStop()
        // Stop the mic when the app leaves the screen; the user taps Start again on return.
        test.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
    }
}

@Composable
private fun RecognitionTestScreen(
    test: RecognitionTest,
    permissionDenied: Boolean,
    onToggleListening: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Recognition test", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Text(
            "Read a few sentences out loud and check what the phone hears. " +
                "Everything runs on the phone, even in airplane mode.",
            color = Dim,
            fontSize = 15.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Lang.entries.forEach { lang ->
                FilterChip(
                    selected = test.lang == lang,
                    onClick = { test.selectLang(lang) },
                    label = { Text(lang.label) },
                )
            }
        }

        StatusLine(test, permissionDenied)

        Button(
            onClick = onToggleListening,
            enabled = test.status == ModelStatus.Ready,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            colors = if (test.listening) {
                ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3F47), contentColor = Color.White)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(if (test.listening) "Stop listening" else "Start listening", fontSize = 18.sp)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF15181D))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("HEARING NOW", color = Dim, fontSize = 12.sp, letterSpacing = 1.sp)
            Text(
                test.partial.ifEmpty { if (test.listening) "…" else "" },
                color = Color.White,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                modifier = Modifier.heightIn(min = 60.dp),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FINISHED PHRASES", color = Dim, fontSize = 12.sp, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = test::clear, enabled = test.phrases.isNotEmpty()) { Text("Clear") }
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(test.phrases) { index, phrase ->
                if (index > 0) HorizontalDivider(color = Color(0xFF262A31))
                Text(
                    phrase,
                    color = if (index == 0) Color.White else Color(0xFFC9CCD1),
                    fontSize = 18.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusLine(test: RecognitionTest, permissionDenied: Boolean) {
    val (color, text) = when {
        permissionDenied -> Color(0xFFFF6B5E) to
            "Microphone access is off. Allow it in Settings → Apps → Prompter → Permissions."
        test.status is ModelStatus.Failed -> Color(0xFFFF6B5E) to (test.status as ModelStatus.Failed).message
        test.status == ModelStatus.Preparing -> Dim to
            "Preparing the ${test.lang.label} model. The first time takes up to a minute."
        test.listening -> Color(0xFF4CD37A) to "Listening"
        else -> Dim to "Ready"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(text, color = color, fontSize = 15.sp)
    }
}

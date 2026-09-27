package com.voiceprompter

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.video.AudioConfig
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Camera preview and video recording for the prompter, through CameraX. Videos go to the
 * gallery under Movies/Prompter. Recording follows the phone's orientation when it starts.
 */
class PrompterCamera(private val context: Context) {
    val controller = LifecycleCameraController(context).apply {
        setEnabledUseCases(CameraController.VIDEO_CAPTURE)
        videoCaptureQualitySelector = QualitySelector.from(
            Quality.FHD,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD),
        )
    }

    private var recording: Recording? = null

    var isRecording by mutableStateOf(false)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        private set

    /** Last result to show the user, like "Saved to your gallery". */
    var message by mutableStateOf<String?>(null)

    fun bind(owner: LifecycleOwner, front: Boolean) {
        useFront(front)
        controller.bindToLifecycle(owner)
    }

    fun useFront(front: Boolean) {
        if (isRecording) return
        controller.cameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }

    @SuppressLint("MissingPermission") // the screen checks CAMERA and RECORD_AUDIO first
    fun start() {
        if (recording != null) return
        message = null
        val name = "Prompter_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Prompter")
            }
        }
        val output = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        elapsedMs = 0
        recording = try {
            controller.startRecording(output, AudioConfig.create(true), ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> isRecording = true
                    is VideoRecordEvent.Status -> elapsedMs = event.recordingStats.recordedDurationNanos / 1_000_000
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        recording = null
                        // SOURCE_INACTIVE means the camera closed (e.g. the app went to the background);
                        // the video up to that point is still saved.
                        val ok = !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                        message = if (ok) {
                            "Saved to your gallery (Movies › Prompter)."
                        } else {
                            "The recording couldn't be saved (error ${event.error})."
                        }
                    }
                }
            }
        } catch (e: Exception) {
            message = "Couldn't start recording: ${e.message ?: "the camera isn't ready"}."
            null
        }
    }

    fun stop() {
        recording?.stop()
    }

    fun release() {
        recording?.stop()
        recording = null
        controller.unbind()
    }
}

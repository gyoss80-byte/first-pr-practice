package com.voiceprompter

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
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
 * gallery under Movies/Prompter.
 *
 * Recordings are "persistent", so switching between the front and back camera while
 * recording keeps going in the same video file.
 */
class PrompterCamera(private val context: Context) {
    private var uhd = false
    private var recorder = buildRecorder(uhd)
    private var videoCapture = VideoCapture.withOutput(recorder)
    private val preview = Preview.Builder().build()
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var front = true
    private var recording: Recording? = null

    var isRecording by mutableStateOf(false)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        private set

    /** Last result to show the user, like "Saved to your gallery". */
    var message by mutableStateOf<String?>(null)

    /** The last video saved, for sharing; cleared along with [message]. */
    var lastVideo by mutableStateOf<Uri?>(null)

    /** 4K when the camera supports it (falls back to the best lower quality), else 1080p. */
    fun setUhd(uhd: Boolean) {
        if (this.uhd == uhd || recording != null) return
        this.uhd = uhd
        recorder = buildRecorder(uhd)
        videoCapture = VideoCapture.withOutput(recorder)
        rebind()
    }

    private fun buildRecorder(uhd: Boolean): Recorder {
        val quality = if (uhd) Quality.UHD else Quality.FHD
        return Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
            .build()
    }

    fun attach(view: PreviewView) {
        preview.setSurfaceProvider(view.surfaceProvider)
    }

    fun bind(owner: LifecycleOwner, front: Boolean) {
        this.owner = owner
        this.front = front
        val existing = provider
        if (existing != null) {
            rebind()
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            provider = runCatching { future.get() }.getOrNull()
            if (provider == null) message = "Couldn't open the camera." else if (this.owner != null) rebind()
        }, mainExecutor)
    }

    /** Switches cameras; works while recording too. */
    fun useFront(front: Boolean) {
        if (this.front == front) return
        this.front = front
        rebind()
    }

    private fun rebind() {
        val p = provider ?: return
        val o = owner ?: return
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val available = runCatching { p.hasCamera(selector) }.getOrDefault(false)
        if (!available) {
            message = "This phone has no ${if (front) "front" else "back"} camera."
            return
        }
        try {
            p.unbindAll()
            p.bindToLifecycle(o, selector, preview, videoCapture)
        } catch (e: Exception) {
            message = "Couldn't switch cameras: ${e.message ?: "the camera is busy"}."
        }
    }

    /** [rotation] is the screen rotation (Surface.ROTATION_*), so the video is saved upright. */
    @SuppressLint("MissingPermission") // the screen checks CAMERA and RECORD_AUDIO first
    @androidx.annotation.OptIn(ExperimentalPersistentRecording::class)
    fun start(rotation: Int, title: String) {
        if (recording != null) return
        message = null
        lastVideo = null
        videoCapture.targetRotation = rotation
        // "Options basics – 2026-09-29 14.05", safe for any file system.
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").trim().take(60).ifEmpty { "Prompter" }
        val name = "$safeTitle – " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
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
            recorder.prepareRecording(context, output)
                .withAudioEnabled()
                .asPersistentRecording()
                .start(mainExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> isRecording = true
                        is VideoRecordEvent.Status -> elapsedMs = event.recordingStats.recordedDurationNanos / 1_000_000
                        is VideoRecordEvent.Finalize -> {
                            isRecording = false
                            recording = null
                            // SOURCE_INACTIVE means the camera closed (e.g. the app went to the
                            // background); the video up to that point is still saved.
                            val ok = !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                            if (ok) lastVideo = event.outputResults.outputUri.takeIf { it != Uri.EMPTY }
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
        provider?.unbindAll()
        owner = null
    }
}

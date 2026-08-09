package com.inboxiq.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Voice memo -> text, entirely on-device via whisper.cpp (see app/src/main/cpp/).
 * Model is bundled as an APK asset (assets/models/ggml-tiny.en.bin, ~75MB, English-only —
 * the smallest/fastest variant, appropriate for short SMS-length voice memos), copied to
 * internal storage once on first use. No network call anywhere in this path.
 */
class VoiceTranscriber private constructor(private val context: Context) {

    private val whisper = WhisperLib(context)
    private val recorder = AudioRecorder()
    @Volatile private var initialized = false

    sealed interface Result {
        data class Success(val text: String) : Result
        data class Failure(val reason: String) : Result
    }

    fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun startRecording(): Boolean {
        if (!hasRecordPermission()) return false
        return recorder.start()
    }

    fun cancelRecording() = recorder.cancel()

    suspend fun stopAndTranscribe(): Result = withContext(Dispatchers.Default) {
        val samples = recorder.stopAndGetSamples()
        if (samples.isEmpty()) return@withContext Result.Failure("No audio recorded")

        if (!initialized) {
            val ready = ensureModelLoaded()
            if (!ready) return@withContext Result.Failure("Couldn't load the voice model")
        }

        val segments = try {
            whisper.transcribe(samples, language = "en")
        } catch (e: Exception) {
            return@withContext Result.Failure("Transcription failed")
        }

        val text = segments.joinToString(" ") { it.text }.trim()
        if (text.isEmpty()) Result.Failure("Couldn't make out any speech") else Result.Success(text)
    }

    private suspend fun ensureModelLoaded(): Boolean = withContext(Dispatchers.IO) {
        val modelFile = File(context.filesDir, "models/$MODEL_FILE_NAME")
        if (!modelFile.exists()) {
            modelFile.parentFile?.mkdirs()
            runCatching {
                context.assets.open("$MODEL_ASSET_DIR/$MODEL_FILE_NAME").use { input ->
                    modelFile.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { return@withContext false }
        }
        val ok = whisper.initialize(modelFile.absolutePath)
        initialized = ok
        ok
    }

    companion object {
        private const val MODEL_ASSET_DIR = "models"
        private const val MODEL_FILE_NAME = "ggml-tiny.en.bin"

        @Volatile private var instance: VoiceTranscriber? = null

        fun getInstance(context: Context): VoiceTranscriber =
            instance ?: synchronized(this) {
                instance ?: VoiceTranscriber(context.applicationContext).also { instance = it }
            }
    }
}

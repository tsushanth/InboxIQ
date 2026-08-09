package com.inboxiq.app.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Kotlin wrapper for whisper.cpp native library.
 * Provides speech-to-text transcription using OpenAI's Whisper model.
 */
class WhisperLib(private val context: Context) {

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }

        private const val TAG = "WhisperLib"
    }

    private var contextPtr: Long = 0

    /**
     * Initialize the Whisper context with a model file.
     * @param modelPath Path to the GGML model file
     * @return true if initialization succeeded
     */
    suspend fun initialize(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        if (contextPtr != 0L) {
            freeContext(contextPtr)
        }
        contextPtr = initContext(modelPath)
        contextPtr != 0L
    }

    /**
     * Initialize with a model from assets.
     * Copies the model to internal storage if needed.
     */
    suspend fun initializeFromAsset(assetName: String): Boolean = withContext(Dispatchers.IO) {
        val modelFile = File(context.filesDir, "models/$assetName")

        if (!modelFile.exists()) {
            modelFile.parentFile?.mkdirs()
            try {
                context.assets.open(assetName).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to copy model from assets", e)
                return@withContext false
            }
        }

        initialize(modelFile.absolutePath)
    }

    /**
     * Transcribe audio samples.
     * @param audioData PCM audio samples at 16kHz, mono, float32
     * @param language Language code (e.g., "en", "auto" for detection)
     * @param onProgress Progress callback (0-100)
     * @return List of transcription segments
     */
    suspend fun transcribe(
        audioData: FloatArray,
        language: String = "en",
        onProgress: ((Int) -> Unit)? = null
    ): List<TranscriptionSegment> = withContext(Dispatchers.Default) {
        if (contextPtr == 0L) {
            throw IllegalStateException("Whisper context not initialized")
        }

        val callback = onProgress?.let { ProgressCallback(it) }
        val jsonResult = transcribeAudio(contextPtr, audioData, language, callback)

        parseSegments(jsonResult)
    }

    /**
     * Check if the loaded model is multilingual.
     */
    fun isMultilingual(): Boolean {
        return contextPtr != 0L && isMultilingual(contextPtr)
    }

    /**
     * Get system info for debugging.
     */
    fun getSystemInfoString(): String = getSystemInfo()

    /**
     * Release native resources.
     */
    fun release() {
        if (contextPtr != 0L) {
            freeContext(contextPtr)
            contextPtr = 0
        }
    }

    private fun parseSegments(json: String): List<TranscriptionSegment> {
        if (json.isEmpty() || json == "[]") return emptyList()

        val segments = mutableListOf<TranscriptionSegment>()

        // Simple JSON parsing without external library
        val pattern = """\{"text":"((?:[^"\\]|\\.)*)","start":([0-9.]+),"end":([0-9.]+)\}""".toRegex()

        pattern.findAll(json).forEach { match ->
            val text = match.groupValues[1]
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .trim()

            val startMs = match.groupValues[2].toDoubleOrNull() ?: 0.0
            val endMs = match.groupValues[3].toDoubleOrNull() ?: 0.0

            if (text.isNotEmpty()) {
                segments.add(
                    TranscriptionSegment(
                        text = text,
                        startTimeMs = startMs.toLong(),
                        endTimeMs = endMs.toLong()
                    )
                )
            }
        }

        return segments
    }

    // JNI methods
    private external fun initContext(modelPath: String): Long
    private external fun freeContext(contextPtr: Long)
    private external fun transcribeAudio(
        contextPtr: Long,
        audioData: FloatArray,
        language: String,
        progressCallback: ProgressCallback?
    ): String
    private external fun getSystemInfo(): String
    private external fun isMultilingual(contextPtr: Long): Boolean
}

/**
 * A single transcription segment with timing information.
 */
data class TranscriptionSegment(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long
)

/**
 * Progress callback for JNI.
 */
class ProgressCallback(private val onProgress: (Int) -> Unit) {
    @Suppress("unused") // Called from JNI
    fun onProgress(progress: Int) {
        onProgress.invoke(progress)
    }
}

package com.inboxiq.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/** Records 16kHz mono PCM16 — the exact format whisper.cpp expects, converted to normalized Float32 on read. */
class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var buffer = ByteArrayOutputStream()
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false

    @SuppressLint("MissingPermission") // caller must have already checked RECORD_AUDIO
    fun start(): Boolean {
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufSize <= 0) return false

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufSize * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        buffer = ByteArrayOutputStream()
        audioRecord = record
        isRecording = true
        record.startRecording()

        recordingThread = Thread {
            val chunk = ByteArray(minBufSize)
            while (isRecording) {
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) {
                    synchronized(buffer) { buffer.write(chunk, 0, read) }
                }
            }
        }.apply { start() }

        return true
    }

    /** Stops recording and returns the captured audio as normalized [-1, 1] Float32 samples for whisper. */
    fun stopAndGetSamples(): FloatArray {
        isRecording = false
        recordingThread?.join(500)
        recordingThread = null

        val record = audioRecord
        audioRecord = null
        runCatching {
            record?.stop()
            record?.release()
        }

        val bytes = synchronized(buffer) { buffer.toByteArray() }
        return pcm16ToFloat(bytes)
    }

    fun cancel() {
        isRecording = false
        recordingThread?.join(500)
        recordingThread = null
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
    }

    private fun pcm16ToFloat(bytes: ByteArray): FloatArray {
        val samples = FloatArray(bytes.size / 2)
        for (i in samples.indices) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            samples[i] = sample / 32768f
        }
        return samples
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}

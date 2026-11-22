package com.glia.assistant.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captura PCM 16-bit mono 16kHz em streaming, emitindo buffers e amplitude (RMS).
 */
class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val bufferSize: Int = 1600 * 2 // 100ms
) {
    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private val processor = AudioProcessor()
    var onAmplitude: ((Float) -> Unit)? = null
    var onBuffer: ((ShortArray) -> Unit)? = null

    fun start() {
        if (running.get()) return
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, bufferSize)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        audioRecord?.startRecording()
        running.set(true)
        Thread {
            val buf = ShortArray(bufSize / 2)
            while (running.get()) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (read > 0) {
                    val slice = buf.copyOf(read)
                    val rms = processor.rms(slice, read)
                    onAmplitude?.invoke(rms)
                    onBuffer?.invoke(slice)
                }
            }
        }.start()
    }

    fun stop() {
        running.set(false)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun isRunning() = running.get()
}
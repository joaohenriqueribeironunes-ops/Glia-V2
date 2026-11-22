package com.glia.assistant.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captura PCM 16-bit mono 16kHz em streaming, emitindo buffers e amplitude (RMS).
 * start/stop sao serializados e a thread de leitura e encerrada com join antes
 * de liberar o AudioRecord, evitando leituras no buffer de uma sessao seguinte.
 */
class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val bufferSize: Int = 1600 * 2 // 100ms
) {
    companion object { private const val TAG = "AudioRecorder" }

    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val processor = AudioProcessor()
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var thread: Thread? = null

    @Volatile var onAmplitude: ((Float) -> Unit)? = null
    @Volatile var onBuffer: ((ShortArray) -> Unit)? = null
    @Volatile var onError: ((String) -> Unit)? = null

    fun start(): Boolean = synchronized(lock) {
        if (running.get()) return true
        audioRecord?.let { try { it.release() } catch (_: Exception) {} }
        audioRecord = null

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = if (minBuf > 0) maxOf(minBuf, bufferSize) else bufferSize

        val record = createRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufSize)
            ?: createRecord(MediaRecorder.AudioSource.MIC, bufSize)
        if (record == null) {
            val msg = "AudioRecord nao inicializou (bufSize=$bufSize)"
            Log.e(TAG, msg)
            onError?.invoke(msg)
            return false
        }

        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording falhou", e)
            record.release()
            onError?.invoke("startRecording falhou: ${e.message}")
            return false
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            onError?.invoke("AudioRecord nao entrou em RECORDING")
            return false
        }

        audioRecord = record
        running.set(true)
        thread = Thread {
            val buf = ShortArray(bufSize / 2)
            try {
                while (running.get() && audioRecord === record) {
                    val read = try {
                        record.read(buf, 0, buf.size)
                    } catch (e: Exception) {
                        Log.e(TAG, "read falhou", e)
                        -1
                    }
                    if (read <= 0) break
                    val slice = buf.copyOf(read)
                    val rms = try { processor.rms(slice, read) } catch (_: Exception) { 0f }
                    try { onAmplitude?.invoke(rms) } catch (e: Exception) { Log.e(TAG, "onAmplitude", e) }
                    try { onBuffer?.invoke(slice) } catch (e: Exception) { Log.e(TAG, "onBuffer", e) }
                }
            } finally {
                running.set(false)
            }
        }.also { it.name = "GliaAudio"; it.start() }
        Log.i(TAG, "Gravacao iniciada")
        true
    }

    private fun createRecord(source: Int, bufSize: Int): AudioRecord? {
        return try {
            val rec = AudioRecord(
                source, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                null
            } else {
                rec
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord source=$source falhou", e)
            null
        }
    }

    fun stop() = synchronized(lock) {
        running.set(false)
        val rec = audioRecord
        audioRecord = null
        val t = thread
        thread = null
        try { rec?.stop() } catch (_: Exception) {}
        if (t != null && t !== Thread.currentThread()) {
            try { t.join(600) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        try { rec?.release() } catch (_: Exception) {}
        try { onAmplitude?.invoke(0f) } catch (_: Exception) {}
        Log.i(TAG, "Gravacao parada")
    }

    fun isRunning() = running.get()
}

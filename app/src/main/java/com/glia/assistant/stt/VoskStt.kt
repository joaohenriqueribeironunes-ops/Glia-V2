package com.glia.assistant.stt

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * STT offline via Vosk. Requer modelo Vosk PT-BR em filesystem.
 * Download: https://alphacephei.com/vosk/models (vosk-model-small-pt-0.3)
 *
 * Todos os metodos sao synchronized porque o Recognizer nativo nao e thread-safe.
 */
class VoskStt(private val context: Context) : SttEngine {
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private val sampleRate = 16000f

    @Synchronized
    override fun init(modelPath: String): Boolean {
        releaseLocked()
        val dir = File(modelPath)
        if (!dir.isDirectory) {
            Log.e(TAG, "Modelo Vosk nao encontrado: $modelPath")
            return false
        }
        return try {
            val m = Model(modelPath)
            model = m
            recognizer = Recognizer(m, sampleRate)
            Log.i(TAG, "Vosk pronto: $modelPath")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Falha ao iniciar Vosk", e)
            releaseLocked()
            false
        }
    }

    @Synchronized
    override fun feed(buffer: ShortArray, length: Int): String? {
        val rec = recognizer ?: return null
        if (length <= 0) return null
        val n = minOf(length, buffer.size)
        return try {
            if (rec.acceptWaveForm(buffer, n)) {
                val json = JSONObject(rec.result)
                json.optString("text", "")
            } else {
                val json = JSONObject(rec.partialResult)
                json.optString("partial", "")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "feed falhou", e)
            null
        }
    }

    @Synchronized
    override fun finalize(): String {
        val rec = recognizer ?: return ""
        val text = try {
            val json = JSONObject(rec.finalResult)
            json.optString("text", "")
        } catch (e: Throwable) {
            Log.e(TAG, "finalize falhou", e)
            ""
        }
        // Cria um Recognizer novo para a proxima fala nao herdar estado.
        try { rec.close() } catch (_: Exception) {}
        recognizer = try {
            model?.let { Recognizer(it, sampleRate) }
        } catch (e: Exception) {
            Log.e(TAG, "reset do Recognizer falhou", e)
            null
        }
        return text
    }

    @Synchronized
    override fun release() {
        releaseLocked()
    }

    private fun releaseLocked() {
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        recognizer = null
        model = null
    }

    @Synchronized
    fun isReady(): Boolean = recognizer != null

    companion object {
        private const val TAG = "VoskStt"

        fun defaultModelDirs(context: Context): List<File> {
            return listOf(
                File(context.filesDir, "vosk-model-small-pt-0.3"),
                File(context.filesDir, "vosk-model-small-pt"),
                File("/storage/emulated/0/Download/vosk-model-small-pt-0.3"),
                File("/storage/emulated/0/Download/vosk-model-small-pt")
            )
        }
    }
}

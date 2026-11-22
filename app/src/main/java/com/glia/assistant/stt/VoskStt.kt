package com.glia.assistant.stt

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * STT offline via Vosk. Requer modelo Vosk PT-BR em filesystem.
 * Download: https://alphacephei.com/vosk/models (vosk-model-small-pt-0.3)
 */
class VoskStt(private val context: Context) : SttEngine {
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private val sampleRate = 16000f

    override fun init(modelPath: String): Boolean {
        return try {
            model = Model(modelPath)
            recognizer = Recognizer(model, sampleRate)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun feed(buffer: ShortArray, length: Int): String? {
        val rec = recognizer ?: return null
        return try {
            if (rec.acceptWaveForm(buffer, length)) {
                val json = JSONObject(rec.result)
                json.optString("text", "")
            } else {
                val json = JSONObject(rec.partialResult)
                json.optString("partial", "")
            }
        } catch (e: Exception) { null }
    }

    override fun finalize(): String {
        val rec = recognizer ?: return ""
        return try {
            val json = JSONObject(rec.finalResult)
            json.optString("text", "")
        } catch (e: Exception) { "" }
    }

    override fun release() {
        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
    }

    companion object {
        fun defaultModelDirs(context: Context): List<File> {
            return listOf(
                File(context.filesDir, "vosk-model-small-pt"),
                File("/storage/emulated/0/Download/vosk-model-small-pt")
            )
        }
    }
}
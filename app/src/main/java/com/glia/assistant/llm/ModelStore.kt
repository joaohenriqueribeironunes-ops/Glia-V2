package com.glia.assistant.llm

import android.content.Context
import java.io.File

/**
 * Garante que o modelo embutido (assets) esteja disponivel no armazenamento interno.
 * A copia roda fora da main thread (chamada por coroutine IO no servico).
 */
object ModelStore {
    const val ASSET_PATH = "models/Ternary-Bonsai-2-2B-Q2_0.gguf"
    const val FILE_NAME = "model.gguf"

    @Synchronized
    fun ensureModel(context: Context): File? {
        val out = File(context.filesDir, FILE_NAME)
        if (out.exists() && out.length() > 0L) return out
        return try {
            context.assets.open(ASSET_PATH).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
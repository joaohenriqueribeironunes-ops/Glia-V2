package com.glia.assistant.llm

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * Garante que o modelo embutido (assets) esteja disponivel no armazenamento interno.
 * A copia e atomica (.part) e valida tamanho + magic GGUF para nunca aceitar
 * uma copia parcial de um arquivo de centenas de MB.
 */
object ModelStore {
    private const val TAG = "ModelStore"
    const val ASSET_PATH = "models/Ternary-Bonsai-2-2B-Q2_0.gguf"
    const val FILE_NAME = "embedded-model.gguf"
    const val EXPECTED_SIZE = 546_689_504L

    @Synchronized
    fun ensureModel(context: Context): File? {
        val out = File(context.filesDir, FILE_NAME)
        if (isValid(out)) {
            Log.i(TAG, "Modelo embutido ja valido: ${out.length()} bytes")
            return out
        }
        if (out.exists()) {
            Log.w(TAG, "Modelo invalido/parcial (${out.length()} bytes); removendo")
            out.delete()
        }

        val free = context.filesDir.usableSpace
        if (free < EXPECTED_SIZE + 16L * 1024 * 1024) {
            Log.e(TAG, "Espaco insuficiente: livre=$free necessario=$EXPECTED_SIZE")
            return null
        }

        val part = File(context.filesDir, "$FILE_NAME.part")
        part.delete()
        return try {
            context.assets.open(ASSET_PATH).use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(1024 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        total += n
                    }
                    output.flush()
                    Log.i(TAG, "Copia concluida: $total bytes")
                }
            }
            if (!isValid(part)) {
                Log.e(TAG, "Copia invalida: ${part.length()} bytes (esperado $EXPECTED_SIZE)")
                part.delete()
                return null
            }
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) {
                part.copyTo(out, overwrite = true)
                part.delete()
            }
            if (!isValid(out)) {
                Log.e(TAG, "Falha ao promover copia para ${out.name}")
                out.delete()
                null
            } else {
                Log.i(TAG, "Modelo embutido pronto: ${out.length()} bytes")
                out
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Falha ao copiar modelo", e)
            part.delete()
            null
        }
    }

    fun isValid(file: File): Boolean {
        if (!file.isFile) return false
        if (EXPECTED_SIZE > 0 && file.length() != EXPECTED_SIZE) return false
        return try {
            FileInputStream(file).use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 &&
                    magic[0] == 'G'.code.toByte() &&
                    magic[1] == 'G'.code.toByte() &&
                    magic[2] == 'U'.code.toByte() &&
                    magic[3] == 'F'.code.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }
}

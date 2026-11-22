package com.glia.assistant.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * TTS robusto: nao perde frases antes do init, nao trava em speaking=true
 * quando o engine falha e tenta pt-BR, locale do sistema e en-US nessa ordem.
 */
class GliaTts(context: Context) {
    companion object { private const val TAG = "GliaTts" }

    private val queue = ConcurrentLinkedQueue<String>()
    private val utteranceCounter = AtomicInteger(0)
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var speaking = false
    @Volatile private var initError: String? = null
    @Volatile private var language = "?"
    private var consecutiveFailures = 0

    var onFinished: (() -> Unit)? = null
    var onTtsError: ((String) -> Unit)? = null
    var onReady: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                initError = "init status=$status"
                ready = false
                Log.e(TAG, initError!!)
                onTtsError?.invoke(initError!!)
                return@TextToSpeech
            }
            val engine = tts
            if (engine == null) {
                initError = "engine nulo"
                Log.e(TAG, initError!!)
                onTtsError?.invoke(initError!!)
                return@TextToSpeech
            }
            try {
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                var res = engine.setLanguage(Locale("pt", "BR"))
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    res = engine.setLanguage(Locale.getDefault())
                }
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    res = engine.setLanguage(Locale.US)
                }
                language = if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    "indisponivel"
                } else {
                    engine.voice?.locale?.toLanguageTag() ?: "padrao"
                }
                engine.setSpeechRate(1.05f)
                engine.setPitch(1.0f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        speaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        speaking = false
                        if (queue.isNotEmpty()) speakNext() else onFinished?.invoke()
                    }

                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "onError: $utteranceId")
                        speaking = false
                        onTtsError?.invoke("falha ao falar")
                        if (queue.isNotEmpty()) speakNext()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "onError: $utteranceId code=$errorCode")
                        speaking = false
                        onTtsError?.invoke("falha ao falar (codigo $errorCode)")
                        if (queue.isNotEmpty()) speakNext()
                    }
                })
                ready = true
                Log.i(TAG, "TTS pronto, idioma=$language")
                onReady?.invoke()
                speakNext()
            } catch (e: Exception) {
                initError = e.message ?: e.toString()
                ready = false
                Log.e(TAG, "init", e)
                onTtsError?.invoke(initError!!)
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        splitSentences(text).forEach { if (it.isNotBlank()) queue.offer(it.trim()) }
        if (ready) speakNext() else Log.w(TAG, "TTS nao pronto; ${queue.size} frase(s) na fila")
    }

    private fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            sb.append(c)
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                val next = text.getOrNull(i + 1)
                if (c == '\n' || next == null || next.isWhitespace()) {
                    val s = sb.toString().trim()
                    if (s.isNotEmpty()) out.add(s)
                    sb.clear()
                }
            }
            i++
        }
        val rest = sb.toString().trim()
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    @Synchronized
    private fun speakNext() {
        if (!ready || speaking) return
        val next = queue.poll() ?: return
        val engine = tts
        if (engine == null) {
            onTtsError?.invoke("TextToSpeech indisponivel")
            return
        }
        speaking = true
        val id = "glia_${utteranceCounter.incrementAndGet()}"
        val result = engine.speak(next, TextToSpeech.QUEUE_ADD, null, id)
        if (result != TextToSpeech.SUCCESS) {
            speaking = false
            consecutiveFailures++
            Log.e(TAG, "speak retornou $result")
            onTtsError?.invoke("speak retornou $result")
            if (consecutiveFailures < 3 && queue.isNotEmpty()) {
                speakNext()
            } else {
                queue.clear()
                onTtsError?.invoke("TTS falhou repetidamente")
            }
        } else {
            consecutiveFailures = 0
        }
    }

    fun stop() {
        queue.clear()
        speaking = false
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun shutdown() {
        stop()
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ready = false
    }

    fun isReady() = ready
    fun isSpeaking() = speaking || queue.isNotEmpty()
    fun status(): String = when {
        initError != null -> "erro: $initError"
        ready -> "pronto ($language)"
        else -> "inicializando"
    }
}

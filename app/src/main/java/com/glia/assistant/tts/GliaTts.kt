package com.glia.assistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

class GliaTts(context: Context) {
    private var tts: TextToSpeech? = null
    private val queue = ConcurrentLinkedQueue<String>()
    private var ready = false
    private var speaking = false
    var onDone: (() -> Unit)? = null
    var onSpeakStart: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                var res = tts?.setLanguage(Locale("pt", "BR"))
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    res = tts?.setLanguage(Locale.getDefault())
                }
                ready = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setSpeechRate(1.05f)
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { onSpeakStart?.invoke() }
                    override fun onDone(utteranceId: String?) {
                        speaking = false
                        if (queue.isNotEmpty()) speakNext() else onDone?.invoke()
                    }
                    override fun onError(utteranceId: String?) { speaking = false; onDone?.invoke() }
                })
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        // Quebra em sentencas para streaming
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        sentences.forEach { queue.offer(it.trim()) }
        if (!speaking) speakNext()
    }

    private fun speakNext() {
        val next = queue.poll() ?: return
        speaking = true
        tts?.speak(next, TextToSpeech.QUEUE_ADD, null, "utt_${System.currentTimeMillis()}")
    }

    fun stop() {
        queue.clear()
        tts?.stop()
        speaking = false
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    fun isReady() = ready
    fun isSpeaking() = speaking || queue.isNotEmpty()
}
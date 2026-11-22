package com.glia.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.glia.assistant.GliaApplication
import com.glia.assistant.MainActivity
import com.glia.assistant.R
import com.glia.assistant.audio.AudioRecorder
import com.glia.assistant.llm.LlamaBridge
import com.glia.assistant.llm.TokenCallback
import com.glia.assistant.stt.VoskStt
import com.glia.assistant.tts.GliaTts
import com.glia.assistant.ui.GliaMode
import com.glia.assistant.ui.GliaState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class GliaVoiceService : Service() {

    companion object {
        const val CHANNEL_ID = "glia_voice"
        const val NOTIF_ID = 1001
        const val ACTION_START_LISTENING = "com.glia.assistant.START_LISTENING"
        const val ACTION_STOP = "com.glia.assistant.STOP"
        @Volatile var instance: GliaVoiceService? = null
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private lateinit var recorder: AudioRecorder
    private lateinit var stt: VoskStt
    private lateinit var tts: GliaTts
    private lateinit var llm: LlamaBridge

    private var wakeJob: Job? = null
    private var continuous = false
    private var modelPath: String? = null
    private var recognizerReady = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        recorder = AudioRecorder()
        stt = VoskStt(this)
        tts = GliaTts(this)
        llm = LlamaBridge()

        // Carrega modelo Vosk se existir
        val voskDirs = VoskStt.defaultModelDirs(this)
        val voskPath = voskDirs.firstOrNull { it.exists() }?.absolutePath
        if (voskPath != null) {
            recognizerReady = stt.init(voskPath)
        }

        // Carrega modelo LLM se configurado
        val prefs = getSharedPreferences("glia", MODE_PRIVATE)
        var mp = prefs.getString("model_path", null)
        if (mp == null || !File(mp).exists()) {
            val embedded = File(filesDir, "model.gguf")
            if (embedded.exists()) {
                mp = embedded.absolutePath
                prefs.edit().putString("model_path", mp).apply()
            }
        }
        modelPath = mp
        if (mp != null && File(mp).exists()) {
            val ctx = prefs.getInt("context_size", 2048)
            val thr = prefs.getInt("threads", 4)
            llm.loadModel(mp, ctx, thr)
        }

        recorder.onAmplitude = { GliaState.setAmplitude(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Glia ouvindo wake word…"))
        when (intent?.action) {
            ACTION_START_LISTENING -> {
                scope.launch { startActiveListening(bypassWake = true) }
            }
            ACTION_STOP -> {
                stopSelf()
            }
            else -> {
                startWakeWordLoop()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        recorder.stop()
        stt.release()
        tts.shutdown()
        llm.unload()
        instance = null
        super.onDestroy()
    }

    // ---------- Wake word loop ----------
    private fun startWakeWordLoop() {
        if (wakeJob?.isActive == true) return
        GliaState.setMode(GliaMode.IDLE)
        recorder.onBuffer = { buf ->
            if (recognizerReady && GliaState.mode.value == GliaMode.IDLE) {
                scope.launch(Dispatchers.Default) {
                    val partial = stt.feed(buf, buf.size)
                    if (partial != null && partial.lowercase().contains("ei glia")) {
                        stt.finalize()
                        triggerWake()
                    }
                }
            }
        }
        wakeJob = scope.launch {
            recorder.start()
            while (isActive) { delay(500) }
        }
    }

    private suspend fun triggerWake() {
        GliaState.setMode(GliaMode.LISTENING)
        // feedback haptico + sonoro curto
        vibrate(80)
        // toca beep do sistema
        try {
            val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 60)
            toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (_: Exception) {}

        recorder.stop()
        delay(200)
        startActiveListening(bypassWake = false)
    }

    // ---------- Active listening ----------
    private suspend fun startActiveListening(bypassWake: Boolean) {
        GliaState.setMode(GliaMode.LISTENING)
        GliaState.setPartial("")
        GliaState.setResponse("")

        // Reinicia recognizer
        if (recognizerReady) {
            stt.release()
            val voskDirs = VoskStt.defaultModelDirs(this)
            val voskPath = voskDirs.firstOrNull { it.exists() }?.absolutePath
            if (voskPath != null) recognizerReady = stt.init(voskPath)
        }

        recorder.onBuffer = { buf ->
            if (recognizerReady) {
                scope.launch(Dispatchers.Default) {
                    val partial = stt.feed(buf, buf.size)
                    if (partial != null && partial.isNotBlank()) {
                        GliaState.setPartial(partial)
                    }
                }
            }
        }
        recorder.start()

        // Espera silencio / fim do comando
        var lastSpeech = System.currentTimeMillis()
        var hasSpeech = false
        var totalStart = System.currentTimeMillis()
        while (scope.isActive && GliaState.mode.value == GliaMode.LISTENING) {
            val amp = GliaState.amplitude.value
            if (amp > 0.02f) { lastSpeech = System.currentTimeMillis(); hasSpeech = true }
            val silenceMs = System.currentTimeMillis() - lastSpeech
            val totalMs = System.currentTimeMillis() - totalStart
            if (hasSpeech && silenceMs > 1200) break
            if (totalMs > 10000) break
            delay(100)
        }

        // Finaliza transcricao
        recorder.stop()
        val text = if (recognizerReady) stt.finalize() else ""
        val finalText = text.ifBlank { GliaState.partialText.value }
        GliaState.setPartial(finalText)

        if (finalText.isBlank()) {
            // volta a ouvir wake
            GliaState.setMode(GliaMode.IDLE)
            startWakeWordLoop()
            return
        }

        processWithLlm(finalText)
    }

    private suspend fun processWithLlm(userText: String) {
        GliaState.setMode(GliaMode.THINKING)
        // Ternary Bonsai 2 2B (base Qwen3.5): chat template com thinking desligado
        val prompt = "<|im_start|>system\nVoce e a Glia, assistente pessoal offline em portugues. Responda de forma curta e natural.<|im_end|>\n<|im_start|>user\n$userText<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        val sentenceBuffer = StringBuilder()
        val callback = object : TokenCallback {
            override fun onToken(token: String) {
                GliaState.appendResponse(token)
                sentenceBuffer.append(token)
                // Detecta fim de sentenca
                val buf = sentenceBuffer.toString()
                val idx = maxOf(buf.lastIndexOf('.'), buf.lastIndexOf('!'), buf.lastIndexOf('?'))
                if (idx >= 0 && idx < buf.length - 1) {
                    val sentence = buf.substring(0, idx + 1).trim()
                    if (sentence.isNotBlank()) {
                        tts.speak(sentence)
                        sentenceBuffer.clear()
                        sentenceBuffer.append(buf.substring(idx + 1))
                    }
                }
            }
            override fun onDone() {
                val rest = sentenceBuffer.toString().trim()
                if (rest.isNotBlank()) tts.speak(rest)
                sentenceBuffer.clear()
            }
        }

        if (llm.isLoaded()) {
            llm.generate(prompt, callback, 0.7f)
            // aguarda geracao terminar
            while (llm.isGenerating()) { delay(100) }
            // Se nada foi falado/gerado, fallback
            if (GliaState.responseText.value.isBlank()) {
                val fallback = "Nao consegui responder agora."
                GliaState.setResponse(fallback)
                tts.speak(fallback)
            }
        } else {
            // Fallback sem modelo: resposta simples
            val fallback = "Entendi: $userText. (Modelo LLM nao carregado. Importe um .gguf nas configuracoes.)"
            GliaState.setResponse(fallback)
            tts.speak(fallback)
        }

        GliaState.setMode(GliaMode.SPEAKING)
        // Aguarda TTS terminar
        while (tts.isSpeaking()) { delay(200) }

        // Conversa continua: mantem mic aberto 5s
        GliaState.setMode(GliaMode.LISTENING)
        GliaState.setPartial("")
        val continuousStart = System.currentTimeMillis()
        var spoke = false
        recorder.onBuffer = { buf ->
            if (recognizerReady) {
                scope.launch(Dispatchers.Default) {
                    val partial = stt.feed(buf, buf.size)
                    if (partial != null && partial.isNotBlank()) {
                        GliaState.setPartial(partial)
                        spoke = true
                    }
                }
            }
        }
        // Reinicia recognizer para nova captura
        if (recognizerReady) {
            stt.release()
            val voskDirs = VoskStt.defaultModelDirs(this)
            val voskPath = voskDirs.firstOrNull { it.exists() }?.absolutePath
            if (voskPath != null) recognizerReady = stt.init(voskPath)
        }
        recorder.start()

        var lastSpeech = System.currentTimeMillis()
        while (scope.isActive && System.currentTimeMillis() - continuousStart < 5000) {
            val amp = GliaState.amplitude.value
            if (amp > 0.02f) { lastSpeech = System.currentTimeMillis(); spoke = true }
            if (spoke && System.currentTimeMillis() - lastSpeech > 1200) break
            delay(100)
        }

        recorder.stop()
        if (spoke) {
            val text = if (recognizerReady) stt.finalize() else ""
            val ft = text.ifBlank { GliaState.partialText.value }
            if (ft.isNotBlank()) {
                // processa novo comando recursivamente
                processWithLlm(ft)
                return
            }
        }

        // Volta para wake word
        GliaState.setMode(GliaMode.IDLE)
        GliaState.setPartial("")
        startWakeWordLoop()
    }

    private fun vibrate(ms: Long) {
        val v = getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Glia")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_glia)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
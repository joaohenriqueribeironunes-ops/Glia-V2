package com.glia.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.glia.assistant.service.GliaVoiceService

class GliaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                GliaVoiceService.CHANNEL_ID,
                "Glia Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Servico de voz da Glia em segundo plano" }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
        // Extrai modelo embutido (assets) para filesDir se necessario
        try {
            val outFile = java.io.File(filesDir, "model.gguf")
            if (!outFile.exists() || outFile.length() == 0L) {
                assets.open("models/Ternary-Bonsai-2-2B-Q2_0.gguf").use { input ->
                    outFile.outputStream().use { out -> input.copyTo(out) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
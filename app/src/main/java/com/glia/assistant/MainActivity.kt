package com.glia.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.glia.assistant.service.GliaVoiceService
import com.glia.assistant.ui.GliaMode
import com.glia.assistant.ui.GliaState
import com.glia.assistant.ui.SettingsActivity

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPerms()
        setContent { MainScreen(this) }
        // Inicia servico de voz
        startService(Intent(this, GliaVoiceService::class.java))
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permLauncher.launch(perms.toTypedArray())
        }
    }
}

@Composable
fun MainScreen(activity: ComponentActivity) {
    val mode by GliaState.mode.collectAsState()
    val amp by GliaState.amplitude.collectAsState()
    val partial by GliaState.partialText.collectAsState()
    val response by GliaState.responseText.collectAsState()

    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.9f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "pulse"
    )

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            OrbView(amplitude = amp, pulse = pulse, mode = mode)
        }
        Text(
            text = when (mode) {
                GliaMode.IDLE -> "Dormindo - diga \"Ei Glia\""
                GliaMode.LISTENING -> "Ouvindo…"
                GliaMode.THINKING -> "Pensando…"
                GliaMode.SPEAKING -> "Falando…"
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        if (partial.isNotBlank()) Text(partial, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A8AA6), textAlign = TextAlign.Center)
        if (response.isNotBlank()) Text(response, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        RowButtons(activity)
    }
}

@Composable
fun RowButtons(activity: ComponentActivity) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }) { Text("Configuracoes") }
    }
}

@Composable
fun OrbView(amplitude: Float, pulse: Float, mode: GliaMode) {
    val colorCore = when (mode) {
        GliaMode.LISTENING -> Color(0xFF4FD1C5)
        GliaMode.THINKING -> Color(0xFFA78BFA)
        GliaMode.SPEAKING -> Color(0xFF7C6CFF)
        GliaMode.IDLE -> Color(0xFF6D28D9)
    }
    Canvas(Modifier.fillMaxSize(0.6f)) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = minOf(size.width, size.height) / 3
        val ampScale = 1f + (amplitude.coerceIn(0f, 1f) * 0.6f)
        val radius = baseRadius * pulse * ampScale
        // glow
        drawCircle(
            brush = Brush.radialGradient(listOf(colorCore.copy(alpha = 0.35f), Color.Transparent)),
            radius = radius * 1.6f,
            center = center
        )
        drawCircle(color = colorCore, radius = radius, center = center)
        drawCircle(color = Color.White.copy(alpha = 0.25f), radius = radius * 0.35f, center = center)
    }
}
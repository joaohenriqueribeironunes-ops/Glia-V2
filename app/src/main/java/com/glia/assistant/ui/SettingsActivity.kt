package com.glia.assistant.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.glia.assistant.service.GliaVoiceService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsScreen() }
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = ctx.getSharedPreferences("glia", android.content.Context.MODE_PRIVATE)
    var modelPath by remember { mutableStateOf(prefs.getString("model_path", "") ?: "") }
    var temperature by remember { mutableFloatStateOf(prefs.getFloat("temperature", 0.7f)) }
    var contextSize by remember { mutableIntStateOf(prefs.getInt("context_size", 2048)) }
    var threads by remember { mutableIntStateOf(prefs.getInt("threads", 4)) }
    var status by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            scope.launch {
                status = "Copiando modelo..."
                try {
                    val out = withContext(Dispatchers.IO) {
                        val part = File(ctx.filesDir, "user-model.gguf.part")
                        val target = File(ctx.filesDir, "user-model.gguf")
                        ctx.contentResolver.openInputStream(it)?.use { input ->
                            part.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IllegalStateException("Nao foi possivel abrir o arquivo")
                        val header = ByteArray(4)
                        part.inputStream().use { input -> input.read(header) }
                        if (String(header, Charsets.US_ASCII) != "GGUF") {
                            part.delete()
                            throw IllegalStateException("O arquivo selecionado nao e um GGUF valido")
                        }
                        if (target.exists()) target.delete()
                        if (!part.renameTo(target)) {
                            part.copyTo(target, overwrite = true)
                            part.delete()
                        }
                        target
                    }
                    modelPath = out.absolutePath
                    prefs.edit().putString("model_path", out.absolutePath).apply()
                    status = "Modelo importado: ${out.length() / (1024 * 1024)} MB"
                    ContextCompat.startForegroundService(
                        ctx,
                        Intent(ctx, GliaVoiceService::class.java)
                            .setAction(GliaVoiceService.ACTION_RELOAD_MODEL)
                    )
                } catch (e: Exception) {
                    status = "Erro ao importar: ${e.message}"
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Configuracoes - Glia", style = MaterialTheme.typography.headlineSmall)
        Text(
            "O modelo Ternary Bonsai 2 2B (Q2_0) ja vem embutido no APK. " +
                "Importe outro .gguf apenas se quiser substituir o modelo embutido.",
            style = MaterialTheme.typography.bodySmall
        )

        Text("Modelo .gguf", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = modelPath,
            onValueChange = { modelPath = it },
            label = { Text("Caminho do modelo (vazio = embutido)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { picker.launch(arrayOf("*/*")) }) {
            Text("Importar modelo .gguf")
        }
        Button(onClick = {
            prefs.edit().putString("model_path", modelPath.trim()).apply()
            status = "Caminho salvo. Recarregando modelo..."
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, GliaVoiceService::class.java)
                    .setAction(GliaVoiceService.ACTION_RELOAD_MODEL)
            )
        }) {
            Text("Salvar caminho e recarregar")
        }

        Text("Temperatura: ${"%.2f".format(temperature)}")
        Slider(
            value = temperature,
            onValueChange = {
                temperature = it
                prefs.edit().putFloat("temperature", it).apply()
            },
            valueRange = 0f..1.5f
        )

        Text("Tamanho do contexto: $contextSize")
        Slider(
            value = contextSize.toFloat(),
            onValueChange = {
                contextSize = it.toInt()
                prefs.edit().putInt("context_size", contextSize).apply()
            },
            valueRange = 512f..8192f
        )

        Text("Threads de geracao: $threads")
        Slider(
            value = threads.toFloat(),
            onValueChange = {
                threads = it.toInt().coerceIn(1, 8)
                prefs.edit().putInt("threads", threads).apply()
            },
            valueRange = 1f..8f
        )

        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        Text("Permissoes criticas", style = MaterialTheme.typography.titleMedium)
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")
                    )
                )
            }
        }) {
            Text("Sobreposicao de tela")
        }
        Button(onClick = {
            val i = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${ctx.packageName}")
            )
            ctx.startActivity(i)
        }) {
            Text("Ignorar otimizacao de bateria (MIUI)")
        }
        Button(onClick = {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${ctx.packageName}"))
            ctx.startActivity(i)
        }) {
            Text("Abrir permissoes do app")
        }
    }
}

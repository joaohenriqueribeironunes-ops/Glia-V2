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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.glia.assistant.R

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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            // Copia o arquivo selecionado para o armazenamento interno do app
            scope.launch {
                try {
                    val outFile = java.io.File(ctx.filesDir, "model.gguf")
                    ctx.contentResolver.openInputStream(it)?.use { input ->
                        outFile.outputStream().use { out -> input.copyTo(out) }
                    }
                    modelPath = outFile.absolutePath
                    prefs.edit().putString("model_path", modelPath).apply()
                } catch (e: Exception) {
                    modelPath = it.toString()
                    prefs.edit().putString("model_path", modelPath).apply()
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Configuracoes - Glia", style = MaterialTheme.typography.headlineSmall)
        Text("Modelo .gguf", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = modelPath, onValueChange = { modelPath = it }, label = { Text("Caminho/URI do modelo") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Importar modelo .gguf") }

        Text("Temperatura: ${"%.2f".format(temperature)}")
        Slider(value = temperature, onValueChange = { temperature = it; prefs.edit().putFloat("temperature", it).apply() }, valueRange = 0f..1.5f)

        Text("Tamanho do contexto: $contextSize")
        Slider(value = contextSize.toFloat(), onValueChange = { contextSize = it.toInt(); prefs.edit().putInt("context_size", contextSize).apply() }, valueRange = 512f..8192f)

        Spacer(Modifier.height(8.dp))
        Text("Permissoes criticas", style = MaterialTheme.typography.titleMedium)
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
        }) { Text("Sobreposicao de tela") }
        Button(onClick = {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            ctx.startActivity(i)
        }) { Text("Ignorar otimizacao de bateria (MIUI)") }
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // POST_NOTIFICATIONS handled at runtime in activity
            }
        }) { Text("Notificacoes") }
    }
}
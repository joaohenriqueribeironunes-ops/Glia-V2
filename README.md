# Glia - Assistente Pessoal de Voz 100% Offline (Android)

Aplicativo nativo Android (Kotlin + C++/JNI) para conversas de voz fluidas e em tempo real,
projetado para hardware MediaTek Helio G96 / 6GB RAM, 100% offline.

## Stack

- Kotlin (Android Nativo) + C++ (NDK/JNI) para processamento de audio e inferencia.
- LLM offline via `llama.cpp` (integracao nativa real, modelo recomendado: **Ternary Bonsai 2 2B** da PrismML).
- STT offline via Vosk (vosk-android 0.3.75).
- TTS via `android.speech.tts.TextToSpeech` (PT-BR), streaming por sentenca.
- Wake word "Ei Glia" implementado em loop de buffer circular (Vosk parcial).
- ForegroundService resiliente + TileService (Quick Settings) + overlay transparente (MIUI).

## Estrutura

```
Glia/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties          # android.overridePathCheck=true (path com acento)
  local.properties           # sdk.dir / ndk.dir
  app/
    build.gradle.kts
    src/main/
      AndroidManifest.xml
      java/com/glia/assistant/...
      res/...
      cpp/ (CMakeLists.txt, llama_jni.cpp, audio_processor.cpp)
```

## Pre-requisitos

- JDK 17
- Android SDK (platforms android-35, build-tools)
- NDK 28.2.13676358
- CMake 3.22+

## Compilar o APK

```bash
cd Glia
# Windows (PowerShell)
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME="C:\Users\<user>\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat assembleDebug

# Linux/macOS
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

APK gerado em `app/build/outputs/apk/debug/app-debug.apk`.

## Configurar o modelo LLM (Ternary Bonsai 2 2B)

Modelo recomendado: `prism-ml/Ternary-Bonsai-2-2B-gguf` (base Qwen3.5-2B, ternario, ~0.55 GB).

> **Importante:** a build upstream do llama.cpp (usada aqui) suporta apenas o arquivo **`Ternary-Bonsai-2-2B-Q2_0.gguf`** (release b11556+). Os arquivos PTQ1_0/PQ2_0 exigem o fork PrismML.

1. Baixe (via Hugging Face CLI ou curl):
   ```bash
   hf download prism-ml/Ternary-Bonsai-2-2B-gguf Ternary-Bonsai-2-2B-Q2_0.gguf --local-dir .
   ```
   ou: `curl -L -o Ternary-Bonsai-2-2B-Q2_0.gguf https://huggingface.co/prism-ml/Ternary-Bonsai-2-2B-gguf/resolve/main/Ternary-Bonsai-2-2B-Q2_0.gguf`
2. Copie para o armazenamento interno do device (ex: `Download/`).
3. Abra o app -> Configuracoes -> Importar modelo .gguf e selecione o arquivo.
4. Parametros recomendados (ja aplicados por padrao): temperatura 0.7, top_p 0.8, top_k 20, presence_penalty 1.5, thinking desligado.
5. (Opcional) Ajuste tamanho de contexto (default 2048; modelo suporta ate 262144).

## Wake word / STT (Vosk)

1. Baixe `vosk-model-small-pt-0.3` de https://alphacephei.com/vosk/models
2. Extraia para `/sdcard/Download/vosk-model-small-pt` (ou `filesDir/vosk-model-small-pt`).
3. O servico detecta automaticamente.

## Integracao llama.cpp (status)

- `llama.cpp` clonado em `app/src/main/cpp/llama.cpp` (upstream ggerganov).
- `CMakeLists.txt` usa `add_subdirectory(llama.cpp)` e linka `llama`/`ggml`.
- `llama_jni.cpp` implementa carga real (`llama_model_load_from_file`), contexto, sampling
  (top_k, temp, top_p, min_p, presence penalty) e streaming de tokens via callback.
- `LlamaBridge.kt` expoe a interface e encaminha para o JNI.

## Permissoes MIUI / HyperOS (Redmi Note 11S)

- Microfone, Sobreposicao de tela, Ignorar otimizacao de bateria.
- Tile "Glia" na Central de Controle -> toque para escuta imediata (bypass wake word).

## Observacoes de performance (Helio G96 / 6GB)

- `mlock` desativado (default) para evitar OOM.
- Threads configuraveis (default 4, foco nos Cortex-A76).
- Contexto default 2048 tokens.
- Audio 16kHz mono, buffer circular, VAD por amplitude.

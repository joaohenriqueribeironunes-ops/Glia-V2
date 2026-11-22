# Diagnostico e correcoes - Glia v1.0.3

## Sintoma

A Glia ouvia o usuario, mas nao respondia. O modelo de IA estava embutido no APK,
porem a resposta nao chegava ao alto-falante e, em alguns casos, o servico ficava
travado sem voltar a escutar.

## Causas encontradas

1. **TTS travava a fila.** `GliaTts` marcava `speaking = true` antes de chamar
   `TextToSpeech.speak()` e ignorava o retorno. Se o engine ainda nao estivesse
   pronto ou falhasse, `isSpeaking()` ficava `true` para sempre e o servico
   esperava sem timeout.
2. **Loop de wake word nao reiniciava.** O `wakeJob` nao era cancelado ao acordar;
   depois da primeira conversa o recorder era parado, mas o job continuava ativo.
3. **Vosk concorrente.** Cada buffer de audio criava uma coroutine nova; `feed` e
   `finalize` rodavam ao mesmo tempo e fora de ordem. O `Recognizer` do Vosk nao e
   thread-safe.
4. **Copia parcial do modelo aceita como valida.** `ModelStore` aceitava qualquer
   `model.gguf` nao vazio. Uma copia interrompida de 546 MB fazia o carregamento
   falhar e `modelLoading` podia ficar `true` para sempre.
5. **KV cache nao era limpo entre geracoes.** A segunda resposta em diante podia
   sair vazia ou com lixo.
6. **UTF-8 quebrado no JNI.** `NewStringUTF` recebia pedacos parciais de tokens,
   perdendo acentos e tokens.
7. **Penalidades do sampler invertidas.** O codigo passava repeat=1.0 (desligado)
   e presence=1.5 (forte), em vez de repeat=1.1.
8. **Sem wake lock** durante carregamento/geracao longa; a CPU podia dormir.
9. **Corrida no AudioRecorder** entre start/stop e a thread de leitura.
10. **SettingsActivity copiava 546 MB na thread principal**, risco de ANR.
11. **Permissoes ausentes no manifesto:** `VIBRATE` e `RECEIVE_BOOT_COMPLETED`.
12. **Sem diagnostico visivel** para o usuario saber se modelo, voz e microfone
    estavam prontos.

## Correcoes aplicadas

- `tts/GliaTts.kt`: fila preservada ate o init, verificacao do retorno de
  `speak`, limite de falhas, fallback pt-BR -> locale do sistema -> en-US,
  `AudioAttributes.USAGE_ASSISTANT`, status e `isReady()`.
- `service/GliaVoiceService.kt`: reescrito com `SupervisorJob`, fila de audio
  serializada (`Channel` + ack de flush), geracoes para descartar audio antigo,
  cancelamento correto do wake job, captura com timeouts (12 s na primeira,
  7 s nas seguintes, 1,2 s de silencio), espera de modelo com try/finally,
  streaming de frases para o TTS, limite de 8 s para o TTS inicializar,
  wake locks, notificacoes e acoes de teste/recarga.
- `llm/ModelStore.kt`: copia atomica para `embedded-model.gguf.part`, tamanho
  exato 546.689.504 bytes, validacao do magic `GGUF`, rejeicao de copia parcial.
- `llm/LlamaBridge.kt` + `cpp/llama_jni.cpp`: `llama_backend_init`, mutex,
  limpeza do KV cache, tokenizacao com tokens especiais, conversao UTF-8 ->
  UTF-16, callback de erro, `nativeGetLastError`, sampler na ordem correta
  (penalties -> top_k -> top_p -> min_p -> temp -> dist) e
  `load_mode = LLAMA_LOAD_MODE_MMAP`.
- `audio/AudioRecorder.kt`: callbacks volateis, referencia local do recorder,
  join da thread, checagem de estado e fallback `VOICE_RECOGNITION` -> `MIC`.
- `stt/VoskStt.kt`: metodos sincronizados, release antes de reinicializar,
  validacao do diretorio e recriacao do recognizer apos `finalize`.
- `ui/MainActivity.kt`: cartao de diagnostico (modelo/voz/microfone/erro/tempos)
  e botoes Iniciar escuta, Testar voz, Testar modelo, Recarregar modelo, Parar.
- `ui/SettingsActivity.kt`: importacao em `Dispatchers.IO`, `user-model.gguf`,
  validacao GGUF, slider de threads e recarga automatica.
- `AndroidManifest.xml`: `VIBRATE`, `RECEIVE_BOOT_COMPLETED`,
  `stopWithTask=false`.
- Versao: `versionCode 2`, `versionName 1.0.3`.

## Testes executados

- `:app:compileDebugKotlin --offline`: sucesso.
- `:app:assembleDebug --offline`: sucesso; `llama_jni.cpp` compilado para
  arm64-v8a, apenas avisos de APIs depreciadas do llama.cpp.
- Metadados do GGUF conferidos: arquitetura `qwen35`, template de chat Qwen3.5
  com `enable_thinking=false`, BOS/EOS e Hadamard prismatico.
- Prompt do app comparado com o template do modelo: identico.
- Empacotamento: APK gerado em `app/build/outputs/apk/debug/app-debug.apk`.

Nao havia dispositivo/emulador conectado (`adb devices` vazio), portanto o teste
de audio real (microfone -> Vosk -> llama.cpp -> TTS) precisa ser feito no
aparelho. O teste desktop do llama.cpp ficou bloqueado por uma falha de build do
`cpp-httplib` com MinGW; o build Android nativo passou.

## Como testar no aparelho

1. Instale o APK e conceda microfone e notificacoes.
2. Abra o app e confira o cartao de diagnostico: modelo deve ficar `pronto`,
   voz `pronto` e microfone `pronto`.
3. Toque em **Testar voz** e depois em **Testar modelo**.
4. Toque em **Iniciar escuta**, diga "Ei Glia" e faca uma pergunta.
5. Para investigar: `adb logcat -s GliaVoice GliaNative GliaTts VoskStt ModelStore AudioRecorder`.

## Limitacoes conhecidas

- O modelo Vosk precisa estar disponivel em `filesDir/vosk-model-small-pt-0.3`
  (ou em `Download/vosk-model-small-pt-0.3`); o APK nao embute o modelo Vosk.
- A primeira resposta pode demorar enquanto o modelo de 546 MB e copiado e
  carregado; o status na tela mostra o progresso.
- Sem aparelho conectado, a validacao final de audio depende do teste manual.

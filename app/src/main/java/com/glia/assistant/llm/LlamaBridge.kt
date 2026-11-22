package com.glia.assistant.llm

/**
 * Ponte JNI para o motor LLM nativo (llama.cpp).
 * Em producao, compile llama.cpp e linke em CMakeLists.txt.
 */
class LlamaBridge {
    companion object {
        init { System.loadLibrary("glia_native") }
    }

    external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Int
    external fun nativeUnloadModel()
    external fun nativeIsLoaded(): Boolean
    external fun nativeGenerate(prompt: String, callback: TokenCallback, temperature: Float)
    external fun nativeStopGeneration()
    external fun nativeIsGenerating(): Boolean

    fun loadModel(path: String, contextSize: Int = 2048, threads: Int = 4): Boolean {
        return nativeLoadModel(path, contextSize, threads) == 0
    }

    fun unload() = nativeUnloadModel()
    fun isLoaded() = nativeIsLoaded()
    fun generate(prompt: String, callback: TokenCallback, temperature: Float = 0.7f) {
        nativeGenerate(prompt, callback, temperature)
    }
    fun stop() = nativeStopGeneration()
    fun isGenerating() = nativeIsGenerating()
}
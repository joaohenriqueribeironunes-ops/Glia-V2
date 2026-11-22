package com.glia.assistant.stt

interface SttEngine {
    fun init(modelPath: String): Boolean
    fun feed(buffer: ShortArray, length: Int): String? // partial
    fun finalize(): String
    fun release()
}
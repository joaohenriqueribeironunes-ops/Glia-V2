package com.glia.assistant.audio

class AudioProcessor {
    companion object {
        init { System.loadLibrary("glia_native") }
    }
    external fun nativeRms(pcm: ShortArray, length: Int): Float
    fun rms(pcm: ShortArray, length: Int = pcm.size): Float = nativeRms(pcm, length)
}
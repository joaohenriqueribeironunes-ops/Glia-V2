package com.glia.assistant.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class GliaMode { IDLE, LISTENING, THINKING, SPEAKING }

object GliaState {
    private val _mode = MutableStateFlow(GliaMode.IDLE)
    val mode: StateFlow<GliaMode> = _mode
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude
    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText
    private val _responseText = MutableStateFlow("")
    val responseText: StateFlow<String> = _responseText

    fun setMode(m: GliaMode) { _mode.value = m }
    fun setAmplitude(a: Float) { _amplitude.value = a }
    fun setPartial(t: String) { _partialText.value = t }
    fun setResponse(t: String) { _responseText.value = t }
    fun appendResponse(t: String) { _responseText.value += t }
}
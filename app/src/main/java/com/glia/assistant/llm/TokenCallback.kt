package com.glia.assistant.llm

interface TokenCallback {
    fun onToken(token: String)
    fun onDone()
    fun onError(message: String) {}
}
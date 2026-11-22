package com.glia.assistant.ui

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

class QuickOverlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        // Apenas garante que o foreground service tenha acesso ao mic (MIUI).
        // Fecha imediatamente.
        finish()
    }
}
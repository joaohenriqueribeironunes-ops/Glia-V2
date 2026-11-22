package com.glia.assistant.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object Permissions {
    fun has(context: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}
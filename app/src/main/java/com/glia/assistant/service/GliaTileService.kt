package com.glia.assistant.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.glia.assistant.ui.QuickOverlayActivity

class GliaTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(isActive())
    }

    override fun onClick() {
        super.onClick()
        if (isActive()) {
            // Desativa
            stopService(Intent(this, GliaVoiceService::class.java))
            setActive(false)
            updateTileState(false)
        } else {
            // Ativa escuta imediata (bypass wake word)
            setActive(true)
            updateTileState(true)
            // Inicia foreground service
            val svc = Intent(this, GliaVoiceService::class.java).apply {
                action = GliaVoiceService.ACTION_START_LISTENING
            }
            startForegroundService(svc)
            // Overlay transparente para garantir acesso ao mic (MIUI)
            val overlay = Intent(this, QuickOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            startActivityAndCollapse(overlay)
        }
    }

    private fun isActive(): Boolean {
        return getSharedPreferences("glia", MODE_PRIVATE).getBoolean("tile_active", false)
    }

    private fun setActive(v: Boolean) {
        getSharedPreferences("glia", MODE_PRIVATE).edit().putBoolean("tile_active", v).apply()
    }

    private fun updateTileState(active: Boolean) {
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Glia"
            updateTile()
        }
    }
}
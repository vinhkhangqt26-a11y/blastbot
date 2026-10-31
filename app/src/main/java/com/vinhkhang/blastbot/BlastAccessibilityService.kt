package com.vinhkhang.blastbot

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.Toast

class BlastAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var floatingButton: Button? = null
    private var botEnabled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingButton()
    }

    private fun showFloatingButton() {
        if (floatingButton != null) return
        val button = Button(this).apply {
            textSize = 12f
            isAllCaps = false
            setPadding(14, 8, 14, 8)
            setOnClickListener { setBotEnabled(!botEnabled) }
        }
        floatingButton = button
        updateButton()

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 18
            y = 220
        }
        try {
            windowManager?.addView(button, params)
        } catch (_: Exception) {
            floatingButton = null
        }
    }

    private fun setBotEnabled(enabled: Boolean) {
        botEnabled = enabled
        updateButton()
        val message = if (enabled) {
            "BlastBot đã bật. Chờ bộ nhận diện bàn cờ."
        } else {
            "BlastBot đã tắt."
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateButton() {
        floatingButton?.apply {
            text = if (botEnabled) "BOT: BẬT" else "BOT: TẮT"
            alpha = if (botEnabled) 1.0f else 0.82f
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screen observation is reserved for the board-recognition pipeline.
        // No drag is issued until a move can be verified against a fresh board.
    }

    override fun onInterrupt() {
        setBotEnabled(false)
    }

    override fun onDestroy() {
        botEnabled = false
        floatingButton?.let { view ->
            try { windowManager?.removeView(view) } catch (_: Exception) { }
        }
        floatingButton = null
        super.onDestroy()
    }
}

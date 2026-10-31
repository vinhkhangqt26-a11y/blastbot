package com.vinhkhang.blastbot

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class BlastAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Observe screen changes only. Game actions are intentionally disabled
        // until board recognition and post-drop confirmation are implemented.
    }

    override fun onInterrupt() = Unit
}

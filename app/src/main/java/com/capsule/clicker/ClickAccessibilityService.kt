package com.capsule.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Служба спец.возможностей. Единственная задача — по запросу выполнить тап
 * по экранным координатам через dispatchGesture (работает без root).
 */
class ClickAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ClickerState.status("Служба тапов подключена")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* не используется */ }

    override fun onInterrupt() { /* не используется */ }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Короткий тап в точке (x, y) экрана. */
    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 30)  // 30 мс удержание
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        try {
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            ClickerState.status("Ошибка тапа: ${e.message}")
        }
    }

    companion object {
        @Volatile
        var instance: ClickAccessibilityService? = null
    }
}

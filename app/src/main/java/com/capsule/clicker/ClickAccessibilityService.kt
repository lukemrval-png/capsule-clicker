package com.capsule.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Служба спец.возможностей. Единственная задача — по запросу выполнить тап
 * по экранным координатам через dispatchGesture (работает без root).
 */
class ClickAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Android 14+: попросить систему присылать нам события касаний (onMotionEvent),
        // чтобы кликер замолкал, пока пользователь трогает экран. Флаг из кода —
        // соответствующий XML-атрибут есть только с Android 15.
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val info = serviceInfo
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS
                serviceInfo = info
            } catch (_: Throwable) { /* не критично — есть запасной механизм */ }
        }
        ClickerState.status("Служба тапов подключена")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* не используется */ }

    override fun onInterrupt() { /* не используется */ }

    /**
     * Приходит на Android 14+ (flagSendMotionEvents): пока ПОЛЬЗОВАТЕЛЬ трогает
     * экран, помечаем время — тогда кликер замолкает и не мешает прокрутке.
     * Свои же тапы (dispatchGesture) отсекаем по времени, чтобы не пометить их.
     */
    override fun onMotionEvent(event: MotionEvent) {
        val now = System.currentTimeMillis()
        if (now - ClickerState.lastSelfTapAt > 250) {
            ClickerState.lastUserTouchAt = now
        }
    }

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

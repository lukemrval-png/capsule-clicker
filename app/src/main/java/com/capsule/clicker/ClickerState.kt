package com.capsule.clicker

import android.graphics.Rect

/**
 * Общее состояние приложения: параметры, последний кадр экрана и шаблон.
 * Всё @Volatile — читается из фонового потока захвата и пишется из UI-потока.
 */
object ClickerState {

    // ── Параметры кликера ─────────────────────────────────────────────
    @Volatile var running = false          // включён ли автокликер (тапы)
    @Volatile var threshold = 0.65f        // порог сходства NCC 0..1 (ниже = чаще срабатывает)
    @Volatile var cooldownMs = 500L        // пауза после клика, мс
    @Volatile var searchScale = 0.25f      // даунскейл при поиске (меньше = быстрее, грубее)

    // ── Последний кадр экрана (ARGB), обновляет ScreenCaptureService ──
    @Volatile var frame: IntArray? = null
    @Volatile var frameW = 0
    @Volatile var frameH = 0

    // ── Шаблон цели в grayscale (0..255), размеры в пикселях экрана ──
    @Volatile var template: IntArray? = null
    @Volatile var templateW = 0
    @Volatile var templateH = 0

    // Зона поиска в координатах экрана (null = весь экран)
    @Volatile var region: Rect? = null

    // ── Диагностика ──
    @Volatile var lastScore = 0f
    @Volatile var lastClickAt = 0L
    @Volatile var clicks = 0

    // Куда слать текст статуса (панель-оверлей подписывается сюда)
    @Volatile var statusListener: ((String) -> Unit)? = null

    fun status(s: String) {
        statusListener?.invoke(s)
    }

    fun hasTemplate(): Boolean = template != null && templateW > 0 && templateH > 0
}

package com.capsule.clicker

import android.graphics.Rect
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Общее состояние приложения: параметры, последний кадр экрана и шаблон.
 * Всё @Volatile — читается из фонового потока захвата и пишется из UI-потока.
 */
object ClickerState {

    // ── Параметры кликера ─────────────────────────────────────────────
    @Volatile var running = false          // включён ли автокликер (тапы)
    @Volatile var adPaused = false         // «реклама»: стоп, ничего не нажимаем (как F8)
    @Volatile var threshold = 0.65f        // порог сходства NCC 0..1 (ниже = чаще срабатывает)
    @Volatile var colorConf = 0.55f        // порог подтверждения ЦВЕТОМ 0..1 (выше = строже)
    @Volatile var useColorCheck = true     // проверять цвет цели (меньше ложных кликов)
    @Volatile var cooldownMs = 500L        // пауза после клика, мс
    @Volatile var searchScale = 0.25f      // даунскейл при поиске (меньше = быстрее, грубее)

    // ── Последний кадр экрана (ARGB), обновляет ScreenCaptureService ──
    @Volatile var frame: IntArray? = null
    @Volatile var frameW = 0
    @Volatile var frameH = 0

    // ── Несколько шаблонов целей (как разные капсулы в ПК-версии) ──
    // gray — яркость (0..255) для быстрого поиска; color — ARGB для проверки цвета.
    // Кликер ловит ЛЮБОЙ из шаблонов.
    class Template(val gray: IntArray, val color: IntArray, val w: Int, val h: Int)
    val templates = CopyOnWriteArrayList<Template>()

    // Зона поиска в координатах экрана (null = весь экран).
    // Расширяется по мере добавления целей.
    @Volatile var region: Rect? = null

    // ── Не мешать пользователю ──
    @Volatile var lastUserTouchAt = 0L     // когда пользователь последний раз трогал экран
    @Volatile var userIdleMs = 900L        // столько мс после касания НЕ кликаем (даём прокрутить)
    @Volatile var lastSelfTapAt = 0L       // когда МЫ сами тапнули (чтобы не считать это касанием юзера)

    // ── Диагностика ──
    @Volatile var lastScore = 0f
    @Volatile var lastClickAt = 0L
    @Volatile var clicks = 0

    // Куда слать текст статуса (панель-оверлей подписывается сюда)
    @Volatile var statusListener: ((String) -> Unit)? = null

    fun status(s: String) {
        statusListener?.invoke(s)
    }

    fun hasTemplate(): Boolean = templates.isNotEmpty()
}

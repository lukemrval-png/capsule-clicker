package com.capsule.clicker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Плавающая панель управления поверх игры + режим выбора цели одним тапом.
 * Живёт независимо от MainActivity, чтобы всё можно было делать внутри игры.
 */
object OverlayController {

    private var wm: WindowManager? = null
    private var panel: View? = null
    private var picker: View? = null
    private var watcher: View? = null
    private var statusView: TextView? = null
    private var startBtn: Button? = null
    private var thrView: TextView? = null
    private val ui = Handler(Looper.getMainLooper())

    val isShown: Boolean get() = panel != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (panel != null) return
        val ctx = context.applicationContext
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val pad = dp(ctx, 10)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(220, 20, 20, 20))
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(ctx).apply {
            text = "Capsule Clicker  ⠿"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        root.addView(title)

        statusView = TextView(ctx).apply {
            text = "готов"
            setTextColor(Color.parseColor("#9AE6B4"))
            textSize = 11f
        }
        root.addView(statusView)

        // Ряд кнопок: Цель / Старт
        // Ряд целей: + Цель / Убрать / Сброс
        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val targetBtn = Button(ctx).apply {
            text = "+ Цель"
            setOnClickListener { startTargetPick(ctx) }
        }
        val undoBtn = Button(ctx).apply {
            text = "Убрать"
            setOnClickListener {
                val list = ClickerState.templates
                if (list.isNotEmpty()) {
                    list.removeAt(list.size - 1)
                    statusView?.text = "Убрана последняя. Целей: ${list.size}"
                } else {
                    statusView?.text = "Целей нет"
                }
            }
        }
        val resetBtn = Button(ctx).apply {
            text = "Сброс"
            setOnClickListener {
                ClickerState.templates.clear()
                ClickerState.region = null
                ClickerState.running = false
                startBtn?.text = "Старт"
                statusView?.text = "Цели очищены"
            }
        }
        row1.addView(targetBtn)
        row1.addView(undoBtn)
        row1.addView(resetBtn)
        root.addView(row1)

        // Ряд работы: Старт / Реклама
        val rowRun = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = Button(ctx).apply {
            text = "Старт"
            setOnClickListener { toggleRun() }
        }
        val adBtn = Button(ctx)
        adBtn.text = "Реклама"
        adBtn.setOnClickListener {
            ClickerState.adPaused = !ClickerState.adPaused
            adBtn.text = if (ClickerState.adPaused) "▶ Дальше" else "Реклама"
            statusView?.text =
                if (ClickerState.adPaused) "📺 пауза на рекламе" else "продолжаю"
        }
        rowRun.addView(startBtn)
        rowRun.addView(adBtn)
        root.addView(rowRun)

        // Ряд порога: −  0.65  +
        val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minus = Button(ctx).apply {
            text = "−"
            setOnClickListener { changeThreshold(-0.05f) }
        }
        thrView = TextView(ctx).apply {
            text = "порог ${fmt(ClickerState.threshold)}"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
        }
        val plus = Button(ctx).apply {
            text = "+"
            setOnClickListener { changeThreshold(0.05f) }
        }
        row2.addView(minus)
        row2.addView(thrView)
        row2.addView(plus)
        root.addView(row2)

        val closeBtn = Button(ctx).apply {
            text = "Закрыть"
            setOnClickListener {
                ClickerState.running = false
                ScreenCaptureService.stop(ctx)
                hide()
            }
        }
        root.addView(closeBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_TOUCH_MODAL — касания ВНЕ панели уходят игре (иначе экран не прокрутить).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(ctx, 8)
            y = dp(ctx, 120)
        }

        // Перетаскивание панели за заголовок.
        title.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = e.rawX
                        touchY = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (e.rawX - touchX).toInt()
                        params.y = startY + (e.rawY - touchY).toInt()
                        wm?.updateViewLayout(root, params)
                    }
                }
                return true
            }
        })

        wm?.addView(root, params)
        panel = root

        // Статус из фонового сервиса выводим в панель.
        ClickerState.statusListener = { s ->
            ui.post {
                statusView?.text = s
            }
        }

        addTouchWatcher(ctx)
    }

    /**
     * Крошечное окно 1x1, которое ловит касания ВНЕ себя (FLAG_WATCH_OUTSIDE_TOUCH).
     * Как только пользователь тронул экран — запоминаем время, и кликер на userIdleMs
     * замолкает (можно спокойно прокручивать/играть). Свои тапы отсекаем по времени.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun addTouchWatcher(ctx: Context) {
        if (watcher != null) return
        val v = View(ctx)
        val wp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                val now = System.currentTimeMillis()
                // Игнорируем событие от НАШЕГО же тапа (в пределах 250 мс).
                if (now - ClickerState.lastSelfTapAt > 250) {
                    ClickerState.lastUserTouchAt = now
                }
            }
            false
        }
        try {
            wm?.addView(v, wp)
            watcher = v
        } catch (_: Exception) {}
    }

    fun hide() {
        ClickerState.statusListener = null
        removePicker()
        watcher?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        watcher = null
        panel?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        panel = null
        statusView = null
        startBtn = null
        thrView = null
    }

    private fun toggleRun() {
        if (!ClickerState.running) {
            if (!ClickerState.hasTemplate()) {
                statusView?.text = "Сначала нажми «Цель»"
                return
            }
            if (ClickAccessibilityService.instance == null) {
                statusView?.text = "Служба тапов выключена (шаг 1)"
                return
            }
            ClickerState.running = true
            startBtn?.text = "Стоп"
        } else {
            ClickerState.running = false
            startBtn?.text = "Старт"
        }
    }

    private fun changeThreshold(delta: Float) {
        ClickerState.threshold = (ClickerState.threshold + delta).coerceIn(0.30f, 0.98f)
        thrView?.text = "порог ${fmt(ClickerState.threshold)}"
    }

    // ── Выбор цели: полноэкранный слой, один тап по капсуле ──
    @SuppressLint("ClickableViewAccessibility")
    private fun startTargetPick(ctx: Context) {
        if (ClickerState.frame == null) {
            statusView?.text = "Нет кадра — запусти захват (шаг 3)"
            return
        }
        if (picker != null) return

        val overlay = TextView(ctx).apply {
            text = "Тапни по КАПСУЛЕ (добавить цель ${ClickerState.templates.size + 1})"
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(ctx, 60), 0, 0)
            setTextColor(Color.WHITE)
            textSize = 18f
            setBackgroundColor(Color.argb(90, 0, 0, 0))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        overlay.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                captureTemplateAt(e.rawX.toInt(), e.rawY.toInt())
                removePicker()
                true
            } else false
        }

        wm?.addView(overlay, params)
        picker = overlay
    }

    private fun removePicker() {
        picker?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        picker = null
    }

    private fun captureTemplateAt(cx: Int, cy: Int) {
        val frame = ClickerState.frame ?: return
        val fw = ClickerState.frameW
        val fh = ClickerState.frameH
        val half = 70              // шаблон ~140x140 px
        val left = (cx - half).coerceIn(0, fw - 2)
        val top = (cy - half).coerceIn(0, fh - 2)
        val right = (cx + half).coerceIn(left + 1, fw)
        val bottom = (cy + half).coerceIn(top + 1, fh)
        val rect = Rect(left, top, right, bottom)

        // Добавляем новую цель к списку (несколько разных капсул).
        val gray = Matcher.cropGray(frame, fw, fh, rect)
        val color = Matcher.cropArgb(frame, fw, fh, rect)
        ClickerState.templates.add(
            ClickerState.Template(gray, color, rect.width(), rect.height())
        )

        // Зона поиска — рамка вокруг цели; при нескольких целях расширяем объединением.
        val m = 400
        val box = Rect(
            (cx - m).coerceIn(0, fw),
            (cy - m).coerceIn(0, fh),
            (cx + m).coerceIn(0, fw),
            (cy + m).coerceIn(0, fh)
        )
        val cur = ClickerState.region
        ClickerState.region = if (cur == null) box else Rect(
            minOf(cur.left, box.left),
            minOf(cur.top, box.top),
            maxOf(cur.right, box.right),
            maxOf(cur.bottom, box.bottom)
        )

        val count = ClickerState.templates.size
        ui.post {
            statusView?.text = "Целей: $count — «+ Цель» добавить ещё или «Старт»"
        }
    }

    private fun fmt(v: Float): String = String.format("%.2f", v)

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}

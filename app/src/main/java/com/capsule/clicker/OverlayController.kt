package com.capsule.clicker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
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
        // Переключатель режима: Шаблон / Цвет
        val modeBtn = Button(ctx)
        modeBtn.text = modeText()
        modeBtn.setOnClickListener {
            ClickerState.mode =
                if (ClickerState.mode == ClickerState.Mode.TEMPLATE)
                    ClickerState.Mode.COLOR else ClickerState.Mode.TEMPLATE
            ClickerState.running = false
            startBtn?.text = "Старт"
            ClickerState.cooldownMs = if (ClickerState.mode == ClickerState.Mode.COLOR) 150L else 500L
            modeBtn.text = modeText()
            updateParamLabel()
            statusView?.text =
                if (ClickerState.mode == ClickerState.Mode.COLOR)
                    "Режим ЦВЕТ: обведи летящий объект, потом Старт"
                else "Режим ШАБЛОН: обведи капсулу(ы), потом Старт"
        }
        root.addView(modeBtn)

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
            setOnClickListener { changeParam(-1) }
        }
        thrView = TextView(ctx).apply {
            text = "порог ${fmt(ClickerState.threshold)}"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
        }
        val plus = Button(ctx).apply {
            text = "+"
            setOnClickListener { changeParam(1) }
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
            val ready = if (ClickerState.mode == ClickerState.Mode.COLOR)
                ClickerState.hasColorTarget else ClickerState.hasTemplate()
            if (!ready) {
                statusView?.text = "Сначала обведи цель кнопкой «+ Цель»"
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

    private fun modeText(): String =
        if (ClickerState.mode == ClickerState.Mode.COLOR) "Режим: ЦВЕТ" else "Режим: ШАБЛОН"

    private fun updateParamLabel() {
        thrView?.text = if (ClickerState.mode == ClickerState.Mode.COLOR)
            "допуск ${ClickerState.colorTol}" else "порог ${fmt(ClickerState.threshold)}"
    }

    private fun changeParam(dir: Int) {
        if (ClickerState.mode == ClickerState.Mode.COLOR) {
            ClickerState.colorTol = (ClickerState.colorTol + dir * 10).coerceIn(10, 150)
        } else {
            ClickerState.threshold = (ClickerState.threshold + dir * 0.05f).coerceIn(0.30f, 0.98f)
        }
        updateParamLabel()
    }

    // ── Выбор цели: полноэкранный слой, ОБВОДИШЬ капсулу рамкой ──
    private fun startTargetPick(ctx: Context) {
        if (ClickerState.frame == null) {
            statusView?.text = "Нет кадра — запусти захват (шаг 3)"
            return
        }
        if (picker != null) return

        val sel = SelectionView(ctx) { rect ->
            removePicker()
            if (rect != null && rect.width() > 8 && rect.height() > 8) {
                if (ClickerState.mode == ClickerState.Mode.COLOR) {
                    captureColorFromRect(rect)
                } else {
                    captureTemplateFromRect(rect)
                }
            } else {
                statusView?.text = "Рамка слишком мала — попробуй ещё"
            }
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
        try {
            wm?.addView(sel, params)
            picker = sel
        } catch (_: Exception) {}
    }

    private fun removePicker() {
        picker?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        picker = null
    }

    private fun captureTemplateFromRect(rect: Rect) {
        val frame = ClickerState.frame ?: return
        val fw = ClickerState.frameW
        val fh = ClickerState.frameH
        val left = rect.left.coerceIn(0, fw - 2)
        val top = rect.top.coerceIn(0, fh - 2)
        val right = rect.right.coerceIn(left + 1, fw)
        val bottom = rect.bottom.coerceIn(top + 1, fh)
        val r = Rect(left, top, right, bottom)

        val gray = Matcher.cropGray(frame, fw, fh, r)
        val color = Matcher.cropArgb(frame, fw, fh, r)
        ClickerState.templates.add(
            ClickerState.Template(gray, color, r.width(), r.height())
        )

        // Зона поиска — рамка с запасом вокруг цели; при нескольких целях объединяем.
        val m = 250
        val box = Rect(
            (r.left - m).coerceIn(0, fw),
            (r.top - m).coerceIn(0, fh),
            (r.right + m).coerceIn(0, fw),
            (r.bottom + m).coerceIn(0, fh)
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
            statusView?.text = "Целей: $count (${r.width()}x${r.height()}) — «+ Цель» ещё или «Старт»"
        }
    }

    /** Режим ЦВЕТ: берём средний цвет обведённой области как цель, зона поиска — весь экран. */
    private fun captureColorFromRect(rect: Rect) {
        val frame = ClickerState.frame ?: return
        val fw = ClickerState.frameW
        val fh = ClickerState.frameH
        val left = rect.left.coerceIn(0, fw - 2)
        val top = rect.top.coerceIn(0, fh - 2)
        val right = rect.right.coerceIn(left + 1, fw)
        val bottom = rect.bottom.coerceIn(top + 1, fh)

        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0L
        var y = top
        while (y < bottom) {
            val row = y * fw
            var x = left
            while (x < right) {
                val p = frame[row + x]
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
                n++
                x++
            }
            y++
        }
        if (n == 0L) return
        ClickerState.colR = (sr / n).toInt()
        ClickerState.colG = (sg / n).toInt()
        ClickerState.colB = (sb / n).toInt()
        ClickerState.hasColorTarget = true
        ClickerState.region = null      // цвет ищем по всему экрану (объект летает далеко)

        ui.post {
            statusView?.text = "Цвет цели RGB(${ClickerState.colR},${ClickerState.colG}," +
                "${ClickerState.colB}) — жми «Старт»"
        }
    }

    /** Затухающий крестик в точке клика (вызывается из сервиса при каждом тапе). */
    fun showClickMarker(x: Int, y: Int) {
        val manager = wm ?: return
        ui.post {
            val ctx = panel?.context ?: return@post
            val size = dp(ctx, 48)
            val cross = CrossView(ctx)
            val mp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x - size / 2
                this.y = y - size / 2
            }
            try {
                manager.addView(cross, mp)
                cross.animate().alpha(0f).setDuration(600).withEndAction {
                    try { manager.removeView(cross) } catch (_: Exception) {}
                }.start()
            } catch (_: Exception) {}
        }
    }

    private fun fmt(v: Float): String = String.format("%.2f", v)

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** Полупрозрачный слой: тянешь палец — рисуется рамка; отпустил — вернёт её. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class SelectionView(
        ctx: Context,
        val onDone: (Rect?) -> Unit
    ) : View(ctx) {
        private var sx = 0f; private var sy = 0f     // старт (экранные)
        private var cxp = 0f; private var cyp = 0f   // текущая (экранные)
        private var lsx = 0f; private var lsy = 0f   // старт (локальные, для рисунка)
        private var lcx = 0f; private var lcy = 0f   // текущая (локальные)
        private var drawing = false

        private val dim = Paint().apply { color = Color.argb(80, 0, 0, 0) }
        private val line = Paint().apply {
            color = Color.parseColor("#FF3355")
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }
        private val hint = Paint().apply {
            color = Color.WHITE
            textSize = 44f
            isAntiAlias = true
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; cxp = sx; cyp = sy
                    lsx = e.x; lsy = e.y; lcx = e.x; lcy = e.y
                    drawing = true; invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    cxp = e.rawX; cyp = e.rawY; lcx = e.x; lcy = e.y; invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    drawing = false
                    val r = Rect(
                        minOf(sx, cxp).toInt(), minOf(sy, cyp).toInt(),
                        maxOf(sx, cxp).toInt(), maxOf(sy, cyp).toInt()
                    )
                    onDone(r)
                }
            }
            return true
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            canvas.drawText("Обведи цель рамкой", 40f, 90f, hint)
            if (drawing) {
                canvas.drawRect(
                    minOf(lsx, lcx), minOf(lsy, lcy),
                    maxOf(lsx, lcx), maxOf(lsy, lcy), line
                )
            }
        }
    }

    /** Крестик клика: круг + косой крест. */
    @SuppressLint("ViewConstructor")
    private class CrossView(ctx: Context) : View(ctx) {
        private val ring = Paint().apply {
            color = Color.parseColor("#FFD633"); style = Paint.Style.STROKE
            strokeWidth = 4f; isAntiAlias = true
        }
        private val cross = Paint().apply {
            color = Color.parseColor("#FF3355"); style = Paint.Style.STROKE
            strokeWidth = 6f; isAntiAlias = true
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val pad = w * 0.18f
            canvas.drawOval(4f, 4f, w - 4f, h - 4f, ring)
            canvas.drawLine(pad, pad, w - pad, h - pad, cross)
            canvas.drawLine(w - pad, pad, pad, h - pad, cross)
        }
    }
}

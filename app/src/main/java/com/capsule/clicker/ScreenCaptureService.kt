package com.capsule.clicker

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Foreground-сервис: держит MediaProjection, непрерывно читает кадры экрана
 * в ClickerState.frame и, когда включён режим running, ищет шаблон и тапает.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capThread: HandlerThread? = null
    private var loop: Thread? = null
    @Volatile private var stopFlag = false

    private var w = 0
    private var h = 0
    private var dpi = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code == Activity.RESULT_OK && data != null) {
            try {
                startCapture(code, data)
            } catch (e: Exception) {
                ClickerState.status("Ошибка захвата: ${e.message}")
                stopSelf()
            }
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        w = metrics.widthPixels
        h = metrics.heightPixels
        dpi = metrics.densityDpi

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        // На Android 14 обязателен зарегистрированный колбэк, иначе исключение.
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanup()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        capThread = HandlerThread("capture").apply { start() }
        val handler = Handler(capThread!!.looper)

        vdisplay = projection?.createVirtualDisplay(
            "capsule-capture",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )

        stopFlag = false
        loop = Thread { runLoop() }.apply { start() }
        ClickerState.status("Захват запущен ${w}x${h}")
    }

    private fun runLoop() {
        val regWH = IntArray(2)
        val tmpWH = IntArray(2)
        while (!stopFlag) {
            val image = try { reader?.acquireLatestImage() } catch (e: Exception) { null }
            if (image == null) {
                sleep(15)
                continue
            }
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * w

                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)

                val frame = IntArray(w * h)
                var offset = 0
                var idx = 0
                var y = 0
                while (y < h) {
                    var x = 0
                    while (x < w) {
                        val r = bytes[offset].toInt() and 0xFF
                        val g = bytes[offset + 1].toInt() and 0xFF
                        val b = bytes[offset + 2].toInt() and 0xFF
                        frame[idx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        offset += pixelStride
                        x++
                    }
                    offset += rowPadding
                    y++
                }

                ClickerState.frame = frame
                ClickerState.frameW = w
                ClickerState.frameH = h

                detect(frame, regWH, tmpWH)
            } catch (e: Exception) {
                // битый кадр — пропускаем
            } finally {
                try { image.close() } catch (_: Exception) {}
            }
            sleep(30)
        }
    }

    private fun detect(frame: IntArray, regWH: IntArray, tmpWH: IntArray) {
        if (!ClickerState.running) return
        if (ClickerState.adPaused) {
            ClickerState.status("📺 реклама — стоп (жми «Реклама» чтобы продолжить)")
            return
        }
        // Пока пользователь трогает экран (свайп/прокрутка) — не тапаем, не мешаем.
        if (System.currentTimeMillis() - ClickerState.lastUserTouchAt < ClickerState.userIdleMs) {
            ClickerState.status("🖐 ты трогаешь экран — пауза")
            return
        }

        // Режим ЦВЕТА — отдельная ветка (для летящих/вращающихся объектов).
        if (ClickerState.mode == ClickerState.Mode.COLOR) {
            detectColor(frame)
            return
        }

        val tmpls = ClickerState.templates
        if (tmpls.isEmpty()) {
            ClickerState.status("Целей нет — нажми «Цель»")
            return
        }
        val scale = ClickerState.searchScale

        val base = ClickerState.region ?: Rect(0, 0, w, h)
        val rc = Rect(
            base.left.coerceIn(0, w - 1),
            base.top.coerceIn(0, h - 1),
            base.right.coerceIn(1, w),
            base.bottom.coerceIn(1, h)
        )
        if (rc.width() < 4 || rc.height() < 4) {
            ClickerState.status("Зона поиска слишком мала")
            return
        }

        // Регион считаем один раз, дальше ищем в нём КАЖДУЮ цель, берём лучшую.
        val regGray = Matcher.cropGray(frame, w, h, rc)
        val regDown = Matcher.downscale(regGray, rc.width(), rc.height(), scale, regWH)

        var best = -1f
        var bestX = 0
        var bestY = 0
        var bestTW = 0
        var bestTH = 0
        var bestIdx = -1
        var bestTmpl: ClickerState.Template? = null

        for ((i, t) in tmpls.withIndex()) {
            val tmplDown = Matcher.downscale(t.gray, t.w, t.h, scale, tmpWH)
            val hit = Matcher.match(regDown, regWH[0], regWH[1], tmplDown, tmpWH[0], tmpWH[1])
            if (hit.score > best) {
                best = hit.score
                bestX = hit.x
                bestY = hit.y
                bestTW = t.w
                bestTH = t.h
                bestIdx = i
                bestTmpl = t
            }
        }
        ClickerState.lastScore = best

        if (best >= ClickerState.threshold && bestIdx >= 0 && bestTmpl != null) {
            // Координаты из масштаба поиска -> в полный экран, к центру шаблона.
            val fullX = rc.left + (bestX / scale).toInt() + bestTW / 2
            val fullY = rc.top + (bestY / scale).toInt() + bestTH / 2

            // ── Подтверждение ЦВЕТОМ (2-я ступень): grayscale не различает цвета,
            // из-за чего разные капсулы можно спутать. Сверяем найденное место с
            // цветом шаблона в полном разрешении. Не прошло — не наш объект.
            if (ClickerState.useColorCheck) {
                val tlx = (fullX - bestTW / 2).coerceIn(0, w - bestTW)
                val tly = (fullY - bestTH / 2).coerceIn(0, h - bestTH)
                if (bestTW in 1..w && bestTH in 1..h) {
                    val roi = Matcher.cropArgb(
                        frame, w, h, Rect(tlx, tly, tlx + bestTW, tly + bestTH)
                    )
                    val cs = Matcher.colorScore(roi, bestTmpl.color)
                    if (cs in 0f..ClickerState.colorConf) {
                        ClickerState.status(
                            "цвет не совпал ${fmt(cs)}/${fmt(ClickerState.colorConf)} — пропуск"
                        )
                        return
                    }
                }
            }

            val now = System.currentTimeMillis()
            if (now - ClickerState.lastClickAt >= ClickerState.cooldownMs) {
                val svc = ClickAccessibilityService.instance
                if (svc != null) {
                    svc.tap(fullX.toFloat(), fullY.toFloat())
                    OverlayController.showClickMarker(fullX, fullY)   // затухающий крестик
                    ClickerState.lastClickAt = now
                    ClickerState.lastSelfTapAt = now   // чтобы наш тап не приняли за касание юзера
                    ClickerState.clicks++
                    ClickerState.status(
                        "Клик #${ClickerState.clicks}  цель ${bestIdx + 1}/${tmpls.size}  ${fmt(best)}  @($fullX,$fullY)"
                    )
                } else {
                    ClickerState.status("Служба тапов ВЫКЛ — включи в настройках")
                }
            }
        } else {
            ClickerState.status("сходство ${fmt(best)} / порог ${fmt(ClickerState.threshold)}  (целей ${tmpls.size})")
        }
    }

    private fun detectColor(frame: IntArray) {
        if (!ClickerState.hasColorTarget) {
            ClickerState.status("Цвет не задан — обведи объект в режиме «Цвет»")
            return
        }
        val base = ClickerState.region ?: Rect(0, 0, w, h)
        val rc = Rect(
            base.left.coerceIn(0, w - 1),
            base.top.coerceIn(0, h - 1),
            base.right.coerceIn(1, w),
            base.bottom.coerceIn(1, h)
        )
        val blob = ColorFinder.find(
            frame, w, h, rc,
            ClickerState.colR, ClickerState.colG, ClickerState.colB,
            ClickerState.colorTol, ClickerState.minBlob, ClickerState.searchScale
        )
        ClickerState.lastScore = blob.count.toFloat()
        val now = System.currentTimeMillis()

        if (!blob.found) {
            ClickerState.prevT = 0
            ClickerState.status("нет объекта (точек ${blob.count}, допуск ${ClickerState.colorTol})")
            return
        }

        // Упреждение: считаем скорость по двум последним детекциям и целимся вперёд.
        var tx = blob.cx
        var ty = blob.cy
        if (ClickerState.prevT > 0 && now - ClickerState.prevT in 1..300) {
            val dt = (now - ClickerState.prevT) / 1000f
            if (dt > 0f) {
                val vx = (blob.cx - ClickerState.prevCx) / dt
                val vy = (blob.cy - ClickerState.prevCy) / dt
                tx += (vx * ClickerState.leadMs / 1000f).toInt()
                ty += (vy * ClickerState.leadMs / 1000f).toInt()
            }
        }
        ClickerState.prevCx = blob.cx
        ClickerState.prevCy = blob.cy
        ClickerState.prevT = now
        tx = tx.coerceIn(0, w - 1)
        ty = ty.coerceIn(0, h - 1)

        if (now - ClickerState.lastClickAt >= ClickerState.cooldownMs) {
            val svc = ClickAccessibilityService.instance
            if (svc != null) {
                svc.tap(tx.toFloat(), ty.toFloat())
                OverlayController.showClickMarker(tx, ty)
                ClickerState.lastClickAt = now
                ClickerState.lastSelfTapAt = now
                ClickerState.clicks++
                ClickerState.status("Клик #${ClickerState.clicks} (цвет, точек ${blob.count}) @($tx,$ty)")
            } else {
                ClickerState.status("Служба тапов ВЫКЛ — включи в настройках")
            }
        }
    }

    private fun fmt(v: Float): String = String.format("%.2f", v)

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    private fun cleanup() {
        stopFlag = true
        try { loop?.join(300) } catch (_: Exception) {}
        try { vdisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        vdisplay = null
        reader = null
        projection = null
        ClickerState.running = false
        ClickerState.frame = null
        try { capThread?.quitSafely() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Capsule Clicker",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Capsule Clicker")
            .setContentText("Захват экрана активен")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "capture"

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, ScreenCaptureService::class.java)
                .putExtra(EXTRA_CODE, code)
                .putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
        }
    }
}

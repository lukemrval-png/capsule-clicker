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
        val tmpl = ClickerState.template ?: run {
            ClickerState.status("Шаблон не задан — нажми «Цель»")
            return
        }
        val tw = ClickerState.templateW
        val th = ClickerState.templateH
        val scale = ClickerState.searchScale

        val base = ClickerState.region ?: Rect(0, 0, w, h)
        val rc = Rect(
            base.left.coerceIn(0, w - 1),
            base.top.coerceIn(0, h - 1),
            base.right.coerceIn(1, w),
            base.bottom.coerceIn(1, h)
        )
        if (rc.width() < tw || rc.height() < th) {
            ClickerState.status("Зона поиска меньше шаблона")
            return
        }

        val regGray = Matcher.cropGray(frame, w, h, rc)
        val regDown = Matcher.downscale(regGray, rc.width(), rc.height(), scale, regWH)
        val tmplDown = Matcher.downscale(tmpl, tw, th, scale, tmpWH)

        val hit = Matcher.match(regDown, regWH[0], regWH[1], tmplDown, tmpWH[0], tmpWH[1])
        ClickerState.lastScore = hit.score

        if (hit.score >= ClickerState.threshold) {
            // Координаты из масштаба поиска -> в полный экран, к центру шаблона.
            val fullX = rc.left + (hit.x / scale).toInt() + tw / 2
            val fullY = rc.top + (hit.y / scale).toInt() + th / 2
            val now = System.currentTimeMillis()
            if (now - ClickerState.lastClickAt >= ClickerState.cooldownMs) {
                val svc = ClickAccessibilityService.instance
                if (svc != null) {
                    svc.tap(fullX.toFloat(), fullY.toFloat())
                    ClickerState.lastClickAt = now
                    ClickerState.clicks++
                    ClickerState.status(
                        "Клик #${ClickerState.clicks}  сходство ${fmt(hit.score)}  @($fullX,$fullY)"
                    )
                } else {
                    ClickerState.status("Служба тапов ВЫКЛ — включи в настройках")
                }
            }
        } else {
            ClickerState.status("сходство ${fmt(hit.score)} / порог ${fmt(ClickerState.threshold)}")
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

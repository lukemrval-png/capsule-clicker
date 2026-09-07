package com.capsule.clicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Экран настройки: три разрешения по шагам, запуск захвата и плавающей панели.
 * Дальше всё управление — с плавающей панели прямо в игре.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(title("Capsule Clicker (без root)"))
        root.addView(hint("Выполни шаги по порядку. Потом открой игру и управляй с плавающей панели."))

        root.addView(stepButton("1. Включить службу тапов") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(hint("В списке найди «Capsule Clicker» и включи."))

        root.addView(stepButton("2. Разрешить поверх окон") {
            if (!canOverlay()) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                status.text = "Разрешение «поверх окон» уже есть."
            }
        })

        root.addView(stepButton("3. Запустить захват + панель") {
            requestNotifIfNeeded()
            if (!canOverlay()) {
                status.text = "Сначала выполни шаг 2 (поверх окон)."
                return@stepButton
            }
            requestCapture()
        })

        root.addView(stepButton("Остановить всё") {
            ClickerState.running = false
            OverlayController.hide()
            ScreenCaptureService.stop(this)
            status.text = "Остановлено."
        })

        status = TextView(this).apply {
            text = "—"
            setTextColor(Color.parseColor("#2E7D32"))
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(status)

        root.addView(hint(
            "Подсказка: «Цель» — тапни по капсуле в игре, программа запомнит её вид. " +
                "«Старт» — начнёт искать и кликать. Порог ниже = срабатывает чаще."
        ))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun requestCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ScreenCaptureService.start(this, resultCode, data)
                OverlayController.show(this)
                status.text = "Захват и панель запущены. Открой игру и жми «Цель» на панели."
                // Свернуть приложение, чтобы открыть игру.
                moveTaskToBack(true)
            } else {
                status.text = "Захват экрана отклонён."
            }
        }
    }

    private fun requestNotifIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        }
    }

    private fun canOverlay(): Boolean = Settings.canDrawOverlays(this)

    // ── маленькие помощники для UI ──
    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 20f
        setTextColor(Color.BLACK)
        setPadding(0, 0, 0, dp(8))
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.DKGRAY)
        setPadding(0, dp(2), 0, dp(10))
    }

    private fun stepButton(t: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = t
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_CAPTURE = 1001
        private const val REQ_NOTIF = 1002
    }
}

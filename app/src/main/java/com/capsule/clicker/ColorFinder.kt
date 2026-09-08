package com.capsule.clicker

import android.graphics.Rect

/**
 * Поиск объекта ПО ЦВЕТУ (для летящих/вращающихся целей — вращение не мешает).
 * Идём по зоне с шагом (ради скорости), собираем точки, чей цвет близок к целевому,
 * и возвращаем центр их масс (центроид) — туда и целимся.
 */
object ColorFinder {

    class Blob(val found: Boolean, val cx: Int, val cy: Int, val count: Int)

    fun find(
        frame: IntArray, fw: Int, fh: Int, rect: Rect,
        tr: Int, tg: Int, tb: Int, tol: Int, minCount: Int, scale: Float
    ): Blob {
        val step = maxOf(1, (1f / scale).toInt())     // 0.25 -> шаг 4 пикселя
        val tolSq = 3 * tol * tol                      // порог по сумме квадратов отличий каналов

        var sumX = 0L
        var sumY = 0L
        var n = 0

        var y = rect.top
        while (y < rect.bottom) {
            val row = y * fw
            var x = rect.left
            while (x < rect.right) {
                val p = frame[row + x]
                val dr = ((p shr 16) and 0xFF) - tr
                val dg = ((p shr 8) and 0xFF) - tg
                val db = (p and 0xFF) - tb
                if (dr * dr + dg * dg + db * db <= tolSq) {
                    sumX += x
                    sumY += y
                    n++
                }
                x += step
            }
            y += step
        }

        return if (n >= minCount) {
            Blob(true, (sumX / n).toInt(), (sumY / n).toInt(), n)
        } else {
            Blob(false, 0, 0, n)
        }
    }
}

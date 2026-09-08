package com.capsule.clicker

import android.graphics.Rect
import kotlin.math.sqrt

/**
 * Поиск шаблона на экране без OpenCV — чистый Kotlin.
 * Работаем по grayscale и с даунскейлом ради скорости, метрика — нормированная
 * кросс-корреляция (NCC), устойчива к изменению яркости. Результат 0..1.
 */
object Matcher {

    /** ARGB int -> яркость 0..255. */
    private fun gray(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Вырезать прямоугольник из кадра в grayscale (полный масштаб). */
    fun cropGray(frame: IntArray, fw: Int, fh: Int, rect: Rect): IntArray {
        val w = rect.width()
        val h = rect.height()
        val out = IntArray(w * h)
        var i = 0
        var y = rect.top
        while (y < rect.bottom) {
            val row = y * fw
            var x = rect.left
            while (x < rect.right) {
                out[i++] = gray(frame[row + x])
                x++
            }
            y++
        }
        return out
    }

    /** Вырезать прямоугольник из кадра как есть (ARGB) — для проверки цвета. */
    fun cropArgb(frame: IntArray, fw: Int, fh: Int, rect: Rect): IntArray {
        val w = rect.width()
        val h = rect.height()
        val out = IntArray(w * h)
        var i = 0
        var y = rect.top
        while (y < rect.bottom) {
            val row = y * fw
            var x = rect.left
            while (x < rect.right) {
                out[i++] = frame[row + x]
                x++
            }
            y++
        }
        return out
    }

    /** Подтверждение цветом: NCC по каналам R,G,B между найденным местом и шаблоном.
     *  Оба массива одного размера. Возвращает 0..1 (или -1 при несовпадении размеров). */
    fun colorScore(roi: IntArray, tmpl: IntArray): Float {
        val n = roi.size
        if (n == 0 || n != tmpl.size) return -1f
        var mr1 = 0.0; var mg1 = 0.0; var mb1 = 0.0
        var mr2 = 0.0; var mg2 = 0.0; var mb2 = 0.0
        for (k in 0 until n) {
            val a = roi[k]; val b = tmpl[k]
            mr1 += (a shr 16) and 0xFF; mg1 += (a shr 8) and 0xFF; mb1 += a and 0xFF
            mr2 += (b shr 16) and 0xFF; mg2 += (b shr 8) and 0xFF; mb2 += b and 0xFF
        }
        mr1 /= n; mg1 /= n; mb1 /= n
        mr2 /= n; mg2 /= n; mb2 /= n
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (k in 0 until n) {
            val a = roi[k]; val b = tmpl[k]
            val ar = ((a shr 16) and 0xFF) - mr1
            val ag = ((a shr 8) and 0xFF) - mg1
            val ab = (a and 0xFF) - mb1
            val br = ((b shr 16) and 0xFF) - mr2
            val bg = ((b shr 8) and 0xFF) - mg2
            val bb = (b and 0xFF) - mb2
            dot += ar * br + ag * bg + ab * bb
            na += ar * ar + ag * ag + ab * ab
            nb += br * br + bg * bg + bb * bb
        }
        if (na < 1e-6 || nb < 1e-6) return -1f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** Уменьшение grayscale-массива (ближайший сосед). Размеры пишутся в outWH. */
    fun downscale(src: IntArray, w: Int, h: Int, scale: Float, outWH: IntArray): IntArray {
        val dw = maxOf(1, (w * scale).toInt())
        val dh = maxOf(1, (h * scale).toInt())
        val dst = IntArray(dw * dh)
        var i = 0
        var dy = 0
        while (dy < dh) {
            val sy = (dy / scale).toInt().coerceIn(0, h - 1)
            val srow = sy * w
            var dx = 0
            while (dx < dw) {
                val sx = (dx / scale).toInt().coerceIn(0, w - 1)
                dst[i++] = src[srow + sx]
                dx++
            }
            dy++
        }
        outWH[0] = dw
        outWH[1] = dh
        return dst
    }

    /** score — лучшее сходство 0..1; x,y — верхний левый угол совпадения в координатах региона. */
    data class Hit(val score: Float, val x: Int, val y: Int)

    /** Скользим шаблоном по региону, ищем максимум NCC. */
    fun match(region: IntArray, rw: Int, rh: Int, tmpl: IntArray, tw: Int, th: Int): Hit {
        if (tw > rw || th > rh || tw <= 0 || th <= 0) return Hit(-1f, 0, 0)

        // Центрируем шаблон и считаем его норму заранее.
        val n = tw * th
        var tSum = 0.0
        for (v in tmpl) tSum += v
        val tMean = tSum / n
        val tCentered = DoubleArray(n)
        var tNorm = 0.0
        for (k in 0 until n) {
            val c = tmpl[k] - tMean
            tCentered[k] = c
            tNorm += c * c
        }
        tNorm = sqrt(tNorm)
        if (tNorm < 1e-6) return Hit(-1f, 0, 0)

        var best = -1f
        var bx = 0
        var by = 0
        val maxY = rh - th
        val maxX = rw - tw

        var y = 0
        while (y <= maxY) {
            var x = 0
            while (x <= maxX) {
                // Среднее яркости окна.
                var wSum = 0.0
                var yy = 0
                while (yy < th) {
                    val rrow = (y + yy) * rw + x
                    var xx = 0
                    while (xx < tw) {
                        wSum += region[rrow + xx]
                        xx++
                    }
                    yy++
                }
                val wMean = wSum / n

                // Числитель (скалярное произведение) и норма окна.
                var dot = 0.0
                var wNorm = 0.0
                var k = 0
                yy = 0
                while (yy < th) {
                    val rrow = (y + yy) * rw + x
                    var xx = 0
                    while (xx < tw) {
                        val wc = region[rrow + xx] - wMean
                        dot += wc * tCentered[k]
                        wNorm += wc * wc
                        k++
                        xx++
                    }
                    yy++
                }

                if (wNorm > 1e-6) {
                    val score = (dot / (sqrt(wNorm) * tNorm)).toFloat()
                    if (score > best) {
                        best = score
                        bx = x
                        by = y
                    }
                }
                x++
            }
            y++
        }
        return Hit(best, bx, by)
    }
}

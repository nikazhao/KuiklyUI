package com.tencent.kuikly.core.render.android.expand.component.text

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import kotlin.math.max
import kotlin.math.min

/**
 * 在文字 baseline（基线）下方手动画虚线的 span。
 *
 * 这里放在 framework 层，供 RichText 的整段/局部 span 共同复用，避免只能走 demo adapter。
 */
class KRDashedUnderlineSpan : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        return paint.measureText(text, start, end).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        baseline: Int,
        bottom: Int,
        paint: Paint
    ) {
        canvas.drawText(text, start, end, x, baseline.toFloat(), paint)

        val textWidth = paint.measureText(text, start, end)
        val thickness = max(1f, paint.textSize * 0.08f)
        val dash = paint.textSize * 0.45f
        val gap = paint.textSize * 0.35f
        val lineY = baseline + thickness * 2f

        val underlinePaint = Paint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = thickness
            color = paint.color
        }

        var cursorX = x
        val maxX = x + textWidth
        while (cursorX < maxX) {
            val nextX = min(cursorX + dash, maxX)
            canvas.drawLine(cursorX, lineY, nextX, lineY, underlinePaint)
            cursorX += dash + gap
        }
    }
}

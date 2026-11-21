package com.tabi.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** 카메라 화면 위에 번역된 한국어를 원래 글자 자리에 붙여 그리는 뷰 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    data class Label(val box: RectF, val text: String)

    private var labels: List<Label> = emptyList()
    private val density = resources.displayMetrics.density

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE0402A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF16171A.toInt()
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setLabels(list: List<Label>) {
        labels = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = density
        for (lb in labels) {
            if (lb.text.isBlank()) continue
            val w = max(lb.box.width(), 90f * d).coerceAtMost(width - 8f * d).toInt().coerceAtLeast(1)
            tp.textSize = (lb.box.height() * 0.75f).coerceIn(12f * d, 24f * d)
            val layout = StaticLayout.Builder
                .obtain(lb.text, 0, lb.text.length, tp, w)
                .setIncludePad(false)
                .build()
            val left = lb.box.left.coerceIn(4f * d, max(4f * d, width - w - 4f * d))
            val top = lb.box.top.coerceIn(0f, max(0f, height - layout.height.toFloat()))
            val h = max(layout.height.toFloat(), lb.box.height())
            val r = RectF(left - 5f * d, top - 3f * d, left + w + 5f * d, top + h + 3f * d)
            canvas.drawRoundRect(r, 6f * d, 6f * d, bg)
            canvas.drawLine(r.left + 1.5f * d, r.top + 4f * d, r.left + 1.5f * d, r.bottom - 4f * d, edge)
            canvas.save()
            canvas.translate(left + 2f * d, top + (h - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
    }
}

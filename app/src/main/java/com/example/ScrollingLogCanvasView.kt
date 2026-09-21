package com.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class ScrollingLogCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF08090C.toInt() // Deep black terminal bg
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF05D9E8.toInt() // Neon cyan border
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF39FF14.toInt() // Cyber neon green text
        textSize = 24f
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF2A6D.toInt() // Neon pink header
        textSize = 24f
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }

    private val logs = mutableListOf<String>()

    init {
        logs.add("系统终端准备就绪，等待导入指令...")
    }

    fun clearLogs() {
        logs.clear()
        invalidate()
    }

    fun addLog(message: String) {
        logs.add(message)
        if (logs.size > 5) {
            logs.removeAt(0)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background card with rounded corners
        val rect = RectF(2f, 2f, width.toFloat() - 2f, height.toFloat() - 2f)
        canvas.drawRoundRect(rect, 12f, 12f, bgPaint)
        canvas.drawRoundRect(rect, 12f, 12f, borderPaint)

        // Draw terminal header
        canvas.drawText("⚡ POTPLAYER-STYLE SUBSCRIPTION ENGINE v1.2", 20f, 32f, headerPaint)
        canvas.drawLine(15f, 44f, width.toFloat() - 15f, 44f, borderPaint)

        // Draw logs line by line
        var startY = 74f
        for (log in logs) {
            canvas.drawText("> $log", 20f, startY, textPaint)
            startY += 30f
        }
    }
}

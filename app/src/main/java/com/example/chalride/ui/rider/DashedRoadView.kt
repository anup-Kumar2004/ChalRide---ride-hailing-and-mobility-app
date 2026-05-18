package com.example.chalride.ui.rider

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class DashedRoadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x556C63FF.toInt()   // brand_primary at ~33% alpha
        strokeWidth = 4f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(
            floatArrayOf(16f, 12f),  // 16px dash, 12px gap
            0f
        )
    }

    override fun onDraw(canvas: Canvas) {
        val midY = height / 2f
        canvas.drawLine(0f, midY, width.toFloat(), midY, paint)
    }
}
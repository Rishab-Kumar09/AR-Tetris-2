package com.example.tetris

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val outerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        alpha = 100
    }

    private val innerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        alpha = 150
    }

    private var centerX = 0f
    private var centerY = 0f
    private var outerRadius = 0f
    private var innerRadius = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var isTouching = false

    var onJoystickMoved: ((Float) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        
        // Set dimensions based on view size
        outerRadius = min(w, h) / 3f
        innerRadius = outerRadius / 2
        centerX = outerRadius + 50f // Offset from left edge
        centerY = h - outerRadius - 50f // Offset from bottom
        knobX = centerX
        knobY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw outer circle
        canvas.drawCircle(centerX, centerY, outerRadius, outerPaint)
        
        // Draw knob
        canvas.drawCircle(knobX, knobY, innerRadius, innerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                isTouching = true
                updateKnobPosition(event.x, event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                // Reset knob to center
                knobX = centerX
                knobY = centerY
                onJoystickMoved?.invoke(0f)
                invalidate()
            }
        }
        return true
    }

    private fun updateKnobPosition(touchX: Float, touchY: Float) {
        // Calculate distance from center
        val dx = touchX - centerX
        val dy = touchY - centerY
        val distance = sqrt(dx * dx + dy * dy)

        // If touch is outside the outer circle, normalize the position
        if (distance > outerRadius) {
            knobX = centerX + (dx / distance * outerRadius)
            knobY = centerY + (dy / distance * outerRadius)
        } else {
            knobX = touchX
            knobY = touchY
        }

        // Calculate horizontal movement (-1 to 1)
        val movement = (knobX - centerX) / outerRadius
        onJoystickMoved?.invoke(movement)

        invalidate()
    }
} 
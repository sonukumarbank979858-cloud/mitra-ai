package com.sonuclasses.mitra.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator
import com.sonuclasses.mitra.core.VState

private fun stateColor(s: VState): Int = when (s) {
    VState.LISTENING -> Color.parseColor("#22D3EE")
    VState.SPEAKING -> Color.parseColor("#A78BFA")
    VState.THINKING -> Color.parseColor("#F472B6")
    VState.CONNECTING -> Color.parseColor("#FBBF24")
    VState.IDLE -> Color.parseColor("#8B5CF6")
}

/** Floating orb bubble that pulses with the voice level. */
class BubbleView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var state = VState.IDLE
    private var level = 0f

    fun update(s: VState, l: Float) { state = s; level = l; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(cx, cy) * (0.70f + 0.18f * level)
        val c = stateColor(state)
        glow.shader = RadialGradient(cx, cy, minOf(cx, cy), Color.argb(if (state == VState.IDLE) 40 else 110, Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, minOf(cx, cy), glow)
        paint.shader = RadialGradient(cx - r / 3, cy - r / 3, r * 1.3f, intArrayOf(Color.WHITE, c, Color.parseColor("#1E1B4B")), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
    }
}

/** Glowing ring around the screen edge while Mitra listens or speaks. */
class EdgeRingView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var state = VState.IDLE
    private var level = 0f
    private var spin = 0f
    private val anim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 3000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedValue as Float; if (state != VState.IDLE) invalidate() }
    }

    fun update(s: VState, l: Float) {
        val wasIdle = state == VState.IDLE
        state = s; level = l
        if (s == VState.IDLE) { anim.cancel(); invalidate() } else if (wasIdle || !anim.isRunning) anim.start()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (state == VState.IDLE) return
        val stroke = 10f + 18f * level
        paint.strokeWidth = stroke
        val cx = width / 2f; val cy = height / 2f
        val colors = intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#8B5CF6"), Color.parseColor("#F472B6"), Color.parseColor("#22D3EE"))
        val sg = SweepGradient(cx, cy, colors, null)
        val m = android.graphics.Matrix(); m.setRotate(spin, cx, cy); sg.setLocalMatrix(m)
        paint.shader = sg
        paint.alpha = (150 + 100 * level).toInt().coerceIn(0, 255)
        val half = stroke / 2
        canvas.drawRoundRect(RectF(half, half, width - half, height - half), 60f, 60f, paint)
    }
}

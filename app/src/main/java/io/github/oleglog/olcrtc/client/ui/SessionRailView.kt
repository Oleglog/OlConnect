package io.github.oleglog.olcrtc.client.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.statistics.RAIL_SLOTS
import io.github.oleglog.olcrtc.client.statistics.RailCode

/**
 * The rail: one hour of channel state as ticks, the signature device of the
 * design. One slot is one minute, a missing minute is a faint tick — absence of
 * a measurement is not the same thing as "stopped" (design-system §12.3).
 *
 * Ticks are drawn, not masked: the CSS version punches a gradient through a
 * repeating mask, which is one `drawRoundRect` per slot here.
 */
internal class SessionRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slotColors = mapOf(
        RailCode.LIVE to ContextCompat.getColor(context, R.color.state_live),
        RailCode.DEGRADED to ContextCompat.getColor(context, R.color.state_degraded),
        RailCode.LIMIT to ContextCompat.getColor(context, R.color.state_warn),
        RailCode.DOWN to ContextCompat.getColor(context, R.color.state_down),
        RailCode.STOPPED to ContextCompat.getColor(context, R.color.state_idle),
        RailCode.NONE to ContextCompat.getColor(context, R.color.olcrtc_line_strong),
    )

    private var codes: List<RailCode> = List(RAIL_SLOTS) { RailCode.NONE }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Slots oldest first, newest last; shorter lists are padded with "no data". */
    fun setCodes(value: List<RailCode>) {
        codes = value.takeLast(RAIL_SLOTS)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val slot = width.toFloat() / RAIL_SLOTS
        val tick = (slot - resources.displayMetrics.density * 2f).coerceAtLeast(slot * 0.35f)
        val radius = tick / 2f
        val bottom = height.toFloat()
        for (index in 0 until RAIL_SLOTS) {
            val code = codes.getOrElse(index) { RailCode.NONE }
            tickPaint.color = slotColors[code] ?: slotColors.getValue(RailCode.NONE)
            val left = index * slot + (slot - tick) / 2f
            canvas.drawRoundRect(left, 0f, left + tick, bottom, radius, radius, tickPaint)
        }
    }
}

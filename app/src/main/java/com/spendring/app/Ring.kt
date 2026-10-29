package com.spendring.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.RemoteViews
import kotlin.math.min

object Colors {
    const val BG = 0xFF0B1220.toInt()
    const val SURFACE = 0xFF141D2E.toInt()
    const val FIELD = 0xFF1C2740.toInt()
    const val FG = 0xFFEEF2FF.toInt()
    const val MUTED = 0xFF94A3C4.toInt()
    const val LINE = 0xFF243150.toInt()
    const val TRACK = 0xFF1E2A44.toInt()

    // Bright set
    const val GREEN = 0xFF34D399.toInt()
    const val ORANGE = 0xFFFB923C.toInt()
    const val RED = 0xFFF87171.toInt()
    const val BLUE = 0xFF60A5FA.toInt()
    const val PURPLE = 0xFFA78BFA.toInt()
    const val PINK = 0xFFF472B6.toInt()
    const val YELLOW = 0xFFFACC15.toInt()

    const val ACCENT = BLUE
    const val OK = GREEN
    const val WARN = ORANGE
    const val BAD = RED

    /** Same colour at [a] (0..255) opacity, for tinted backgrounds. */
    fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    fun of(s: Summary.State) = when (s) {
        Summary.State.OVER, Summary.State.LOW -> RED
        Summary.State.HALF -> ORANGE
        Summary.State.PLENTY -> GREEN
        Summary.State.NOT_SET -> MUTED
    }

    /** Darker partner for the ring's gradient. */
    fun deep(s: Summary.State) = when (s) {
        Summary.State.OVER, Summary.State.LOW -> 0xFFDC2626.toInt()
        Summary.State.HALF -> 0xFFEA580C.toInt()
        Summary.State.PLENTY -> 0xFF10B981.toInt()
        Summary.State.NOT_SET -> MUTED
    }

    fun label(s: Summary.State) = when (s) {
        Summary.State.OVER -> "Over budget"
        Summary.State.LOW -> "Running low"
        Summary.State.HALF -> "Under half left"
        Summary.State.PLENTY -> "Plenty left"
        Summary.State.NOT_SET -> "Set your budget"
    }
}

/** Draws the "money left" ring. Used by the app screen and the home-screen widget. */
object RingDraw {
    private val mono: Typeface = Typeface.create("monospace", Typeface.BOLD)

    fun draw(c: Canvas, w: Float, h: Float, s: Summary, widget: Boolean) {
        val size = min(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val stroke = size * (if (widget) 0.09f else 0.082f)
        val r = size / 2f - stroke - 2f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        val col = Colors.of(s.state)

        c.drawArc(oval, 0f, 360f, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = Colors.TRACK
        })

        val frac = if (s.budget > 0) (s.left / s.budget).coerceIn(0.0, 1.0).toFloat() else 0f
        if (frac > 0f) {
            val sweep = 360f * frac
            // soft glow under the arc
            c.drawArc(oval, -90f, sweep, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke * 1.9f; color = Colors.alpha(col, 0x2A)
                strokeCap = Paint.Cap.ROUND
            })
            val grad = SweepGradient(cx, cy, intArrayOf(Colors.deep(s.state), col, col), floatArrayOf(0f, (frac * 0.7f).coerceAtLeast(0.01f), 1f))
            grad.setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) })
            c.drawArc(oval, -90f, sweep, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke; shader = grad; strokeCap = Paint.Cap.ROUND
            })
            // bright dot at the end of the arc
            val end = Math.toRadians((-90.0 + sweep))
            c.drawCircle(cx + (r * Math.cos(end)).toFloat(), cy + (r * Math.sin(end)).toFloat(), stroke * 0.26f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.FG })
        }

        // Time marker: how far through the cycle you are.
        if (s.cycle != null) {
            val ang = Math.toRadians((-90.0 + 360.0 * (1.0 - s.timeFrac)))
            // drawn across the ring itself so it never touches the text inside
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.BG; strokeWidth = stroke * 0.2f; strokeCap = Paint.Cap.ROUND }
            val r1 = r - stroke * 0.42f
            val r2 = r + stroke * 0.42f
            c.drawLine(
                cx + (r1 * Math.cos(ang)).toFloat(), cy + (r1 * Math.sin(ang)).toFloat(),
                cx + (r2 * Math.cos(ang)).toFloat(), cy + (r2 * Math.sin(ang)).toFloat(), tick,
            )
        }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

        text.typeface = Typeface.DEFAULT_BOLD
        text.color = Colors.MUTED
        text.textSize = size * 0.055f
        text.letterSpacing = 0.14f
        c.drawText("LEFT TO SPEND", cx, cy - size * 0.13f, text)

        val big = if (s.state == Summary.State.NOT_SET) "—" else Store.money(s.left, kotlin.math.abs(s.left) < 10_000)
        text.letterSpacing = 0f
        text.typeface = mono
        text.color = if (s.state == Summary.State.NOT_SET) Colors.FG else col
        text.textSize = size * 0.17f
        val maxW = r * 1.5f
        val tw = text.measureText(big)
        if (tw > maxW) text.textSize *= maxW / tw
        c.drawText(big, cx, cy + size * 0.045f, text)

        text.typeface = Typeface.DEFAULT
        text.color = Colors.MUTED
        text.textSize = size * 0.056f
        val sub = if (s.budget > 0) "of ${Store.money(s.budget, false)}" else "Tap to set up"
        c.drawText(sub, cx, cy + size * 0.13f, text)

        // state pill
        val st = if (widget && s.cycle != null) "${s.daysLeft}d to payday" else Colors.label(s.state)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = size * 0.05f
        val pw = text.measureText(st) + size * 0.08f
        val py = cy + size * 0.215f
        val ph = size * 0.085f
        c.drawRoundRect(RectF(cx - pw / 2, py - ph * 0.72f, cx + pw / 2, py + ph * 0.38f), ph / 2, ph / 2,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.alpha(col, 0x33) })
        text.color = col
        c.drawText(st, cx, py, text)
    }
}

/** Spending per day across the cycle, coloured against the daily allowance. */
class DayBars @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var days: List<Pair<Long, Double>> = emptyList()  // day start, amount spent
    var allowance = 0.0
    var todayIndex = -1

    fun set(d: List<Pair<Long, Double>>, perDay: Double, today: Int) {
        days = d; allowance = perDay; todayIndex = today; invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), (resources.displayMetrics.density * 150).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (days.isEmpty()) return
        val d = resources.displayMetrics.density
        val labelH = 18 * d
        val top = 8 * d
        val h = height - labelH - top
        val n = days.size
        val slot = width.toFloat() / n
        val bw = (slot * 0.62f).coerceAtMost(22 * d)
        val maxV = maxOf(days.maxOf { it.second }, allowance * 1.6, 1.0)
        val y = { v: Double -> top + h - (v / maxV * h).toFloat() }

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.MUTED; textSize = 10.5f * d; textAlign = Paint.Align.CENTER }
        val fmt = java.text.SimpleDateFormat("EEEEE", java.util.Locale.getDefault())
        days.forEachIndexed { i, (day, v) ->
            val x = slot * i + slot / 2
            val future = todayIndex in 0 until i
            val color = when {
                future -> Colors.TRACK
                v <= 0.0 -> Colors.TRACK
                v <= allowance -> Colors.GREEN
                v <= allowance * 2 -> Colors.ORANGE
                else -> Colors.RED
            }
            val barTop = if (v > 0) y(v).coerceAtMost(top + h - 4 * d) else top + h - 4 * d
            canvas.drawRoundRect(RectF(x - bw / 2, barTop, x + bw / 2, top + h), bw / 2.5f, bw / 2.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            label.color = if (i == todayIndex) Colors.FG else Colors.MUTED
            label.typeface = if (i == todayIndex) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(fmt.format(java.util.Date(day)), x, height - 4 * d, label)
        }
        if (allowance > 0) {
            val ay = y(allowance)
            canvas.drawLine(0f, ay, width.toFloat(), ay, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Colors.alpha(Colors.FG, 0x66); strokeWidth = 1.2f * d
                pathEffect = DashPathEffect(floatArrayOf(5 * d, 5 * d), 0f)
            })
        }
    }
}

class RingView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var summary: Summary? = null
        set(v) { field = v; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val side = min(w, (resources.displayMetrics.density * 280).toInt())
        setMeasuredDimension(w, side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = summary ?: return
        RingDraw.draw(canvas, width.toFloat(), height.toFloat(), s, widget = false)
    }
}

/** Home-screen widget: the ring, tap to open the app. */
class RingWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(ctx, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle?) {
        render(ctx, mgr, id)
    }

    companion object {
        fun updateAll(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, RingWidget::class.java)) ?: return
                ids.forEach { render(ctx, mgr, it) }
            } catch (_: Exception) {
            }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val d = ctx.resources.displayMetrics.density
            val opts = mgr.getAppWidgetOptions(id)
            val wDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 140) ?: 140
            val hDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 140) ?: 140
            val sideDp = min(wDp, hDp).coerceIn(100, 220)
            val px = (sideDp * d).toInt().coerceIn(200, 600)
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            RingDraw.draw(Canvas(bmp), px.toFloat(), px.toFloat(), Store(ctx).summary(), widget = true)

            val rv = RemoteViews(ctx.packageName, R.layout.widget_ring)
            rv.setImageViewBitmap(R.id.widget_ring, bmp)
            val pi = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.widget_root, pi)
            mgr.updateAppWidget(id, rv)
        }
    }
}

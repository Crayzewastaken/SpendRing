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
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Shader
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
        c.drawText(if (s.weekNo > 0) "LEFT THIS WEEK" else "LEFT TO SPEND", cx, cy - size * 0.13f, text)

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
        val sub = when {
            s.weekNo > 0 -> "of ${Store.money(s.budget, false)} · week ${s.weekNo} of 2"
            s.budget > 0 -> "of ${Store.money(s.budget, false)}"
            else -> "Tap to set up"
        }
        c.drawText(sub, cx, cy + size * 0.13f, text)

        // state pill
        val st = if (widget && s.cycle != null) "${s.daysToPayday}d to payday" else Colors.label(s.state)
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

/**
 * Live savings counter: a circle filling towards the next savings goal on the left, and the
 * balance ticking up every few hundred milliseconds on the right.
 */
class SavingsView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var plan: Plan? = null
        set(v) { field = v; invalidate() }

    private val goals = listOf(10_000.0, 20_000.0, 30_000.0, 40_000.0, 50_000.0, 75_000.0, 100_000.0)
    private val tick = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 200) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), (resources.displayMetrics.density * 124).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = plan ?: return
        val d = resources.displayMetrics.density
        val now = System.currentTimeMillis()
        val v = p.liveSavings(now)
        val goal = goals.firstOrNull { it > v } ?: (Math.ceil(v / 50_000.0) * 50_000.0 + 50_000.0)
        val prevGoal = goals.lastOrNull { it <= v } ?: 0.0
        val frac = ((v - prevGoal) / (goal - prevGoal)).toFloat().coerceIn(0f, 1f)

        // circle
        val size = height - 8 * d
        val stroke = 10 * d
        val cx = 4 * d + size / 2
        val cy = height / 2f
        val r = size / 2 - stroke / 2
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(oval, 0f, 360f, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = Colors.TRACK
        })
        val grad = SweepGradient(cx, cy, intArrayOf(Colors.BLUE, Colors.PURPLE, Colors.PINK, Colors.BLUE), null)
        grad.setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) })
        canvas.drawArc(oval, -90f, 360f * frac.coerceAtLeast(0.01f), false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; shader = grad; strokeCap = Paint.Cap.ROUND
        })
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        t.color = Colors.FG; t.textSize = 17 * d
        canvas.drawText("${(frac * 100).toInt()}%", cx, cy + 2 * d, t)
        t.color = Colors.MUTED; t.textSize = 10.5f * d; t.typeface = Typeface.DEFAULT
        canvas.drawText("to ${Store.money(goal, false).replace(",000", "k")}", cx, cy + 16 * d, t)

        // text
        val x = cx + size / 2 + 16 * d
        val l = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
        l.color = Colors.PURPLE; l.typeface = Typeface.DEFAULT_BOLD; l.textSize = 11 * d; l.letterSpacing = 0.1f
        canvas.drawText("SAVINGS · LIVE", x, cy - 26 * d, l)
        // pulsing dot
        val pulse = ((Math.sin(now / 300.0) + 1) / 2).toFloat()
        canvas.drawCircle(x + l.measureText("SAVINGS · LIVE") + 9 * d, cy - 30 * d, 3.5f * d,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.alpha(Colors.GREEN, (110 + 145 * pulse).toInt()) })

        l.letterSpacing = 0f
        l.color = Colors.FG; l.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); l.textSize = 27 * d
        val big = String.format(java.util.Locale.US, "$%,.2f", v)
        val maxW = width - x - 4 * d
        if (l.measureText(big) > maxW) l.textSize *= maxW / l.measureText(big)
        canvas.drawText(big, x, cy + 8 * d, l)

        l.typeface = Typeface.DEFAULT; l.textSize = 12.5f * d; l.color = Colors.GREEN
        canvas.drawText("+${Store.money(p.saving, false)} goes in every payday", x, cy + 28 * d, l)
        p.goalDate(goal)?.let {
            l.color = Colors.MUTED
            val m = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(it))
            canvas.drawText("${Store.money(goal, false)} around $m", x, cy + 46 * d, l)
        }
    }
}

/**
 * Big payday countdown: a colourful card with the days to go, a live hours/minutes/seconds
 * clock, the date, how far through the fortnight you are, and how much is coming in.
 */
class PaydayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var cycle: Cycle? = null
    private var pay = 0.0
    private val tick = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 1000) }
    }

    fun set(c: Cycle?, payAmount: Double) { cycle = c; pay = payAmount; invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), (resources.displayMetrics.density * 250).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = cycle ?: return
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val now = System.currentTimeMillis()
        val ms = (c.end - now).coerceAtLeast(0L)
        val days = ms / Store.DAY
        val hrs = (ms / 3_600_000L) % 24
        val mins = (ms / 60_000L) % 60
        val secs = (ms / 1000L) % 60

        // card: deep blue → purple → pink gradient with soft glow circles
        val rect = RectF(0f, 0f, w, h)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFF1D4ED8.toInt(), 0xFF7C3AED.toInt(), 0xFFDB2777.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.save()
        val clip = Path().apply { addRoundRect(rect, 26 * d, 26 * d, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawRect(rect, bg)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
        canvas.drawCircle(w * 0.92f, h * 0.08f, h * 0.55f, glow)
        canvas.drawCircle(w * 0.05f, h * 1.05f, h * 0.45f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18FFFFFF })
        canvas.restore()

        val pad = 22 * d
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = 12 * d; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.14f }
        canvas.drawText("NEXT PAYDAY", pad, pad + 10 * d, t)

        // money coming in, top right
        if (pay > 0) {
            val pill = "+${Store.money(pay, false)}"
            t.letterSpacing = 0f; t.textSize = 14 * d; t.color = 0xFFFFFFFF.toInt()
            val pw = t.measureText(pill) + 22 * d
            val pr = RectF(w - pad - pw, pad - 6 * d, w - pad, pad + 20 * d)
            canvas.drawRoundRect(pr, 13 * d, 13 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
            canvas.drawText(pill, pr.left + 11 * d, pr.bottom - 8 * d, t)
        }

        // huge day count
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 78 * d
        }
        val dayStr = if (days > 0) "$days" else "${hrs}h"
        val baseY = pad + 96 * d
        canvas.drawText(dayStr, pad - 4 * d, baseY, big)
        val bx = pad + big.measureText(dayStr) + 8 * d
        val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 24 * d; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText(if (days > 0) (if (days == 1L) "day" else "days") else "to go", bx, baseY - 30 * d, unit)
        unit.textSize = 15 * d; unit.typeface = Typeface.DEFAULT; unit.color = 0xD9FFFFFF.toInt()
        canvas.drawText("until payday", bx, baseY - 8 * d, unit)

        // live clock
        val clock = String.format(java.util.Locale.US, "%02dh  %02dm  %02ds", hrs, mins, secs)
        val ck = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 20 * d; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        val cw = ck.measureText(clock) + 24 * d
        val cr = RectF(pad, baseY + 14 * d, pad + cw, baseY + 50 * d)
        canvas.drawRoundRect(cr, 12 * d, 12 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF })
        canvas.drawText(clock, cr.left + 12 * d, cr.bottom - 11 * d, ck)
        val dateStr = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(java.util.Date(c.end))
        val dt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt(); textSize = 14 * d }
        if (cr.right + 14 * d + dt.measureText(dateStr) < w - pad) canvas.drawText(dateStr, cr.right + 14 * d, cr.bottom - 12 * d, dt)
        else canvas.drawText(dateStr, pad, cr.bottom + 22 * d, dt)

        // fortnight progress
        val frac = ((now - c.start).toDouble() / c.length).coerceIn(0.0, 1.0).toFloat()
        val by = h - pad - 4 * d
        val track = RectF(pad, by - 7 * d, w - pad, by)
        canvas.drawRoundRect(track, 4 * d, 4 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
        canvas.drawRoundRect(RectF(track.left, track.top, track.left + track.width() * frac.coerceAtLeast(0.03f), track.bottom), 4 * d, 4 * d,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = 11.5f * d }
        canvas.drawText("${(frac * 100).toInt()}% through the fortnight", pad, track.top - 8 * d, small)
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
            RingDraw.draw(Canvas(bmp), px.toFloat(), px.toFloat(), Store(ctx).view(), widget = true)

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

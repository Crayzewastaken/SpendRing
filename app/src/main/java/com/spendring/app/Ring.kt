package com.spendring.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.RemoteViews
import kotlin.math.min

object Colors {
    const val BG = 0xFF0E1514.toInt()
    const val SURFACE = 0xFF17211F.toInt()
    const val FIELD = 0xFF1F2B29.toInt()
    const val FG = 0xFFE7EFED.toInt()
    const val MUTED = 0xFF93A5A1.toInt()
    const val LINE = 0xFF283634.toInt()
    const val TRACK = 0xFF24302E.toInt()
    const val ACCENT = 0xFF7A9BFF.toInt()
    const val OK = 0xFF3FC39D.toInt()
    const val WARN = 0xFFF0A93A.toInt()
    const val BAD = 0xFFFF6B62.toInt()

    fun of(s: Summary.State) = when (s) {
        Summary.State.OVER -> BAD
        Summary.State.FAST -> WARN
        Summary.State.ON_TRACK -> OK
        Summary.State.NOT_SET -> MUTED
    }

    fun label(s: Summary.State) = when (s) {
        Summary.State.OVER -> "Over budget"
        Summary.State.FAST -> "Spending fast"
        Summary.State.ON_TRACK -> "On track"
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
        val stroke = size * (if (widget) 0.085f else 0.075f)
        val r = size / 2f - stroke / 2f - 2f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = Colors.TRACK
        }
        c.drawArc(oval, 0f, 360f, false, track)

        val frac = if (s.budget > 0) (s.left / s.budget).coerceIn(0.0, 1.0).toFloat() else 0f
        if (frac > 0f) {
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke; color = Colors.of(s.state)
                strokeCap = Paint.Cap.ROUND
            }
            c.drawArc(oval, -90f, 360f * frac, false, arc)
        }

        // Time marker: how far through the cycle you are (a small tick on the ring).
        if (s.cycle != null) {
            val ang = Math.toRadians((-90.0 + 360.0 * (1.0 - s.timeFrac)))
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.FG; strokeWidth = stroke * 0.18f; strokeCap = Paint.Cap.ROUND }
            val r1 = r - stroke * 0.75f
            val r2 = r + stroke * 0.75f
            c.drawLine(
                cx + (r1 * Math.cos(ang)).toFloat(), cy + (r1 * Math.sin(ang)).toFloat(),
                cx + (r2 * Math.cos(ang)).toFloat(), cy + (r2 * Math.sin(ang)).toFloat(), tick,
            )
        }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

        // Label
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = Colors.MUTED
        text.textSize = size * 0.058f
        text.letterSpacing = 0.12f
        c.drawText("LEFT TO SPEND", cx, cy - size * 0.13f, text)

        // Big number (shrinks to fit inside the ring)
        val big = if (s.state == Summary.State.NOT_SET) "—" else Store.money(s.left, s.left.let { kotlin.math.abs(it) < 10_000 })
        text.letterSpacing = 0f
        text.typeface = mono
        text.color = if (s.left < 0) Colors.BAD else Colors.FG
        text.textSize = size * 0.17f
        val maxW = r * 1.55f
        val tw = text.measureText(big)
        if (tw > maxW) text.textSize *= maxW / tw
        c.drawText(big, cx, cy + size * 0.045f, text)

        // "of $600"
        text.typeface = Typeface.DEFAULT
        text.color = Colors.MUTED
        text.textSize = size * 0.058f
        val sub = if (s.budget > 0) "of ${Store.money(s.budget, false)}" else "Tap to set up"
        c.drawText(sub, cx, cy + size * 0.13f, text)

        // State / days
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = Colors.of(s.state)
        text.textSize = size * 0.055f
        val st = if (widget && s.cycle != null) "${s.daysLeft}d to refill" else Colors.label(s.state)
        c.drawText(st, cx, cy + size * 0.215f, text)
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

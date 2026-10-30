package com.spendring.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Posts "you spent X, Y left" after each purchase and keeps the widget and open screen in sync. */
object Notifier {
    private const val CHANNEL = "purchases"

    /** The open dashboard, if any, refreshes through this. */
    @Volatile var onChange: (() -> Unit)? = null

    fun changed(ctx: Context) {
        RingWidget.updateAll(ctx)
        try { checkAlerts(ctx) } catch (_: Exception) {}
        val cb = onChange ?: return
        Handler(Looper.getMainLooper()).post { cb() }
    }

    private const val ALERTS = "alerts"

    /**
     * Once per week (or cycle) for each level: half the money gone, a quarter left, over budget.
     * Only the highest newly-reached level is announced.
     */
    @Synchronized
    fun checkAlerts(ctx: Context) {
        val store = Store(ctx)
        if (!store.budgetAlerts || !store.isSetUp) return
        val s = store.view()
        val cyc = s.cycle ?: return
        if (s.budget <= 0) return
        val usedFrac = s.spent / s.budget
        val level = when {
            s.left < 0 -> 3
            usedFrac >= 0.75 -> 2
            usedFrac >= 0.5 -> 1
            else -> 0
        }
        val key = "alertLevel_${cyc.start}"
        val done = store.sp.getInt(key, 0)
        if (level <= done) return
        store.sp.edit().putInt(key, level).apply()

        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(ALERTS, "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When this week's money is half gone, a quarter left, or over"
            }
        )
        val period = if (s.weekNo > 0) "this week's" else "this fortnight's"
        val days = "${s.daysLeft} ${if (s.daysLeft == 1) "day" else "days"} to go"
        val (title, body) = when (level) {
            1 -> "Halfway through $period money" to
                "${Store.money(s.left)} of ${Store.money(s.budget, false)} left · $days · ${Store.money(s.perDay)} a day"
            2 -> "Only a quarter left" to
                "${Store.money(s.left)} left of ${period.removeSuffix("'s")} money · $days · ${Store.money(s.perDay)} a day"
            else -> "Over budget" to
                "${Store.money(-s.left)} over ${period} money" + if (s.weekNo == 1) ". It comes out of next week." else "."
        }
        val color = when (level) { 1 -> Colors.ORANGE; else -> Colors.RED }
        val open = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(color)
            .build()
        try { nm.notify(7002, n) } catch (_: SecurityException) {}
    }

    fun afterPurchase(ctx: Context, t: Txn) {
        val store = Store(ctx)
        if (!store.notifyOnPurchase || !store.isSetUp) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "After each purchase", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows how much you have left after Google Wallet charges your card"
            }
        )
        val s = store.view()
        val title = if (t.amount < 0) "Refund ${Store.money(-t.amount)} from ${t.merchant}"
        else "${Store.money(t.amount)} at ${t.merchant}"
        val days = if (s.weekNo > 0) "${s.daysLeft} ${if (s.daysLeft == 1) "day" else "days"} left this week"
        else "${s.daysLeft} ${if (s.daysLeft == 1) "day" else "days"} to payday"
        val body = if (t.cat == Store.FUEL) {
            val fl = s.fuelBudget - s.fuelSpent
            (if (fl >= 0) "${Store.money(fl)} fuel money left" else "${Store.money(-fl)} over the fuel budget") + " · $days"
        } else {
            val wk = if (s.weekNo > 0) " this week" else ""
            val left = if (s.left >= 0) "${Store.money(s.left)} left$wk" else "${Store.money(-s.left)} over$wk"
            "$left · $days · ${Store.money(s.perDay, false)}/day"
        }

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(0xFF7A9BFF.toInt())
            .build()
        try {
            nm.notify(7001, n)
        } catch (_: SecurityException) {
        }
    }
}

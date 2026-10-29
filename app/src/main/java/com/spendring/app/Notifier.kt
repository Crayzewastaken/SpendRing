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
        val cb = onChange ?: return
        Handler(Looper.getMainLooper()).post { cb() }
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
        val s = store.summary()
        val title = if (t.amount < 0) "Refund ${Store.money(-t.amount)} from ${t.merchant}"
        else "${Store.money(t.amount)} at ${t.merchant}"
        val days = "${s.daysLeft} ${if (s.daysLeft == 1) "day" else "days"} to payday"
        val body = if (t.cat == Store.FUEL) {
            val fl = s.fuelBudget - s.fuelSpent
            (if (fl >= 0) "${Store.money(fl)} fuel money left" else "${Store.money(-fl)} over the fuel budget") + " · $days"
        } else {
            val left = if (s.left >= 0) "${Store.money(s.left)} left" else "${Store.money(-s.left)} over budget"
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

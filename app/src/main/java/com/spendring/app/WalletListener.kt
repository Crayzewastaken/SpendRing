package com.spendring.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Reads Google Wallet payment notifications as they arrive and records each purchase. */
class WalletListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Pick up any payment notifications still in the shade from while the app wasn't listening.
        try {
            activeNotifications?.forEach { handle(applicationContext, it) }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            handle(applicationContext, sbn)
        } catch (_: Exception) {
        }
    }

    companion object {
        fun handle(ctx: Context, sbn: StatusBarNotification) {
            val store = Store(ctx)
            val pkg = sbn.packageName ?: return
            if (pkg !in store.watchSet()) return
            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val ex = n.extras
            val title = (ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
                .ifBlank { ex?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString() ?: "" }
            val big = ex?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val lines = ex?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() } ?: ""
            val text = big.ifBlank { ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "" }.ifBlank { lines }
                .ifBlank { n.tickerText?.toString() ?: "" }
            val sub = ex?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            if (title.isBlank() && text.isBlank()) return

            val whenTs = if (n.`when` > 0) n.`when` else sbn.postTime
            val fp = "$pkg|${sbn.key}|$whenTs|$title|$text"
            if (!store.markSeen(fp)) return

            val body = if (sub.isNotBlank()) "$text\n$sub" else text
            ingest(ctx, store, pkg, title, body, whenTs, strict = pkg != Store.WALLET)
        }

        /** Shared by the listener and the in-app "test a notification" box. */
        fun ingest(ctx: Context, store: Store, pkg: String, title: String, text: String, ts: Long, strict: Boolean): String {
            val result = when (val o = Parser.parse(title, text, strict)) {
                is Parser.Outcome.Skip -> "Skipped: ${o.reason}"
                is Parser.Outcome.Purchase -> {
                    val r = o.r
                    if (store.isDuplicate(r.merchant, r.amount, ts, "wallet:$pkg")) {
                        "Skipped: already recorded"
                    } else {
                        val t = Txn(
                            id = Store.newId(), merchant = r.merchant, amount = r.amount,
                            cat = store.guessCategory(r.merchant), ts = ts, source = "wallet:$pkg",
                            raw = "$title\n$text".trim(),
                        )
                        store.add(t)
                        Notifier.afterPurchase(ctx, t)
                        "Added ${Store.money(r.amount)} at ${r.merchant}"
                    }
                }
            }
            store.log(pkg, title, text, result)
            Notifier.changed(ctx)
            return result
        }

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
            val me = ComponentName(ctx, WalletListener::class.java).flattenToString()
            return flat.split(":").any { it == me } || flat.contains(ctx.packageName)
        }
    }
}

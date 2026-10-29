package com.spendring.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Three tabs: Main (the ring and four numbers), Budget (settings, purchases, add), Settings. */
class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var pages: List<ScrollView>
    private val tabViews = ArrayList<TextView>()
    private var tab = 0

    // Main
    private lateinit var ring: RingView
    private lateinit var accessBox: LinearLayout
    private lateinit var statLeft: TextView
    private lateinit var statSpent: TextView
    private lateinit var statDays: TextView
    private lateinit var statRefill: TextView

    // Budget
    private lateinit var budgetField: EditText
    private lateinit var dateBtn: TextView
    private val cycleBtns = LinkedHashMap<String, TextView>()
    private var pendingCycle = "fortnightly"
    private var pendingDate = ""
    private lateinit var budgetSummary: TextView
    private lateinit var catBox: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var pastBox: LinearLayout

    // Settings
    private lateinit var logBox: LinearLayout
    private lateinit var accessStatus: TextView
    private lateinit var batteryStatus: TextView

    private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Colors.BG) }
        val content = FrameLayout(this)
        pages = listOf(buildMain(), buildBudget(), buildSettings())
        pages.forEach { content.addView(it) }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(Colors.LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        root.addView(buildTabBar())
        setContentView(root)

        handleTestIntent(intent)
        loadBudgetFields()
        show(if (store.isSetUp) 0 else 1)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handleTestIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Notifier.onChange = { refresh() }
        // If Android dropped the connection to the notification reader, ask it to reconnect.
        if (WalletListener.isEnabled(this)) {
            try { NotificationListenerService.requestRebind(ComponentName(this, WalletListener::class.java)) } catch (_: Exception) {}
        }
        refresh()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !store.sp.getBoolean("askedNotif", false)
        ) {
            store.sp.edit().putBoolean("askedNotif", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onPause() {
        super.onPause()
        Notifier.onChange = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab != 0 && store.isSetUp) show(0) else super.onBackPressed()
    }

    /** Used by the automated emulator test to feed in sample Wallet notifications. */
    private fun handleTestIntent(i: Intent) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val tabExtra = i.getIntExtra("sr_tab", -1)
        val text = i.getStringExtra("sr_text")
        if (text != null) {
            val title = i.getStringExtra("sr_title") ?: ""
            val ago = i.getIntExtra("sr_hours_ago", 0)
            val ts = System.currentTimeMillis() - ago * 3_600_000L
            WalletListener.ingest(this, store, "test", title, text, ts, strict = false)
            i.removeExtra("sr_text")
        }
        if (tabExtra in 0..2 && ::pages.isInitialized) show(tabExtra)
    }

    // ================================================================== tabs

    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Colors.SURFACE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        listOf("◔" to "Main", "＄" to "Budget", "⚙" to "Settings").forEachIndexed { i, (icon, name) ->
            val t = TextView(this).apply {
                text = "$icon\n$name"
                textSize = 12.5f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setLineSpacing(0f, 1.1f)
                setPadding(0, dp(8), 0, dp(8))
                contentDescription = name
                setOnClickListener { show(i) }
            }
            tabViews.add(t)
            bar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar
    }

    private fun show(i: Int) {
        if (i != 1 && !store.isSetUp && i != 2) { toast("Save your budget first"); return }
        tab = i
        pages.forEachIndexed { j, p -> p.visibility = if (j == i) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { j, t ->
            val on = j == i
            t.setTextColor(if (on) Colors.ACCENT else Colors.MUTED)
            t.background = if (on) rounded(Colors.FIELD, 12f) else null
        }
        if (i == 1) loadBudgetFields()
        refresh()
    }

    // ================================================================== MAIN

    private fun buildMain(): ScrollView {
        val col = column()
        col.addView(label("Spend Ring", 26f, Colors.FG, true))

        accessBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(accessBox, lp(12))

        ring = RingView(this)
        ring.setOnClickListener { if (!store.isSetUp) show(1) }
        col.addView(ring, lp(16))

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r1 = row()
        statLeft = bigStat(r1, "LEFT TO SPEND", 0)
        statSpent = bigStat(r1, "SPENT", 10)
        val r2 = row()
        statDays = bigStat(r2, "DAYS LEFT", 0)
        statRefill = bigStat(r2, "REFILL IN", 10)
        grid.addView(r1)
        grid.addView(r2, lp(10))
        col.addView(grid, lp(20))

        col.addView(label("Everything stays on this phone. No internet, no accounts.", 12f, Colors.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(8))
        })
        return scroll(col)
    }

    private fun bigStat(parent: LinearLayout, title: String, leftMarginDp: Int): TextView {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Colors.SURFACE, 16f, Colors.LINE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        c.addView(label(title, 11f, Colors.MUTED, true).apply { letterSpacing = 0.09f })
        val v = label("–", 24f, Colors.FG, true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setPadding(0, dp(6), 0, 0)
            maxLines = 1
        }
        c.addView(v)
        parent.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(leftMarginDp) })
        return v
    }

    // ================================================================== BUDGET

    private fun buildBudget(): ScrollView {
        val col = column()
        val top = row()
        top.addView(label("Budget", 26f, Colors.FG, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(label("+  Add", 15f, Colors.BG, true).apply {
            background = rounded(Colors.ACCENT, 20f)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            contentDescription = "Add a purchase"
            setOnClickListener { editDialog(null) }
        })
        col.addView(top)

        budgetSummary = label("", 14f, Colors.MUTED).apply { setPadding(0, dp(6), 0, 0) }
        col.addView(budgetSummary)

        col.addView(section("YOUR BUDGET"))
        val b = card()
        b.addView(label("Budget for each cycle (AUD)", 13f, Colors.MUTED))
        budgetField = field("e.g. 600", "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        b.addView(budgetField, lp(6))
        b.addView(label("Money comes in every", 13f, Colors.MUTED).apply { setPadding(0, dp(16), 0, 0) })
        val cyc = row()
        listOf("weekly" to "Week", "fortnightly" to "Fortnight", "monthly" to "Month").forEachIndexed { i, (k, v) ->
            val t = label(v, 15f, Colors.FG, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(11), 0, dp(11))
                setOnClickListener { pendingCycle = k; paintCycle() }
            }
            cycleBtns[k] = t
            cyc.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        b.addView(cyc, lp(6))
        b.addView(label("Next refill date", 13f, Colors.MUTED).apply { setPadding(0, dp(16), 0, 0) })
        dateBtn = label("Pick a date", 16f, Colors.FG, true).apply {
            background = rounded(Colors.FIELD, 10f); setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { pickRefillDate() }
        }
        b.addView(dateBtn, lp(6))
        b.addView(button("Save budget", Colors.ACCENT, Colors.BG) { saveBudget() }, lp(16))
        col.addView(b, lp(8))

        col.addView(button("+  Add a purchase", Colors.SURFACE, Colors.ACCENT) { editDialog(null) }, lp(16))

        col.addView(section("WHERE IT WENT"))
        catBox = card()
        col.addView(catBox, lp(8))

        col.addView(section("PURCHASES THIS CYCLE"))
        col.addView(label("Tap one to change it or delete it.", 12.5f, Colors.MUTED).apply { setPadding(dp(2), dp(4), 0, 0) })
        listBox = card(pad = 0)
        col.addView(listBox, lp(8))

        col.addView(section("PAST CYCLES"))
        pastBox = card()
        col.addView(pastBox, lp(8))
        return scroll(col)
    }

    private fun loadBudgetFields() {
        budgetField.setText(if (store.budget > 0) trimNum(store.budget) else "")
        pendingCycle = store.cycleKind
        if (store.isSetUp) store.currentCycle()
        pendingDate = store.nextRefill
        paintCycle(); paintDate()
    }

    private fun paintCycle() {
        cycleBtns.forEach { (k, v) ->
            val on = k == pendingCycle
            v.background = rounded(if (on) Colors.ACCENT else Colors.FIELD, 10f)
            v.setTextColor(if (on) Colors.BG else Colors.FG)
        }
    }

    private fun paintDate() {
        val c = Store.parseDate(pendingDate)
        dateBtn.text = if (c != null) SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault()).format(c.time) else "Pick a date"
        dateBtn.setTextColor(if (c != null) Colors.FG else Colors.MUTED)
    }

    private fun pickRefillDate() {
        val c = Store.parseDate(pendingDate) ?: Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 7) }
        DatePickerDialog(this, { _, y, m, d ->
            val cal = Calendar.getInstance().apply { set(y, m, d) }
            pendingDate = Store.DATE.format(cal.time)
            paintDate()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun saveBudget() {
        val v = budgetField.text.toString().replace(",", "").replace("$", "").trim().toDoubleOrNull()
        if (v == null || v <= 0) { budgetField.error = "Enter a budget above \$0"; return }
        if (pendingDate.isEmpty()) { toast("Pick your next refill date"); return }
        val first = !store.isSetUp
        store.budget = v
        store.cycleKind = pendingCycle
        store.nextRefill = pendingDate
        Notifier.changed(this)
        toast("Budget saved")
        loadBudgetFields()
        if (first) show(0) else refresh()
    }

    // ================================================================== SETTINGS

    private fun buildSettings(): ScrollView {
        val col = column()
        col.addView(label("Settings", 26f, Colors.FG, true))

        col.addView(section("READING GOOGLE WALLET"))
        val n = card()
        accessStatus = label("", 15f, Colors.FG, true)
        n.addView(accessStatus)
        n.addView(label("Spend Ring logs a purchase every time Google Wallet shows a payment notification. Keep Wallet's notifications switched on.", 13f, Colors.MUTED), lp(6))
        n.addView(button("Open notification access", Colors.FIELD, Colors.FG) { openListenerSettings() }, lp(10))
        batteryStatus = label("", 15f, Colors.FG, true).apply { setPadding(0, dp(18), 0, 0) }
        n.addView(batteryStatus)
        n.addView(label("Some phones (Samsung especially) stop apps in the background, which can make it miss a purchase. Allow it to run unrestricted.", 13f, Colors.MUTED), lp(6))
        n.addView(button("Allow running in the background", Colors.FIELD, Colors.FG) { askBattery() }, lp(10))
        val notifySw = Switch(this).apply {
            text = "After each purchase, show how much is left"
            setTextColor(Colors.FG); textSize = 15f
            isChecked = store.notifyOnPurchase
            setOnCheckedChangeListener { _, on -> store.notifyOnPurchase = on }
        }
        n.addView(notifySw, lp(18))
        col.addView(n, lp(8))

        col.addView(section("RECENT WALLET NOTIFICATIONS"))
        col.addView(label("Every payment notification and what Spend Ring did with it.", 12.5f, Colors.MUTED).apply { setPadding(dp(2), dp(4), 0, 0) })
        logBox = card()
        col.addView(logBox, lp(8))

        col.addView(section("TRY A NOTIFICATION"))
        val tcard = card()
        tcard.addView(label("Type the text of a Wallet notification to check it reads it right. It's added as a real purchase, so delete it afterwards if it was only a test.", 13f, Colors.MUTED))
        val tTitle = field("Title, e.g. Woolworths", "")
        val tText = field("Text, e.g. \$23.45 with Visa ••1234", "")
        tcard.addView(tTitle, lp(8))
        tcard.addView(tText, lp(8))
        tcard.addView(button("Read it", Colors.FIELD, Colors.FG) {
            val res = WalletListener.ingest(this, store, "typed in", tTitle.text.toString(), tText.text.toString(), System.currentTimeMillis(), strict = false)
            toast(res)
            tTitle.setText(""); tText.setText("")
            refresh()
        }, lp(10))
        col.addView(tcard, lp(8))

        col.addView(section("ADVANCED"))
        val adv = card()
        adv.addView(label("Apps to read, one per line. Google Wallet is the main one. You can add your bank app's package name if it also sends purchase alerts; the same purchase from two apps is only counted once.", 13f, Colors.MUTED))
        val apps = field("", store.watchApps, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 2; textSize = 13f; typeface = Typeface.MONOSPACE
        }
        adv.addView(apps, lp(8))
        adv.addView(button("Save apps", Colors.FIELD, Colors.FG) {
            store.watchApps = apps.text.toString().trim().ifEmpty { Store.DEFAULT_APPS }
            apps.setText(store.watchApps); toast("Saved")
        }, lp(10))
        adv.addView(button("Delete all purchases", Colors.FIELD, Colors.BAD) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Delete all purchases?")
                .setMessage("This removes every purchase from Spend Ring. Your budget stays.")
                .setPositiveButton("Delete all") { _, _ ->
                    store.sp.edit().putString("txns", "[]").commit()
                    Notifier.changed(this); refresh(); toast("All purchases deleted")
                }
                .setNegativeButton("Cancel", null).show()
        }, lp(16))
        col.addView(adv, lp(8))

        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        col.addView(label("Spend Ring $ver · works offline", 12f, Colors.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(8))
        })
        return scroll(col)
    }

    private fun isBatteryFree(): Boolean =
        (getSystemService(POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(packageName) ?: false

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun openListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
        toast("Find Spend Ring and turn it on")
    }

    // ================================================================== refresh

    private fun refresh() {
        if (!::ring.isInitialized) return
        val s = store.summary()
        val listening = WalletListener.isEnabled(this)

        // ---- Main
        ring.summary = s
        accessBox.removeAllViews()
        if (!listening) {
            accessBox.addView(warnCard(
                "Turn on notification access",
                "Spend Ring needs to see your Google Wallet notifications to log every purchase by itself.",
                "Turn on",
            ) { openListenerSettings() })
        } else if (!store.isSetUp) {
            accessBox.addView(warnCard("Set your budget", "Add your budget and next refill date to start.", "Set up") { show(1) })
        }
        statLeft.text = if (s.cycle != null) Store.money(s.left, false) else "–"
        statLeft.setTextColor(if (s.left < 0) Colors.BAD else Colors.of(s.state).let { if (s.state == Summary.State.NOT_SET) Colors.FG else it })
        statSpent.text = Store.money(s.spent, false)
        val cyc = s.cycle
        statDays.text = if (cyc != null) "${s.daysLeft}" else "–"
        statRefill.text = if (cyc != null) dayFmt.format(Date(cyc.end)) else "–"

        // ---- Budget
        budgetSummary.text = if (cyc != null)
            "${dayFmt.format(Date(cyc.start))} → ${dayFmt.format(Date(cyc.end))}  ·  ${Store.money(s.perDay, false)} a day left"
        else "Set your budget below to start."

        catBox.removeAllViews()
        val byCat = s.txns.groupBy { it.cat }.mapValues { e -> e.value.sumOf { it.amount } }
            .filter { it.value > 0.004 }.toList().sortedByDescending { it.second }
        if (byCat.isEmpty()) {
            catBox.addView(label("Nothing spent yet this cycle.", 14f, Colors.MUTED))
        } else {
            val top = byCat.first().second
            val total = byCat.sumOf { it.second }
            byCat.forEachIndexed { i, (cat, v) ->
                val r = row()
                r.addView(dot(Store.colorOf(cat)))
                r.addView(label(cat, 15f, Colors.FG, true).apply { setPadding(dp(8), 0, 0, 0) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(label(Store.money(v), 15f, Colors.FG, true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
                r.addView(label("${(v / total * 100).toInt()}%", 12.5f, Colors.MUTED).apply {
                    setPadding(dp(8), 0, 0, 0); minWidth = dp(36); gravity = Gravity.END
                })
                catBox.addView(r, if (i == 0) lp(0) else lp(12))
                catBox.addView(bar((v / top).toFloat(), Store.colorOf(cat)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)).apply { topMargin = dp(6) })
            }
        }

        listBox.removeAllViews()
        if (s.txns.isEmpty()) {
            listBox.addView(label(
                if (listening) "No purchases yet. Your next Google Wallet tap will show up here." else "No purchases yet.",
                14f, Colors.MUTED,
            ).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) })
        } else {
            s.txns.forEachIndexed { i, t ->
                if (i > 0) listBox.addView(View(this).apply { setBackgroundColor(Colors.LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                listBox.addView(txnRow(t))
            }
        }

        pastBox.removeAllViews()
        val past = store.pastCycles(4).map { it to store.spentIn(it) }
        if (cyc == null || past.all { it.second == 0.0 }) {
            pastBox.addView(label("Your previous cycles will show here.", 14f, Colors.MUTED))
        } else {
            past.forEachIndexed { i, (c, spent) ->
                val r = row()
                r.addView(label("${dayFmt.format(Date(c.start))} – ${dayFmt.format(Date(c.end - 1))}", 14f, Colors.FG),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val diff = s.budget - spent
                r.addView(label(Store.money(spent, false), 14f, Colors.FG, true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
                r.addView(label(if (diff >= 0) "  ${Store.money(diff, false)} saved" else "  ${Store.money(-diff, false)} over", 12.5f,
                    if (diff >= 0) Colors.OK else Colors.BAD))
                pastBox.addView(r, if (i == 0) lp(0) else lp(10))
            }
        }

        // ---- Settings
        accessStatus.text = if (listening) "✓  Notification access is on" else "Notification access is off"
        accessStatus.setTextColor(if (listening) Colors.OK else Colors.WARN)
        val free = isBatteryFree()
        batteryStatus.text = if (free) "✓  Allowed to run in the background" else "Background running is restricted"
        batteryStatus.setTextColor(if (free) Colors.OK else Colors.WARN)

        logBox.removeAllViews()
        val log = store.logEntries()
        if (log.isEmpty()) {
            logBox.addView(label("None yet. When Google Wallet shows a payment, it appears here.", 13f, Colors.MUTED))
        } else {
            log.take(20).forEachIndexed { i, e ->
                val added = e.optString("result").startsWith("Added")
                val t = Date(e.optLong("ts"))
                logBox.addView(label("${dayFmt.format(t)}, ${timeFmt.format(t)}", 11.5f, Colors.MUTED), if (i == 0) lp(0) else lp(12))
                logBox.addView(label("${e.optString("title")}  ${e.optString("text")}".trim(), 13.5f, Colors.FG).apply { maxLines = 3 })
                logBox.addView(label(e.optString("result"), 12.5f, if (added) Colors.OK else Colors.WARN, true))
            }
        }
    }

    private fun txnRow(t: Txn): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Colors.LINE), null, null)
            setOnClickListener { editDialog(t) }
        }
        val r1 = row()
        r1.addView(label(t.merchant, 15.5f, Colors.FG, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r1.addView(label(Store.money(t.amount), 15.5f, if (t.amount < 0) Colors.OK else Colors.FG, true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setPadding(dp(10), 0, 0, 0)
        })
        box.addView(r1)
        val r2 = row()
        r2.addView(dot(Store.colorOf(t.cat), 8))
        val src = if (t.source.startsWith("wallet")) "Wallet" else "Added by you"
        r2.addView(label("${t.cat}  ·  ${dayFmt.format(Date(t.ts))}, ${timeFmt.format(Date(t.ts))}  ·  $src", 12.5f, Colors.MUTED).apply {
            setPadding(dp(6), 0, 0, 0)
        })
        box.addView(r2, lp(3))
        return box
    }

    // ================================================================== ADD / EDIT

    private fun editDialog(existing: Txn?) {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val shop = field("Shop", existing?.merchant ?: "")
        val amount = field("Amount", existing?.let { String.format(Locale.US, "%.2f", kotlin.math.abs(it.amount)) } ?: "",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val cats = Store.CATEGORIES.map { it.name }
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cats)
        spinner.setSelection(cats.indexOf(existing?.cat ?: "Other").coerceAtLeast(0))
        val refund = Switch(this).apply {
            text = "This is a refund (money back)"
            setTextColor(Colors.FG)
            isChecked = (existing?.amount ?: 0.0) < 0
        }
        val cal = Calendar.getInstance().apply { timeInMillis = existing?.ts ?: System.currentTimeMillis() }
        val dateBtnLocal = label("", 15f, Colors.ACCENT, true).apply { setPadding(0, dp(12), 0, dp(12)) }
        fun showDate() { dateBtnLocal.text = "${dayFmt.format(cal.time)}, ${timeFmt.format(cal.time)}  (change)" }
        showDate()
        dateBtnLocal.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d -> cal.set(y, m, d); showDate() },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        // Auto-pick a category as the shop name is typed (new purchases only).
        if (existing == null) shop.setOnFocusChangeListener { _, has ->
            if (!has && shop.text.isNotBlank()) spinner.setSelection(cats.indexOf(store.guessCategory(shop.text.toString())).coerceAtLeast(0))
        }

        wrap.addView(shop)
        wrap.addView(amount, lp(8))
        wrap.addView(label("Category", 12.5f, Colors.MUTED).apply { setPadding(0, dp(12), 0, 0) })
        wrap.addView(spinner)
        wrap.addView(refund, lp(8))
        wrap.addView(dateBtnLocal)
        if (existing != null && existing.raw.isNotBlank()) {
            wrap.addView(label("From the notification:\n${existing.raw}", 12f, Colors.MUTED).apply { setPadding(0, dp(4), 0, dp(8)) })
        }

        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (existing == null) "Add a purchase" else "Edit purchase")
            .setView(ScrollView(this).apply { addView(wrap) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
        if (existing != null) b.setNeutralButton("Delete", null)
        val dlg = b.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = amount.text.toString().replace(",", "").replace("$", "").trim().toDoubleOrNull()
                if (a == null || a <= 0) { amount.error = "Enter an amount above \$0"; return@setOnClickListener }
                val name = shop.text.toString().trim().ifEmpty { "Purchase" }
                val cat = cats[spinner.selectedItemPosition.coerceIn(0, cats.size - 1)]
                val signed = if (refund.isChecked) -a else a
                if (existing == null) {
                    store.add(Txn(Store.newId(), name, signed, cat, cal.timeInMillis, "manual", ""))
                    toast("Added ${Store.money(signed)}")
                } else {
                    store.replace(existing.copy(merchant = name, amount = signed, cat = cat, ts = cal.timeInMillis))
                    toast("Saved")
                }
                if (existing == null || existing.cat != cat) store.rememberCategory(name, cat)
                Notifier.changed(this)
                refresh()
                dlg.dismiss()
            }
            if (existing != null) dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("Delete ${Store.money(existing.amount)} at ${existing.merchant}?")
                    .setPositiveButton("Delete") { _, _ ->
                        store.remove(existing.id); Notifier.changed(this); refresh(); dlg.dismiss(); toast("Deleted")
                    }
                    .setNegativeButton("Keep", null).show()
            }
        }
        dlg.show()
    }

    // ================================================================== small view helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private fun lp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(20), dp(18), dp(28))
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun scroll(v: View) = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(Colors.BG)
        addView(v)
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun section(t: String) = label(t, 12.5f, Colors.MUTED, true).apply {
        letterSpacing = 0.1f; setPadding(dp(2), dp(26), 0, 0)
    }

    private fun rounded(color: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(radiusDp)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun card(pad: Int = 16) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Colors.SURFACE, 16f, Colors.LINE)
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        clipToOutline = true
    }

    private fun pill(t: String, onClick: () -> Unit) = label(t, 14f, Colors.FG, true).apply {
        background = rounded(Colors.SURFACE, 20f, Colors.LINE)
        setPadding(dp(16), dp(9), dp(16), dp(9))
        setOnClickListener { onClick() }
    }

    private fun button(t: String, bg: Int, fg: Int, onClick: () -> Unit) = label(t, 16f, fg, true).apply {
        gravity = Gravity.CENTER
        background = rounded(bg, 12f, if (bg == Colors.SURFACE) Colors.LINE else null)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = type
        setTextColor(Colors.FG); setHintTextColor(Colors.MUTED); textSize = 16f
        background = rounded(Colors.FIELD, 10f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun statCell(parent: LinearLayout, title: String): TextView {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        c.addView(label(title, 10.5f, Colors.MUTED, true).apply { letterSpacing = 0.08f; gravity = Gravity.CENTER })
        val v = label("–", 18f, Colors.FG, true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
        }
        c.addView(v)
        parent.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return v
    }

    private fun dot(color: Int, sizeDp: Int = 10) = View(this).apply {
        background = rounded(color, 3f)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun bar(frac: Float, color: Int): View {
        val track = LinearLayout(this).apply { background = rounded(Colors.TRACK, 5f); orientation = LinearLayout.HORIZONTAL }
        val fill = View(this).apply { background = rounded(color, 5f) }
        val f = frac.coerceIn(0.02f, 1f)
        track.weightSum = 1f
        track.addView(fill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, f))
        return track
    }

    private fun warnCard(title: String, body: String, action: String, onClick: () -> Unit): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Colors.SURFACE, 16f, Colors.WARN)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        c.addView(label(title, 16f, Colors.WARN, true))
        c.addView(label(body, 13.5f, Colors.FG), lp(4))
        c.addView(button(action, Colors.WARN, Colors.BG, onClick), lp(10))
        return c
    }

    private fun trimNum(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

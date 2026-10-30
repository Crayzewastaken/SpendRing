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
    private lateinit var greeting: TextView
    private lateinit var safeChip: TextView
    private lateinit var paceHint: TextView
    private lateinit var paydayView: PaydayView
    private lateinit var dayBars: DayBars
    private lateinit var dayCard: LinearLayout
    private lateinit var recentBox: LinearLayout
    private lateinit var savingsCard: LinearLayout
    private lateinit var savingsView: SavingsView
    private val tabColors = listOf(Colors.GREEN, Colors.BLUE, Colors.PURPLE)

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
    private lateinit var planBox: LinearLayout
    private lateinit var fuelBox: LinearLayout

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
        val tabBar = buildTabBar()
        root.addView(tabBar)
        setContentView(root)
        fitSystemBars(root, content, tabBar)

        handleTestIntent(intent)
        loadBudgetFields()
        show(0)
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
        Updater.autoCheck(this)
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
        i.getStringExtra("sr_plan")?.let { Plan.importCode(this, it); i.removeExtra("sr_plan"); loadBudgetFields() }
        i.getStringExtra("sr_purchases")?.let { store.importPurchases(it)?.let { r -> toast("Imported ${r.first}, skipped ${r.second}") }; i.removeExtra("sr_purchases") }
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

    /**
     * Draw behind the status bar and Samsung's navigation buttons (Android 15 does this anyway),
     * then pad the content and tab bar so nothing sits underneath them.
     */
    private fun fitSystemBars(root: View, content: View, tabBar: View) {
        val baseBottom = tabBar.paddingBottom
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Colors.SURFACE
        }
        root.setOnApplyWindowInsetsListener { _, ins ->
            val v = if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                intArrayOf(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                intArrayOf(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            }
            val l = v[0]; val t = v[1]; val r = v[2]; val b = v[3]
            content.setPadding(l, t, r, 0)
            tabBar.setPadding(dp(8) + l, tabBar.paddingTop, dp(8) + r, baseBottom + b)
            ins
        }
        root.requestApplyInsets()
    }

    // ================================================================== tabs

    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Colors.SURFACE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        listOf("◉" to "Main", "＄" to "Budget", "⚙" to "Settings").forEachIndexed { i, (icon, name) ->
            val t = TextView(this).apply {
                text = "$icon\n$name"
                textSize = 13f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setLineSpacing(0f, 1.1f)
                setPadding(0, dp(10), 0, dp(10))
                contentDescription = name
                setOnClickListener { show(i) }
            }
            tabViews.add(t)
            bar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        return bar
    }

    private fun show(i: Int) {
        tab = i
        pages.forEachIndexed { j, p -> p.visibility = if (j == i) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { j, t ->
            val on = j == i
            t.setTextColor(if (on) tabColors[j] else Colors.MUTED)
            t.background = if (on) rounded(Colors.alpha(tabColors[j], 0x26), 16f) else null
        }
        if (i == 1) loadBudgetFields()
        refresh()
    }

    // ================================================================== MAIN

    private fun buildMain(): ScrollView {
        val col = column()
        col.addView(label("Spend Ring", 28f, Colors.FG, true))
        greeting = label("", 14f, Colors.MUTED).apply { setPadding(0, dp(2), 0, 0) }
        col.addView(greeting)

        accessBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(accessBox, lp(12))

        ring = RingView(this)
        ring.setOnClickListener { if (!store.isSetUp) show(1) }
        col.addView(ring, lp(12))

        safeChip = label("", 15f, Colors.GREEN, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        col.addView(safeChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10)
        })
        paceHint = label("", 13f, Colors.ORANGE).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        paceHint.visibility = View.GONE
        paydayView = PaydayView(this)
        col.addView(paydayView, lp(18))

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r1 = row()
        statLeft = bigStat(r1, "LEFT TO SPEND", 0, Colors.GREEN)
        statSpent = bigStat(r1, "SPENT", 10, Colors.PINK)
        val r2 = row()
        statDays = bigStat(r2, "DAYS LEFT", 0, Colors.BLUE)
        statRefill = bigStat(r2, "REFILL ON", 10, Colors.YELLOW)
        grid.addView(r1)
        grid.addView(r2, lp(10))
        grid.visibility = View.GONE   // the four number tiles are kept off the home screen
        col.addView(grid, lp(0))

        col.addView(section("WHAT YOU'RE SAVING"))
        savingsCard = card()
        savingsView = SavingsView(this)
        savingsCard.addView(savingsView)
        savingsCard.setOnClickListener { show(1) }
        col.addView(savingsCard, lp(8))

        col.addView(section("DAY BY DAY"))
        dayCard = card()
        dayBars = DayBars(this)
        dayCard.addView(dayBars)
        dayCard.addView(label("Dashed line is what you can spend each day. Green is under it, orange up to double, red over that.", 12f, Colors.MUTED), lp(8))
        col.addView(dayCard, lp(8))

        col.addView(section("LATEST"))
        recentBox = card(pad = 0)
        col.addView(recentBox, lp(8))

        col.addView(label("Your money data stays on this phone. No accounts.", 12f, Colors.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(8))
        })
        return scroll(col)
    }

    private fun bigStat(parent: LinearLayout, title: String, leftMarginDp: Int, color: Int): TextView {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Colors.alpha(color, 0x1F), 18f, Colors.alpha(color, 0x55))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        c.addView(label(title, 11f, color, true).apply { letterSpacing = 0.09f })
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

        col.addView(section("SPENDING MONEY"))
        val b = card()
        b.addView(label("For the card each cycle (AUD). Fuel has its own allowance below.", 13f, Colors.MUTED))
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
        b.addView(label("Next refill (payday)", 13f, Colors.MUTED).apply { setPadding(0, dp(16), 0, 0) })
        dateBtn = label("Pick a date", 16f, Colors.FG, true).apply {
            background = rounded(Colors.FIELD, 10f); setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { pickRefillDate() }
        }
        b.addView(dateBtn, lp(6))
        b.addView(Switch(this).apply {
            text = "Show it week by week (half each week, leftovers roll over)"
            setTextColor(Colors.FG); textSize = 15f
            isChecked = store.weekly
            setOnCheckedChangeListener { _, on -> store.weekly = on; Notifier.changed(this@MainActivity); refresh() }
        }, lp(16))
        b.addView(button("Save budget", Colors.ACCENT, Colors.BG) { saveBudget() }, lp(16))
        col.addView(b, lp(8))

        col.addView(button("+  Add a purchase", Colors.SURFACE, Colors.ACCENT) { editDialog(null) }, lp(16))
        col.addView(button("Import purchases from a code", Colors.SURFACE, Colors.PURPLE) { importDialog() }, lp(10))

        col.addView(section("FUEL"))
        fuelBox = card()
        col.addView(fuelBox, lp(8))

        col.addView(section("YOUR PLAN"))
        planBox = card()
        col.addView(planBox, lp(8))

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
        n.addView(Switch(this).apply {
            text = "Alert me at halfway, a quarter left, and over budget"
            setTextColor(Colors.FG); textSize = 15f
            isChecked = store.budgetAlerts
            setOnCheckedChangeListener { _, on -> store.budgetAlerts = on }
        }, lp(14))
        col.addView(n, lp(8))

        col.addView(section("UPDATES"))
        val up = card()
        up.addView(label("You have build ${Updater.currentBuild(this)}", 15f, Colors.FG, true))
        up.addView(label("When a new version is out, Spend Ring downloads it by itself and Android asks you to tap Install. Your purchases and budget are kept. Checking is the only thing it uses the internet for.", 13f, Colors.MUTED), lp(6))
        up.addView(Switch(this).apply {
            text = "Update automatically"
            setTextColor(Colors.FG); textSize = 15f
            isChecked = store.sp.getBoolean("autoUpdate", true)
            setOnCheckedChangeListener { _, on -> store.sp.edit().putBoolean("autoUpdate", on).apply() }
        }, lp(12))
        up.addView(button("Check for updates now", Colors.FIELD, Colors.FG) { Updater.check(this) }, lp(12))
        col.addView(up, lp(8))

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
        col.addView(label("Spend Ring $ver", 12f, Colors.MUTED).apply {
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
        val w = store.view()   // this week (or the whole cycle if weekly view is off)
        val listening = WalletListener.isEnabled(this)

        // ---- Main
        ring.summary = w
        accessBox.removeAllViews()
        if (!listening) {
            accessBox.addView(warnCard(
                "Turn on notification access",
                "Spend Ring needs to see your Google Wallet notifications to log every purchase by itself.",
                "Turn on",
            ) { openListenerSettings() })
        } else if (!store.isSetUp) {
            accessBox.addView(warnCard("Set up your budget", "Paste your budget code or type your spending money in on the Budget tab.", "Set up") { show(1) })
        }
        val col = Colors.of(w.state)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hello = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
        val cyc = s.cycle
        greeting.text = if (w.weekNo > 0) "$hello · week ${w.weekNo} of 2" else hello
        val weekly = w.weekNo > 0
        (statLeft.parent as? LinearLayout)?.let { (it.getChildAt(0) as? TextView)?.text = if (weekly) "LEFT THIS WEEK" else "LEFT TO SPEND" }
        (statSpent.parent as? LinearLayout)?.let { (it.getChildAt(0) as? TextView)?.text = if (weekly) "SPENT THIS WEEK" else "SPENT" }
        (statDays.parent as? LinearLayout)?.let { (it.getChildAt(0) as? TextView)?.text = if (weekly) "DAYS LEFT IN WEEK" else "DAYS LEFT" }
        (statRefill.parent as? LinearLayout)?.let { (it.getChildAt(0) as? TextView)?.text = "PAYDAY" }

        statLeft.text = if (cyc != null) Store.money(w.left, false) else "–"
        statLeft.setTextColor(if (cyc != null) col else Colors.FG)
        (statLeft.parent as? LinearLayout)?.let { tile ->
            val tc = if (cyc != null) col else Colors.GREEN
            tile.background = rounded(Colors.alpha(tc, 0x1F), 18f, Colors.alpha(tc, 0x55))
            (tile.getChildAt(0) as? TextView)?.setTextColor(tc)
        }
        statSpent.text = Store.money(w.spent, false)
        statDays.text = if (cyc != null) "${w.daysLeft}" else "–"
        statRefill.text = if (cyc != null) dayFmt.format(Date(cyc.end)) else "–"

        if (cyc != null) {
            safeChip.visibility = View.VISIBLE
            safeChip.text = when {
                w.left > 0 -> "Safe to spend today  ${Store.money(w.perDay)}"
                w.weekNo == 1 -> "Nothing left this week"
                else -> "Nothing left until payday"
            }
            safeChip.setTextColor(col)
            safeChip.background = rounded(Colors.alpha(col, 0x24), 22f, Colors.alpha(col, 0x66))
            val note = when {
                w.weekNo == 1 && w.left < 0 -> "${Store.money(-w.left)} over this week. It comes out of week 2, which starts with ${Store.money(s.budget / 2 + w.left)}."
                w.weekNo == 1 && w.left > 0 -> "Whatever's left on ${dayFmt.format(Date(w.cycle!!.end))} rolls into week 2."
                w.weekNo == 2 && w.carry > 0.005 -> "Includes ${Store.money(w.carry)} left over from week 1."
                w.weekNo == 2 && w.carry < -0.005 -> "${Store.money(-w.carry)} less this week because week 1 went over."
                w.fast && w.left >= 0 -> "You're spending faster than the days are passing. Ease off and it evens out."
                else -> ""
            }
            paceHint.visibility = View.GONE
            paceHint.text = note
            paceHint.setTextColor(if (w.left < 0 || w.carry < -0.005) Colors.ORANGE else Colors.MUTED)

            val (days, today) = store.daily(cyc, s.txns)
            val allowance = s.budget / days.size.coerceAtLeast(1)
            dayBars.set(days, allowance, today)
            dayCard.visibility = View.VISIBLE
        } else {
            safeChip.visibility = View.GONE
            paceHint.visibility = View.GONE
            dayCard.visibility = View.GONE
        }

        val plan = Plan.load(this)
        paydayView.set(s.cycle, if (plan.isEmpty) 0.0 else plan.pay)
        if (plan.isEmpty) {
            savingsCard.visibility = View.GONE
            (savingsCard.parent as? LinearLayout)?.let { p -> p.getChildAt(p.indexOfChild(savingsCard) - 1)?.visibility = View.GONE }
        } else {
            savingsCard.visibility = View.VISIBLE
            (savingsCard.parent as? LinearLayout)?.let { p -> p.getChildAt(p.indexOfChild(savingsCard) - 1)?.visibility = View.VISIBLE }
            savingsView.plan = plan
        }

        recentBox.removeAllViews()
        val recent = s.txns.take(3)
        if (recent.isEmpty()) {
            recentBox.addView(label("Your purchases show up here as soon as Google Wallet tells us.", 14f, Colors.MUTED).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) })
        } else {
            recent.forEachIndexed { i, t ->
                if (i > 0) recentBox.addView(View(this).apply { setBackgroundColor(Colors.LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                recentBox.addView(txnRow(t))
            }
            recentBox.addView(label("See all purchases  ›", 14f, Colors.BLUE, true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(14)); setOnClickListener { show(1) }
            })
        }

        // ---- Budget
        budgetSummary.text = if (cyc != null)
            "${dayFmt.format(Date(cyc.start))} → ${dayFmt.format(Date(cyc.end))}  ·  ${Store.money(s.perDay, false)} a day left"
        else "Set your budget below to start."

        catBox.removeAllViews()
        val byCat = s.txns.filter { it.cat != Store.FUEL }.groupBy { it.cat }.mapValues { e -> e.value.sumOf { it.amount } }
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
        // Only fortnights that have purchases in them, so ones from before tracking started aren't shown as "saved".
        val all = store.txns()
        val past = store.pastCycles(6).filter { c -> all.any { it.ts >= c.start && it.ts < c.end } }
            .take(4).map { it to store.spentIn(it) }
        if (cyc == null || past.isEmpty()) {
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

        renderFuel(s)
        renderPlan()

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

    private fun renderFuel(s: Summary) {
        fuelBox.removeAllViews()
        val r = row()
        r.addView(dot(Store.colorOf(Store.FUEL)))
        r.addView(label("Fuel this fortnight", 15f, Colors.FG, true).apply { setPadding(dp(8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(label("${Store.money(s.fuelSpent, false)} of ${Store.money(s.fuelBudget, false)}", 15f,
            if (s.fuelSpent > s.fuelBudget) Colors.BAD else Colors.FG, true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
        fuelBox.addView(r)
        val frac = if (s.fuelBudget > 0) (s.fuelSpent / s.fuelBudget).toFloat() else 0f
        fuelBox.addView(bar(frac.coerceAtMost(1f), if (frac > 1f) Colors.BAD else Store.colorOf(Store.FUEL)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8) })
        fuelBox.addView(label("Your \$${trimNum(Plan.load(this).fuelMonthly)}/month fuel bill, tracked on its own so filling up doesn't use your spending money.", 12.5f, Colors.MUTED), lp(8))
    }

    private fun renderPlan() {
        val p = Plan.load(this)
        val now = System.currentTimeMillis()
        planBox.removeAllViews()
        if (p.isEmpty) {
            planBox.addView(label("Bring in your budget", 16f, Colors.FG, true))
            planBox.addView(label("Paste the import code Claude gave you. It sets your spending money, paydays, fuel, bills and savings. It stays on this phone only.", 13f, Colors.MUTED), lp(4))
            val code = field("Paste the code here", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
                minLines = 2; maxLines = 4; textSize = 13f; typeface = Typeface.MONOSPACE
            }
            planBox.addView(code, lp(10))
            planBox.addView(button("Import my budget", Colors.ACCENT, Colors.BG) {
                val np = Plan.importCode(this, code.text.toString())
                if (np == null) { code.error = "That doesn't look like a budget code. Copy the whole thing and try again." }
                else {
                    Notifier.changed(this); loadBudgetFields(); refresh()
                    toast("Budget imported: ${Store.money(np.spending, false)} a fortnight, refills every payday")
                }
            }, lp(10))
            planBox.addView(button("Type it in instead", Colors.FIELD, Colors.FG) { planDialog() }, lp(8))
            return
        }
        fun line(name: String, value: String, color: Int = Colors.FG, top: Int = 8) {
            val r = row()
            r.addView(label(name, 14f, Colors.MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(label(value, 14f, color, true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
            planBox.addView(r, lp(top))
        }
        val next = p.nextPayday(now)
        planBox.addView(label("Pay ${Store.money(p.pay, false)} every 2nd Tuesday", 16f, Colors.FG, true))
        planBox.addView(label(if (next != null) "Next payday ${dayFmt.format(Date(next))}" else "", 13f, Colors.MUTED), lp(2))

        line("Rent", Store.money(p.rent, false), Colors.FG, 12)
        line("Savings + investing", Store.money(p.saving, false))
        line("Stays for bills + spending", Store.money(p.staysInAccount, false))

        planBox.addView(label("BILLS EACH MONTH", 11f, Colors.MUTED, true).apply { letterSpacing = 0.09f }, lp(18))
        p.bills.forEach { (n, a) -> line(n, Store.money(a, false), Colors.FG, 6) }
        line("Total", Store.money(p.billsMonthly, false), Colors.FG, 8)

        planBox.addView(label("SAVINGS", 11f, Colors.MUTED, true).apply { letterSpacing = 0.09f }, lp(18))
        line("Now (estimate)", Store.money(p.savingsAt(now), false), Colors.OK, 6)
        val dec31 = Store.parseDate("${Calendar.getInstance().get(Calendar.YEAR)}-12-31")?.timeInMillis ?: now
        line("By 31 Dec", Store.money(p.savingsAt(dec31), false))
        val goals = listOf(10000.0, 20000.0, 30000.0, 40000.0).filter { it > p.savingsAt(now) }.take(2)
        val mon = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        goals.forEach { g -> p.goalDate(g)?.let { line("${Store.money(g, false)} around", mon.format(Date(it))) } }
        planBox.addView(label("From ${Store.money(p.savingsBalance, false)} on ${dayFmt.format(Date(p.savingsDate))}, plus ${Store.money(p.saving, false)} each payday, extra hours and expected windfalls.", 12f, Colors.MUTED), lp(6))

        planBox.addView(label("CARD CASHBACK", 11f, Colors.MUTED, true).apply { letterSpacing = 0.09f }, lp(18))
        val card = store.cardThisMonth(now)
        val hit = card >= p.cashbackMin
        line("On the card this month", "${Store.money(card, false)} / ${Store.money(p.cashbackMin, false)}", if (hit) Colors.OK else Colors.FG, 6)
        planBox.addView(bar((card / p.cashbackMin).toFloat().coerceIn(0f, 1f), if (hit) Colors.OK else Colors.ACCENT),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8) })
        planBox.addView(label(if (hit) "Cashback unlocked: ${Store.money(p.cashback, false)} this month." else
            "${Store.money(p.cashbackMin - card, false)} more on the card this month for ${Store.money(p.cashback, false)} cashback. Purchases Wallet sees count here.", 12f, Colors.MUTED), lp(6))

        planBox.addView(button("Update my plan", Colors.FIELD, Colors.FG) { planDialog() }, lp(16))
    }

    private fun importDialog() {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        wrap.addView(label("Paste a purchases code from Claude. Anything already in the app is skipped, so it's safe to paste the same code twice.", 13f, Colors.MUTED))
        val code = field("Paste the code here", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
            minLines = 3; maxLines = 6; textSize = 13f; typeface = Typeface.MONOSPACE
        }
        wrap.addView(code, lp(10))
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Import purchases")
            .setView(wrap)
            .setPositiveButton("Import", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val r = store.importPurchases(code.text.toString())
                if (r == null) { code.error = "That isn't a purchases code. Copy the whole thing and try again."; return@setOnClickListener }
                Notifier.changed(this); refresh()
                toast("Added ${r.first} purchases" + if (r.second > 0) ", skipped ${r.second} already here" else "")
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun planDialog() {
        val p = Plan.load(this)
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val num = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        fun add(title: String, v: Double): EditText {
            wrap.addView(label(title, 12.5f, Colors.MUTED).apply { setPadding(0, dp(10), 0, dp(4)) })
            val f = field("", trimNum(v), num); wrap.addView(f); return f
        }
        val pay = add("Pay each fortnight (after tax)", p.pay)
        val rent = add("Rent each fortnight", p.rent)
        val saving = add("Savings + investing each fortnight", p.saving)
        val spending = add("Spending money each fortnight", p.spending)
        val fuel = add("Fuel each month", p.fuelMonthly)
        val bal = add("Savings balance today", p.savingsAt(System.currentTimeMillis()))
        wrap.addView(label("Bills each month, one per line (name and amount)", 12.5f, Colors.MUTED).apply { setPadding(0, dp(10), 0, dp(4)) })
        val bills = field("", p.bills.joinToString("\n") { "${it.first} ${trimNum(it.second)}" },
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply { minLines = 3 }
        wrap.addView(bills)

        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Update my plan")
            .setView(ScrollView(this).apply { addView(wrap) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun v(e: EditText, old: Double) = e.text.toString().replace(",", "").replace("$", "").trim().toDoubleOrNull() ?: old
                val billList = bills.text.toString().split("\n").mapNotNull { l ->
                    val m = Regex("""^(.*?)[\s:$]+([\d,]+(?:\.\d+)?)\s*$""").find(l.trim()) ?: return@mapNotNull null
                    val amt = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
                    m.groupValues[1].trim().ifEmpty { "Bill" } to amt
                }
                val newBal = v(bal, p.savingsBalance)
                val balChanged = Math.abs(newBal - p.savingsAt(System.currentTimeMillis())) > 0.5
                val np = p.copy(
                    pay = v(pay, p.pay), rent = v(rent, p.rent), saving = v(saving, p.saving),
                    spending = v(spending, p.spending), fuelMonthly = v(fuel, p.fuelMonthly),
                    bills = if (billList.isEmpty()) p.bills else billList,
                    savingsBalance = if (balChanged) newBal else p.savingsBalance,
                    savingsDate = if (balChanged || p.savingsDate == 0L) System.currentTimeMillis() else p.savingsDate,
                    payAnchor = p.payAnchor.ifEmpty { store.nextRefill },
                )
                if (np.pay <= 0) { pay.error = "Enter your pay"; return@setOnClickListener }
                Plan.save(this, np)
                if (p.isEmpty || np.spending != p.spending || np.fuelMonthly != p.fuelMonthly) Plan.applyToBudget(this, np)
                Notifier.changed(this)
                loadBudgetFields()
                refresh()
                toast("Plan saved")
                dlg.dismiss()
            }
        }
        dlg.show()
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
        val src = when {
            t.source.startsWith("wallet") -> "Wallet"
            t.source == "import" -> "Imported"
            else -> "Added by you"
        }
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
        val allShop = android.widget.CheckBox(this).apply {
            text = "Use this category for every purchase from this shop"
            setTextColor(Colors.FG)
            isChecked = true
        }
        wrap.addView(allShop, lp(4))
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
                if (existing == null || existing.cat != cat) {
                    if (allShop.isChecked) {
                        store.rememberCategory(name, cat)
                        val n = store.recategorize(name, cat)
                        if (n > 0) toast("$name is now $cat ($n purchases changed)")
                    }
                }
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

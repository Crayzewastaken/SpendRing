package com.spendring.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/** One purchase. [amount] is positive for spending, negative for a refund. */
data class Txn(
    val id: String,
    val merchant: String,
    val amount: Double,
    val cat: String,
    val ts: Long,
    val source: String, // "wallet" or "manual"
    val raw: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("merchant", merchant).put("amount", amount).put("cat", cat)
        .put("ts", ts).put("source", source).put("raw", raw)

    companion object {
        fun fromJson(o: JSONObject) = Txn(
            id = o.optString("id"),
            merchant = o.optString("merchant", "Purchase"),
            amount = o.optDouble("amount", 0.0),
            cat = o.optString("cat", "Other"),
            ts = o.optLong("ts", 0L),
            source = o.optString("source", "manual"),
            raw = o.optString("raw", ""),
        )
    }
}

data class Cycle(val start: Long, val end: Long) {
    val length: Long get() = end - start
}

data class Summary(
    val budget: Double,
    val cycle: Cycle?,
    val txns: List<Txn>,   // this cycle, newest first (fuel included)
    val spent: Double,     // spending money used (fuel not included)
    val fuelSpent: Double,
    val fuelBudget: Double,
    val left: Double,
    val daysLeft: Int,
    val perDay: Double,
    val timeFrac: Double,
    val state: State,
) {
    /** Ring colour comes from how much is left: over half green, under half orange, under a quarter red. */
    enum class State { NOT_SET, PLENTY, HALF, LOW, OVER }

    /** Spending faster than the days are passing. */
    val fast: Boolean get() = cycle != null && budget > 0 && spent / budget > timeFrac + 0.10
}

data class Category(val name: String, val color: Int, val keywords: List<String>)

/** Everything is kept in SharedPreferences on the phone. Nothing leaves the device. */
class Store(ctx: Context) {
    private val app = ctx.applicationContext
    val sp: SharedPreferences = app.getSharedPreferences("spendring", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ settings

    var budget: Double
        get() = sp.getFloat("budget", 0f).toDouble()
        set(v) = sp.edit().putFloat("budget", v.toFloat()).apply()

    /** "weekly", "fortnightly" or "monthly". */
    var cycleKind: String
        get() = sp.getString("cycle", "fortnightly") ?: "fortnightly"
        set(v) = sp.edit().putString("cycle", v).apply()

    /** yyyy-MM-dd of the next refill (rolled forward automatically). */
    var nextRefill: String
        get() = sp.getString("nextRefill", "") ?: ""
        set(v) = sp.edit().putString("nextRefill", v).apply()

    /** Fuel has its own allowance per cycle, so filling up doesn't eat the spending money. */
    var fuelBudget: Double
        get() = sp.getFloat("fuelBudget", 92f).toDouble()
        set(v) = sp.edit().putFloat("fuelBudget", v.toFloat()).apply()

    var notifyOnPurchase: Boolean
        get() = sp.getBoolean("notifyOnPurchase", true)
        set(v) = sp.edit().putBoolean("notifyOnPurchase", v).apply()

    /** Apps whose notifications are read, one package per line. */
    var watchApps: String
        get() = sp.getString("watchApps", DEFAULT_APPS) ?: DEFAULT_APPS
        set(v) = sp.edit().putString("watchApps", v).apply()

    fun watchSet(): Set<String> =
        watchApps.split(",", "\n", " ").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    val isSetUp: Boolean get() = budget > 0 && nextRefill.isNotEmpty()

    // ------------------------------------------------------------------ purchases

    @Synchronized
    fun txns(): List<Txn> {
        val arr = try { JSONArray(sp.getString("txns", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val out = ArrayList<Txn>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(Txn.fromJson(it)) }
        return out
    }

    @Synchronized
    private fun saveTxns(list: List<Txn>) {
        // Keep roughly two years so the file stays small.
        val cutoff = System.currentTimeMillis() - 730L * DAY
        val arr = JSONArray()
        list.filter { it.ts >= cutoff }.sortedBy { it.ts }.forEach { arr.put(it.toJson()) }
        sp.edit().putString("txns", arr.toString()).commit()
    }

    @Synchronized
    fun add(t: Txn) = saveTxns(txns() + t)

    @Synchronized
    fun replace(t: Txn) = saveTxns(txns().map { if (it.id == t.id) t else it })

    @Synchronized
    fun remove(id: String) = saveTxns(txns().filter { it.id != id })

    /**
     * True if this purchase was already recorded: same shop and amount within 3 minutes, or the same
     * amount within 3 minutes from another app (e.g. Wallet and a bank app both announce one tap).
     */
    @Synchronized
    fun isDuplicate(merchant: String, amount: Double, ts: Long, source: String): Boolean =
        txns().any {
            it.source != "manual" && abs(it.amount - amount) < 0.005 && abs(it.ts - ts) < 3 * 60_000L &&
                (it.merchant.equals(merchant, ignoreCase = true) || it.source != source)
        }

    /** Notification ids already handled, so an updated or re-shown notification isn't counted twice. */
    @Synchronized
    fun markSeen(fp: String): Boolean {
        val arr = try { JSONArray(sp.getString("seen", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        for (i in 0 until arr.length()) if (arr.optString(i) == fp) return false
        val out = JSONArray()
        val start = max(0, arr.length() - 300)
        for (i in start until arr.length()) out.put(arr.optString(i))
        out.put(fp)
        sp.edit().putString("seen", out.toString()).apply()
        return true
    }

    // ------------------------------------------------------------------ notification log (for checking)

    @Synchronized
    fun log(pkg: String, title: String, text: String, result: String) {
        val arr = try { JSONArray(sp.getString("log", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val out = JSONArray()
        val start = max(0, arr.length() - 39)
        for (i in start until arr.length()) out.put(arr.optJSONObject(i))
        out.put(JSONObject().put("ts", System.currentTimeMillis()).put("pkg", pkg)
            .put("title", title.take(120)).put("text", text.take(300)).put("result", result))
        sp.edit().putString("log", out.toString()).apply()
    }

    @Synchronized
    fun logEntries(): List<JSONObject> {
        val arr = try { JSONArray(sp.getString("log", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.reversed()
    }

    // ------------------------------------------------------------------ categories

    /** Remembered "this shop → this category" choices. */
    fun rememberCategory(merchant: String, cat: String) {
        val j = try { JSONObject(sp.getString("catMemory", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        j.put(merchantKey(merchant), cat)
        sp.edit().putString("catMemory", j.toString()).apply()
    }

    /** Changes the category of every purchase from this shop (same name, ignoring case and symbols). */
    @Synchronized
    fun recategorize(merchant: String, cat: String): Int {
        val key = merchantKey(merchant)
        var n = 0
        saveTxns(txns().map { if (merchantKey(it.merchant) == key && it.cat != cat) { n++; it.copy(cat = cat) } else it })
        return n
    }

    /**
     * Adds purchases from an import code: {"purchases":[["Shop", 12.50, "2026-10-29", "Category"], ...]}
     * (time optional as "2026-10-29 14:05", category optional). Skips ones already in the app.
     * Returns (added, skipped), or null if the text isn't a purchase code.
     */
    @Synchronized
    fun importPurchases(text: String): Pair<Int, Int>? {
        val arr = try {
            val t = text.trim()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("purchases")
        } catch (_: Exception) { null } ?: return null
        val list = txns().toMutableList()
        val catNames = CATEGORIES.map { it.name }
        var added = 0
        var skipped = 0
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            val m = row.optString(0).trim().ifEmpty { "Purchase" }
            val amt = row.optDouble(1, Double.NaN)
            if (amt.isNaN() || amt == 0.0) { skipped++; continue }
            val ds = row.optString(2)
            val ts = parseDateTime(ds)
            if (ts == null) { skipped++; continue }
            val dayStart = parseDate(ds.take(10))?.timeInMillis ?: ts
            val k = merchantKey(m).take(5)
            // Same amount, same day, and the shop names start the same ("WOOLWORTHS 2648" = "Woolworths").
            val dup = list.any {
                abs(it.amount - amt) < 0.005 && it.ts >= dayStart && it.ts < dayStart + DAY &&
                    merchantKey(it.merchant).take(5) == k
            }
            if (dup) { skipped++; continue }
            val c = row.optString(3, "").let { given -> catNames.firstOrNull { it.equals(given, true) } } ?: guessCategory(m)
            val t = Txn(newId(), m, amt, c, ts, "import", "")
            list.add(t)
            added++
        }
        saveTxns(list)
        return added to skipped
    }

    private fun parseDateTime(s: String): Long? {
        val d = parseDate(s.take(10)) ?: return null
        val tm = Regex("""(\d{1,2}):(\d{2})""").find(s.drop(10))
        if (tm != null) {
            d.set(Calendar.HOUR_OF_DAY, tm.groupValues[1].toInt()); d.set(Calendar.MINUTE, tm.groupValues[2].toInt())
        } else d.set(Calendar.HOUR_OF_DAY, 12)
        return d.timeInMillis
    }

    fun guessCategory(merchant: String): String {
        val j = try { JSONObject(sp.getString("catMemory", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val key = merchantKey(merchant)
        if (j.has(key)) return j.optString(key, "Other")
        val m = merchant.lowercase(Locale.ROOT)
        for (c in CATEGORIES) if (c.keywords.any { m.contains(it) }) return c.name
        return "Other"
    }

    private fun merchantKey(m: String) = m.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

    // ------------------------------------------------------------------ cycle maths

    fun currentCycle(now: Long = System.currentTimeMillis()): Cycle? {
        val next = parseDate(nextRefill) ?: return null
        val end = next.clone() as Calendar
        var guard = 0
        while (end.timeInMillis <= now && guard++ < 500) step(end, 1)
        val start = end.clone() as Calendar
        step(start, -1)
        // Remember the rolled-forward date so settings show the real next refill.
        val endStr = DATE.format(end.time)
        if (endStr != nextRefill) nextRefill = endStr
        return Cycle(start.timeInMillis, end.timeInMillis)
    }

    /** The [n] cycles before the current one, newest first. */
    fun pastCycles(n: Int): List<Cycle> {
        val cur = currentCycle() ?: return emptyList()
        val out = ArrayList<Cycle>()
        val end = Calendar.getInstance().apply { timeInMillis = cur.start }
        repeat(n) {
            val start = end.clone() as Calendar
            step(start, -1)
            out.add(Cycle(start.timeInMillis, end.timeInMillis))
            end.timeInMillis = start.timeInMillis
        }
        return out
    }

    private fun step(c: Calendar, n: Int) {
        when (cycleKind) {
            "weekly" -> c.add(Calendar.DAY_OF_MONTH, 7 * n)
            "fortnightly" -> c.add(Calendar.DAY_OF_MONTH, 14 * n)
            else -> c.add(Calendar.MONTH, n)
        }
    }

    fun summary(now: Long = System.currentTimeMillis()): Summary {
        val b = budget
        val cyc = if (isSetUp) currentCycle(now) else null
        val all = txns()
        val inCycle = (if (cyc != null) all.filter { it.ts >= cyc.start && it.ts < cyc.end } else all)
            .sortedByDescending { it.ts }
        val spent = inCycle.filter { it.cat != FUEL }.sumOf { it.amount }
        val fuel = inCycle.filter { it.cat == FUEL }.sumOf { it.amount }
        val left = b - spent
        val daysLeft = if (cyc != null) max(1, ceil((cyc.end - now) / DAY.toDouble()).toInt()) else 0
        val perDay = if (daysLeft > 0) max(0.0, left) / daysLeft else 0.0
        val timeFrac = if (cyc != null) ((now - cyc.start).toDouble() / cyc.length).coerceIn(0.0, 1.0) else 0.0
        val state = when {
            cyc == null || b <= 0 -> Summary.State.NOT_SET
            left < 0 -> Summary.State.OVER
            left / b < 0.25 -> Summary.State.LOW
            left / b < 0.50 -> Summary.State.HALF
            else -> Summary.State.PLENTY
        }
        return Summary(b, cyc, inCycle, spent, fuel, fuelBudget, left, daysLeft, perDay, timeFrac, state)
    }

    fun spentIn(c: Cycle): Double = txns().filter { it.ts >= c.start && it.ts < c.end && it.cat != FUEL }.sumOf { it.amount }

    /** Spending (fuel not included) for each day of the cycle, with the index of today. */
    fun daily(c: Cycle, txns: List<Txn>, now: Long = System.currentTimeMillis()): Pair<List<Pair<Long, Double>>, Int> {
        val days = ArrayList<Pair<Long, Double>>()
        val cal = Calendar.getInstance().apply { timeInMillis = c.start }
        var today = -1
        var guard = 0
        while (cal.timeInMillis < c.end && guard++ < 40) {
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
            val end = cal.timeInMillis
            if (now in start until end) today = days.size
            days.add(start to txns.filter { it.ts in start until end && it.cat != FUEL }.sumOf { it.amount })
        }
        return days to today
    }

    /** Everything put on the card this calendar month (for the cashback target). */
    fun cardThisMonth(now: Long = System.currentTimeMillis()): Double {
        val c = Calendar.getInstance().apply {
            timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return txns().filter { it.ts >= c.timeInMillis && it.ts <= now }.sumOf { it.amount }
    }

    companion object {
        const val DAY = 86_400_000L
        const val WALLET = "com.google.android.apps.walletnfcrel"
        const val GMS = "com.google.android.gms"
        const val FUEL = "Fuel"
        const val DEFAULT_APPS = "$WALLET\n$GMS"

        val DATE = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        fun parseDate(s: String): Calendar? {
            if (s.isBlank()) return null
            return try {
                val d = DATE.parse(s) ?: return null
                Calendar.getInstance().apply {
                    time = d
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
            } catch (_: Exception) { null }
        }

        fun money(v: Double, cents: Boolean = true): String {
            val neg = v < 0
            val a = abs(v)
            val s = if (cents) {
                val c = (a * 100).roundToLong()
                String.format(Locale.US, "%,d.%02d", c / 100, c % 100)
            } else String.format(Locale.US, "%,d", a.roundToLong())
            return (if (neg) "-$" else "$") + s
        }

        fun newId(): String = "t" + System.currentTimeMillis().toString(36) + (1000..9999).random()

        val CATEGORIES = listOf(
            Category("Groceries", 0xFF3FC39D.toInt(), listOf("woolworths", "coles", "aldi", "iga", "foodworks", "harris farm", "costco", "butcher", "fruit", "market")),
            Category("Eating out", 0xFFF08A4B.toInt(), listOf("mcdonald", "maccas", "kfc", "hungry jack", "subway", "domino", "pizza", "cafe", "coffee", "grill", "sushi", "guzman", "nando", "oporto", "uber eats", "ubereats", "menulog", "doordash", "bar ", "restaurant", "bakery", "boost", "zambrero", "red rooster", "grilld", "starbucks", "kebab", "thai", "noodle", "hjs", "hj's", "zarraffa", "gong cha", "chatime", "bakehouse", "taco bell", "deliverect", "hotel", "tavern", "pho", "saigon", "sai gon", "dumpling", "burger", "bubble tea")),
            Category(FUEL, 0xFFF0C23A.toInt(), listOf("bp ", "bp express", "shell", "ampol", "caltex", "7-eleven", "7 eleven", "7eleven", "united petrol", "puma", "liberty", "metro petroleum", "fuel", "petrol", "costco fuel", "pearl energy", "freedom fuels", "coles express", "astron")),
            Category("Transport", 0xFF7A9BFF.toInt(), listOf("translink", "go card", "uber trip", "uber *trip", "didi", "ola ", "parking", "linkt", "transurban", "taxi", "13cabs")),
            Category("Shopping", 0xFFB58CFF.toInt(), listOf("kmart", "target", "big w", "jb hi", "amazon", "ebay", "myer", "david jones", "officeworks", "bunnings", "rebel", "uniqlo", "h&m", "temu", "shein", "apple", "harvey norman", "the good guys", "best&less", "cotton on")),
            Category("Fun", 0xFFFF6FAE.toInt(), listOf("event cinemas", "hoyts", "dendy", "steam", "playstation", "xbox", "nintendo", "ticketek", "ticketmaster", "bowling", "timezone", "netflix", "spotify", "reading cinemas", "the lott", "lotto", "tatts")),
            Category("Health", 0xFF4FD1E0.toInt(), listOf("chemist", "pharmacy", "priceline", "terrywhite", "doctor", "medical", "dental", "gym", "fitness", "anytime", "physio")),
            Category("Other", 0xFF93A5A1.toInt(), emptyList()),
        )

        fun colorOf(cat: String): Int = (CATEGORIES.firstOrNull { it.name == cat } ?: CATEGORIES.last()).color
    }
}

package com.spendring.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Your budget plan: pay, rent, savings, bills. It is pasted in once as an import code (or typed in)
 * and stored only on the phone, never in this code. Pay lands every 2nd week from [payAnchor].
 */
data class Plan(
    val pay: Double,             // net pay per fortnight
    val rent: Double,            // per fortnight
    val saving: Double,          // per fortnight to savings + investments
    val spending: Double,        // card spending money per fortnight
    val fuelMonthly: Double,     // fuel bill per month (tracked on its own)
    val bills: List<Pair<String, Double>>, // monthly
    val savingsBalance: Double,
    val savingsDate: Long,       // when the balance was checked
    val payAnchor: String,       // a known payday, yyyy-MM-dd
    val cashbackMin: Double,     // card spend per calendar month for cashback
    val cashback: Double,
    val extra: Map<String, Double>,                 // payday (yyyy-MM-dd) -> extra saved that payday
    val lumps: List<Triple<String, Double, String>>, // expected windfalls straight to savings
) {
    val isEmpty: Boolean get() = pay <= 0.0

    val fuelPerFortnight: Double get() = fuelMonthly * 12.0 / 26.0
    val billsMonthly: Double get() = bills.sumOf { it.second }
    val staysInAccount: Double get() = pay - rent - saving

    fun toJson(): JSONObject = JSONObject()
        .put("pay", pay).put("rent", rent).put("saving", saving).put("spending", spending)
        .put("fuelMonthly", fuelMonthly)
        .put("bills", JSONArray().apply { bills.forEach { put(JSONArray().put(it.first).put(it.second)) } })
        .put("savingsBalance", savingsBalance).put("savingsDate", savingsDate).put("payAnchor", payAnchor)
        .put("cashbackMin", cashbackMin).put("cashback", cashback)
        .put("extra", JSONObject().apply { extra.forEach { (k, v) -> put(k, v) } })
        .put("lumps", JSONArray().apply { lumps.forEach { put(JSONArray().put(it.first).put(it.second).put(it.third)) } })

    /** Paydays (as midnight timestamps) after [from] up to [to]. */
    fun paydays(from: Long, to: Long): List<Long> {
        val c = Store.parseDate(payAnchor) ?: return emptyList()
        val out = ArrayList<Long>()
        var guard = 0
        while (c.timeInMillis <= from && guard++ < 2000) c.add(Calendar.DAY_OF_MONTH, 14)
        while (c.timeInMillis <= to && guard++ < 4000) { out.add(c.timeInMillis); c.add(Calendar.DAY_OF_MONTH, 14) }
        return out
    }

    fun nextPayday(now: Long = System.currentTimeMillis()): Long? = paydays(now, now + 15 * Store.DAY).firstOrNull()

    /** Estimated savings balance at [at]: last checked balance plus every payday transfer since. */
    fun savingsAt(at: Long): Double {
        var v = savingsBalance
        paydays(savingsDate, at).forEach { v += saving + (extra[Store.DATE.format(it)] ?: 0.0) }
        lumps.forEach { (d, amt, _) -> val t = Store.parseDate(d)?.timeInMillis ?: 0L; if (t in (savingsDate + 1)..at) v += amt }
        return v
    }

    /** First date the estimated balance reaches [goal], looking up to 4 years ahead. */
    fun goalDate(goal: Double): Long? {
        if (savingsBalance >= goal) return savingsDate
        val now = System.currentTimeMillis()
        val ends = (paydays(savingsDate, now + 4 * 365 * Store.DAY) +
            lumps.mapNotNull { Store.parseDate(it.first)?.timeInMillis }).filter { it > savingsDate }.sorted()
        return ends.firstOrNull { savingsAt(it) >= goal }
    }

    companion object {
        /** Nothing set yet: the Budget tab offers to import a code or type it in. */
        val EMPTY = Plan(
            pay = 0.0, rent = 0.0, saving = 0.0, spending = 150.0, fuelMonthly = 0.0, bills = emptyList(),
            savingsBalance = 0.0, savingsDate = 0L, payAnchor = "", cashbackMin = 0.0, cashback = 0.0,
            extra = emptyMap(), lumps = emptyList(),
        )

        fun load(ctx: Context): Plan =
            Store(ctx).sp.getString("plan", null)?.let { parse(it) } ?: EMPTY

        /** Reads a plan from JSON (the import code). Returns null if it isn't one. */
        fun parse(text: String): Plan? = try {
            val o = JSONObject(text.trim().removePrefix("SPENDRING:").trim())
            val b = o.optJSONArray("bills") ?: JSONArray()
            val ex = o.optJSONObject("extra") ?: JSONObject()
            val lu = o.optJSONArray("lumps") ?: JSONArray()
            val d = EMPTY
            val sd = o.opt("savingsDate")
            Plan(
                pay = o.optDouble("pay", d.pay), rent = o.optDouble("rent", d.rent),
                saving = o.optDouble("saving", d.saving), spending = o.optDouble("spending", d.spending),
                fuelMonthly = o.optDouble("fuelMonthly", d.fuelMonthly),
                bills = (0 until b.length()).mapNotNull { i -> b.optJSONArray(i)?.let { it.optString(0) to it.optDouble(1, 0.0) } },
                savingsBalance = o.optDouble("savingsBalance", d.savingsBalance),
                savingsDate = when (sd) {
                    is Number -> sd.toLong()
                    is String -> Store.parseDate(sd)?.timeInMillis ?: 0L
                    else -> 0L
                },
                payAnchor = o.optString("payAnchor", d.payAnchor),
                cashbackMin = o.optDouble("cashbackMin", d.cashbackMin),
                cashback = o.optDouble("cashback", d.cashback),
                extra = ex.keys().asSequence().associateWith { ex.optDouble(it, 0.0) },
                lumps = (0 until lu.length()).mapNotNull { i ->
                    lu.optJSONArray(i)?.let { Triple(it.optString(0), it.optDouble(1, 0.0), it.optString(2, "")) }
                },
            ).takeIf { it.pay > 0 }
        } catch (_: Exception) { null }

        fun save(ctx: Context, p: Plan) {
            Store(ctx).sp.edit().putString("plan", p.toJson().toString()).apply()
        }

        /** Sets the spending budget and refill date from the plan. Runs once on first open. */
        fun applyToBudget(ctx: Context, p: Plan) {
            val store = Store(ctx)
            store.budget = p.spending
            store.cycleKind = "fortnightly"
            store.fuelBudget = Math.round(p.fuelPerFortnight).toDouble()
            p.nextPayday()?.let { store.nextRefill = Store.DATE.format(java.util.Date(it)) }
        }

        /** Saves a pasted import code and sets the spending budget + payday refills from it. */
        fun importCode(ctx: Context, text: String): Plan? {
            val p = parse(text) ?: return null
            save(ctx, p)
            applyToBudget(ctx, p)
            return p
        }
    }
}

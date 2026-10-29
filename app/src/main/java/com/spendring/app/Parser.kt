package com.spendring.app

import java.util.Locale

/** Reads a purchase out of a Google Wallet "you paid" notification. */
object Parser {

    data class Result(val merchant: String, val amount: Double)

    /** Either a purchase, or the reason it was skipped. */
    sealed class Outcome {
        data class Purchase(val r: Result) : Outcome()
        data class Skip(val reason: String) : Outcome()
    }

    private val AMOUNT = Regex(
        """(?:A\$|AU\$|AUD\s?|\$)\s?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )
    private val AT = Regex(
        """\b(?:at|to|from)\s+(.+?)(?:\s+(?:with|using|on|via|for)\b|[.\n•·|]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val SKIP_WORDS = listOf(
        "declined", "unsuccessful", "failed", "insufficient", "not approved", "couldn't", "could not",
        "pending verification", "verify your", "added to wallet", "card added", "is ready", "set up",
    )
    private val CARD_HINTS = listOf("visa", "mastercard", "amex", "american express", "eftpos", "••", "**", "card", "paid", "purchase", "wallet")
    private val GENERIC_TITLES = listOf(
        "google wallet", "google pay", "wallet", "payment", "purchase", "transaction", "you paid", "paid",
        "payment complete", "payment successful", "contactless payment",
    )

    /**
     * [strict] = the notification came from a general app (Google Play services) rather than Wallet,
     * so it also has to look like a card payment.
     */
    fun parse(titleIn: String, textIn: String, strict: Boolean): Outcome {
        val title = clean(titleIn)
        val text = clean(textIn)
        val all = "$title\n$text"
        val lower = all.lowercase(Locale.ROOT)

        SKIP_WORDS.firstOrNull { lower.contains(it) }?.let { return Outcome.Skip("not a completed purchase (\"$it\")") }
        if (strict && CARD_HINTS.none { lower.contains(it) }) return Outcome.Skip("doesn't look like a card payment")

        val m = AMOUNT.find(all) ?: return Outcome.Skip("no dollar amount")
        val amount = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return Outcome.Skip("no dollar amount")
        if (amount <= 0.0) return Outcome.Skip("amount was \$0")

        val refund = lower.contains("refund") || lower.contains("reversal") || lower.contains("credited")
        val signed = if (refund) -amount else amount

        return Outcome.Purchase(Result(merchant(title, text), signed))
    }

    private fun merchant(title: String, text: String): String {
        // Wallet normally puts the shop name in the title.
        val t = trimCard(title)
        if (t.isNotEmpty() && !AMOUNT.containsMatchIn(t) && GENERIC_TITLES.none { t.equals(it, true) } &&
            !t.lowercase(Locale.ROOT).startsWith("you paid")
        ) return tidy(t)

        // "You paid $12.50 at Woolworths"
        for (src in listOf(text, title)) {
            AT.find(src)?.let { mm ->
                val name = trimCard(mm.groupValues[1])
                if (name.isNotEmpty() && !AMOUNT.containsMatchIn(name)) return tidy(name)
            }
        }
        // Otherwise the first line that isn't the amount or the card.
        val line = text.split('\n', '•', '·', '|').map { trimCard(it) }.firstOrNull {
            it.isNotEmpty() && !AMOUNT.containsMatchIn(it) && CARD_HINTS.none { h -> it.lowercase(Locale.ROOT).contains(h) }
        }
        return if (line.isNullOrEmpty()) "Purchase" else tidy(line)
    }

    private fun trimCard(s: String): String =
        s.replace(Regex("""(?i)\s*(?:with|using|on)\s+(?:your\s+)?(?:visa|mastercard|amex|debit|credit|card|eftpos).*$"""), "")
            .replace(Regex("""\s*[•*]{2,}\s*\d{2,4}.*$"""), "")
            .trim().trim('-', '—', ':', '.', ',').trim()

    private fun tidy(s: String): String {
        val one = s.replace(Regex("\\s{2,}"), " ").trim()
        return if (one.length > 50) one.take(50).trimEnd() + "…" else one
    }

    private fun clean(s: String) = s.replace(' ', ' ').replace(' ', ' ').trim()
}

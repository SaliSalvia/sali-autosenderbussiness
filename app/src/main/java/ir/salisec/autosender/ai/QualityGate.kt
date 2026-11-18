package ir.salisec.autosender.ai

import ir.salisec.autosender.data.ReportBundle
import ir.salisec.autosender.data.Settings

/** Enforces the agreed red lines BEFORE a human is allowed to approve an email:
 *  - no invented numerical claim (facts must be grounded in the target report; identifiers and fixed footer/signature are excluded)
 *  - no fake urgency / fake deadline / threat / hype
 *  - no spam-evasion tricks, no deceptive subject, no fake headers
 *  - one low-friction CTA + a polite opt-out line
 *  - short, scannable, professional Persian B2B copy */
object QualityGate {

    private val banned = listOf(
        "فیلتر اسپم", "دور زدن", "دورزدن", "ارسال انبوه", "اسپم",
        "هک شد", "هک شده", "نفوذ کرده ایم", "نفوذ کردیم", "اطلاعات شما لو رفته",
        "تضمین", "صد درصد", "صددرصد", "۱۰۰٪", "100%", "بدون هیچ ریسک",
        "همین امروز اقدام", "تا پایان امروز", "تا فردا", "فرصت محدود",
        "فقط ۲۴ ساعت", "فقط 24 ساعت", "همین هفته تمام می‌شود", "مهلت تمام شد",
        "پیشنهاد ویژه", "تخفیف ویژه", "هدیه ویژه", "پرداخت کنید", "خرید کنید",
        "کلیک کنید", "لینک زیر", "فرم سفارش", "قیمت را اعلام کنید",
        "click here", "buy now", "act now", "limited time", "guaranteed",
        "unsubscribe:", "list-unsubscribe:", "urgent!!!"
    )

    private val capsWhitelist = setOf(
        "DMARC", "HSTS", "CSP", "CVSS", "SPF", "DKIM", "TLS", "SSL", "XSS",
        "CORS", "HTTP", "HTTPS", "SQL", "CTO", "CEO", "API", "URL", "HTML",
        "SALISECURE", "SALIBUSSINESS", "IP", "DNS", "WAF", "CVE", "PDF"
    )

    private val numberRegex = Regex("""[0-9]+(?:[.,][0-9]+)?""")
    private val capsRegex = Regex("""[A-Z]{4,}""")

    /**
     * Numeric allow-list derived exclusively from the current target's evidence pack.
     * Campaign aggregates, recipient metadata, signatures, previous drafts and customer
     * replies are context, not evidence for factual claims in this recipient's email.
     */
    fun allowedNumbers(bundle: ReportBundle?): Set<String> {
        if (bundle == null) return emptySet()

        val out = mutableSetOf<String>()
        // Only readable PDF body text and parsed numeric report fields count as evidence.
        // Domain identifiers, sidecar IDs, file metadata and recipient metadata do not.
        out.addAll(numbers(bundle.pdfs.filter { it.readOk }.joinToString("\n") { it.text }))
        bundle.data?.let { d ->
            listOf(d.score, d.findings, d.critical, d.high, d.medium, d.low, d.subdomains)
                .filterNotNull()
                .forEach { out.add(it.toString()) }
            if (d.score != null) out.add("100")
            d.freeFindingCvss?.let { out.addAll(numbers(it)) }
        }
        return out
    }

    /** Validate a draft (or a human-edited draft) against the evidence pack. */
    fun validate(
        result: AiResult,
        s: Settings,
        allowed: Set<String>,
        isFollowUp: Boolean = false,
        nonClaimIdentifiers: Set<String> = emptySet()
    ): QualityReport {
        val all = listOf(result.subject, result.preheader, result.body, result.cta).joinToString("\n")
        val lower = normalizeDigits(all).lowercase()
        val blockers = mutableListOf<String>()
        val issues = mutableListOf<String>()
        var score = 100

        if (result.subject.isBlank()) blockers.add("موضوع خالی است")
        if (result.body.isBlank()) blockers.add("متن ایمیل خالی است")
        if (result.subject.length > 70) issues.add("موضوع طولانی است (بیش از ۷۰ نویسه)؛ ۲۵ تا ۵۰ نویسه بهتر است")
        if (result.subject.length < 8 && result.subject.isNotBlank()) issues.add("موضوع بیش از حد کوتاه است")

        if (lower.trimStart().startsWith("re:") || lower.trimStart().startsWith("fwd:")) {
            blockers.add("موضوع جعلی Re:/Fwd: ممنوع است")
        }

        // Domains/emails and deterministic local signature/footer are identifiers, not numeric claims.
        var numericClaimText = all
        s.signature.trim().takeIf { it.isNotBlank() }?.let { numericClaimText = numericClaimText.replace(it, "") }
        s.unsubscribeFooter.trim().takeIf { it.isNotBlank() }?.let { numericClaimText = numericClaimText.replace(it, "") }
        nonClaimIdentifiers.filter { it.isNotBlank() }.forEach {
            numericClaimText = numericClaimText.replace(it, "", ignoreCase = true)
        }
        val used = numbers(numericClaimText)
        val invented = used.filter { it !in allowed }
        if (invented.isNotEmpty()) {
            blockers.add("عدد/رقمی در متن هست که در گزارش واقعی وجود ندارد: " + invented.take(6).joinToString("، "))
        }

        for (b in banned) {
            if (lower.contains(b)) blockers.add("عبارت ممنوع (ادعای غیرواقعی/فریب/اسپم): «$b»")
        }

        if (!isFollowUp) {
            if (result.body.length < 220) issues.add("متن کوتاه است؛ ارزش رایگان و مدرک را کامل توضیح بده")
            if (result.body.length > 1800) issues.add("متن طولانی است؛ زیر ۲۰۰ کلمه نگه دار")
        } else {
            if (result.body.length > 900) issues.add("پیگیری باید زیر ۸۰ کلمه باشد")
        }
        if (result.preheader.length > 110) issues.add("پیش نمایش ایمیل (preheader) طولانی است")

        if (!lower.contains("پاسخ") && !lower.contains("reply")) {
            blockers.add("فراخوان اقدام کم اصطکاک (پاسخ به همین ایمیل) وجود ندارد")
        }
        if (!lower.contains("حذف") && !lower.contains("تمایل") && !lower.contains("عدم") &&
            !lower.contains("unsubscribe")
        ) {
            issues.add("خط «خروج از فهرست» پیشنهادی نیست؛ اضافه کردنش هم انطباق و هم deliverability را بهتر میکند")
        }
        if (all.count { it == '!' } > 2) issues.add("علامت تعجب زیاد است")
        if (emojiCount(all) > 2) issues.add("ایموجی بیش از دو مورد ممنوع است")

        val caps = capsRegex.findAll(all).map { it.value }.filter { it !in capsWhitelist }.toList()
        if (caps.isNotEmpty()) issues.add("حروف بزرگ پیاپی غیرمتعارف: " + caps.take(3).joinToString("، "))

        score -= blockers.size * 25
        score -= issues.size * 7
        score = score.coerceIn(0, 100)
        return QualityReport(
            passed = blockers.isEmpty() && score >= 70,
            score = score,
            issues = issues,
            blockers = blockers,
            usedNumbers = used,
            allowedNumbers = allowed
        )
    }

    /** Pick the strongest subject line with a deterministic local heuristic (no extra API call). */
    fun bestSubject(result: AiResult): AiResult {
        val pool = (listOf(result.subject) + result.subjectAlternatives)
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (pool.isEmpty()) return result
        fun score(s: String): Int {
            var v = 0
            if (s.length in 22..52) v += 4
            if (s.contains(Regex("""[0-9]"""))) v += 3
            if (s.contains("گزارش") || s.contains("امنیت") || s.contains("یافته")) v += 2
            if (s.contains("!") || s.contains("؟")) v -= 2
            if (s.length > 60) v -= 4
            if (s.contains("فوری") || s.contains("هشدار")) v -= 1
            return v
        }
        val best = pool.maxByOrNull { score(it) } ?: result.subject
        return result.copy(
            subject = best,
            subjectAlternatives = pool.filter { it != best }.take(4)
        )
    }

    fun numbers(text: String): Set<String> =
        numberRegex.findAll(normalizeDigits(text)).map { it.value }.toSet()

    private fun normalizeDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(
                when (ch) {
                    '۰' -> '0'; '۱' -> '1'; '۲' -> '2'; '۳' -> '3'; '۴' -> '4'
                    '۵' -> '5'; '۶' -> '6'; '۷' -> '7'; '۸' -> '8'; '۹' -> '9'
                    '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'
                    '٥' -> '5'; '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'
                    '\u066B' -> '.'; '\u066C' -> ','
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    private fun emojiCount(text: String): Int {
        var c = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (Character.isHighSurrogate(ch) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                val cp = Character.toCodePoint(ch, text[i + 1])
                if (cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF) c++
                i += 2
            } else {
                if (ch.code in 0x2600..0x27BF) c++
                i++
            }
        }
        return c
    }
}

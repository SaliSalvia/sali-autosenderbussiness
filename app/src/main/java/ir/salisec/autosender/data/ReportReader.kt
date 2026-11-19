package ir.salisec.autosender.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Reads the user-selected "sitest" folder (SAF).
 *
 *  Fast folder indexing + automatic matching by email/domain.
 *  Strictly parses only the 2 newest PDFs per domain (prioritizing the newest
 *  matched Persian/English trial pair) so older reports never leak into AI analysis. */
object ReportReader {

    private val freeEmailDomains = setOf(
        "gmail.com", "googlemail.com", "yahoo.com", "ymail.com", "outlook.com",
        "hotmail.com", "live.com", "icloud.com", "me.com", "proton.me", "protonmail.com",
        "mail.ru", "yandex.com", "yandex.ru", "aol.com", "zoho.com"
    )

    /** Suggested initial URI for OpenDocumentTree so the picker opens straight to /sitest. */
    fun initialSitestTreeUri(): Uri =
        Uri.parse("content://com.android.externalstorage.documents/document/primary%3Asitest")

    fun indexSubfolders(root: DocumentFile): List<DocumentFile> = try {
        root.listFiles().filter { it.isDirectory }
    } catch (e: Exception) {
        emptyList()
    }

    fun folderCandidates(domain: String, email: String = ""): List<String> {
        val out = linkedSetOf<String>()
        val d = domain.trim().removePrefix("www.").lowercase()
        val local = email.substringBefore('@').trim().lowercase()

        fun addDomainVariants(dom: String) {
            if (dom.isBlank()) return
            out.add(dom)
            out.add(dom.replace(".", "_"))
            out.add(dom.replace(".", "-"))
            val stem = dom.substringBefore('.')
            if (stem.length >= 3) out.add(stem)
        }

        if (d.isNotBlank() && d !in freeEmailDomains) {
            addDomainVariants(d)
            // Strip subdomains progressively (e.g., mail.company.ir -> company.ir)
            val labels = d.split('.')
            if (labels.size > 2) {
                for (i in 1 until labels.size - 1) {
                    val parent = labels.subList(i, labels.size).joinToString(".")
                    if (DomainValidator.isValid(parent)) addDomainVariants(parent)
                }
            }
        }
        if (local.length >= 3) {
            out.add(local)
            out.add(local.replace(".", "_"))
            out.add(local.replace("-", "_"))
        }
        if (d in freeEmailDomains && d.isNotBlank()) {
            addDomainVariants(d)
        }
        return out.toList()
    }

    fun findFolder(root: DocumentFile, domain: String, email: String = ""): DocumentFile? =
        findFolderInIndex(indexSubfolders(root), domain, email)

    fun findFolderInIndex(
        subfolders: List<DocumentFile>,
        domain: String,
        email: String = ""
    ): DocumentFile? {
        if (subfolders.isEmpty()) return null
        val byName = subfolders.associateBy { it.name?.trim()?.lowercase().orEmpty() }
        val candidates = folderCandidates(domain, email)

        // 1. Exact candidate match in priority order (domain > root domain > stem > local)
        for (c in candidates) {
            byName[c]?.let { return it }
        }

        // 2. Prefix match (e.g., folder "numberland.ir_2026" matches "numberland.ir")
        for (c in candidates) {
            if (c.length < 3) continue
            subfolders.firstOrNull { f ->
                val fn = f.name?.trim()?.lowercase().orEmpty()
                fn.startsWith(c) || c.startsWith(fn)
            }?.let { return it }
        }

        // 3. Substring match on meaningful stems (>= 4 chars)
        for (c in candidates) {
            if (c.length < 4) continue
            subfolders.firstOrNull { f ->
                val fn = f.name?.trim()?.lowercase().orEmpty()
                fn.contains(c)
            }?.let { return it }
        }

        return null
    }

    /** Lists all PDFs in [folder] sorted NEWEST FIRST by filename timestamp / lastModified. */
    fun pdfsIn(folder: DocumentFile): List<DocumentFile> =
        pdfsFrom(folder.listFiles())

    private fun pdfsFrom(files: Array<DocumentFile>): List<DocumentFile> =
        files
            .filter { it.isFile && it.name?.lowercase()?.endsWith(".pdf") == true }
            .sortedWith(
                compareByDescending<DocumentFile> {
                    maxOf(
                        AttachmentPolicy.extractFilenameTimestamp(it.name.orEmpty()),
                        it.lastModified()
                    )
                }
                    .thenByDescending { AttachmentPolicy.extractFilenameTimestamp(it.name.orEmpty()) }
                    .thenByDescending { it.lastModified() }
                    .thenByDescending {
                        val n = it.name.orEmpty()
                        if (AttachmentPolicy.isTrialName(n) && !AttachmentPolicy.isFullName(n)) 1 else 0
                    }
                    .thenBy { it.name?.lowercase().orEmpty() }
            )

    fun jsonIn(folder: DocumentFile): DocumentFile? =
        folder.listFiles().firstOrNull { it.isFile && it.name?.lowercase()?.endsWith(".json") == true }

    internal const val MAX_PDFS_FOR_AI = 2
    internal const val MAX_SIDECAR_BYTES = 256 * 1024

    /** Main entry: strictly reads only the 2 newest PDFs per domain folder (prioritizing
     *  the newest matching bilingual trial pair), plus an optional JSON sidecar.
     *  Uses Room [pdfCacheDao] when available so unchanged PDFs are not re-parsed by PDFBox. */
    suspend fun readBundle(
        context: Context,
        domain: String,
        folder: DocumentFile,
        pdfCacheDao: PdfCacheDao? = null
    ): ReportBundle {
        val warnings = mutableListOf<String>()
        val docs = mutableListOf<PdfDoc>()
        val children = try { folder.listFiles() } catch (_: Exception) { emptyArray() }

        val sidecar = children.firstOrNull { it.isFile && it.name?.lowercase()?.endsWith(".json") == true }?.let { f ->
            try {
                context.contentResolver.openInputStream(f.uri)?.use { input ->
                    readBoundedText(input, MAX_SIDECAR_BYTES)
                }
            } catch (e: Exception) {
                warnings.add("خواندن فایل JSON کنار گزارش ناموفق بود: " + f.name)
                null
            }
        }

        val allPdfFiles = pdfsFrom(children)
        val metadata = allPdfFiles.map { file ->
            PdfDoc(
                name = file.name ?: "report.pdf",
                uri = file.uri.toString(),
                lastModified = file.lastModified(),
                sizeBytes = file.length()
            )
        }
        val prioritizedUris = AttachmentPolicy.selectTrialCandidates(metadata).map { it.uri }.toSet()
        val prioritizedTrialFiles = allPdfFiles.filter { it.uri.toString() in prioritizedUris }
        val remainingFiles = allPdfFiles.filterNot { it.uri.toString() in prioritizedUris }
        val toParse = (prioritizedTrialFiles + remainingFiles)
            .distinctBy { it.uri.toString() }
            .take(MAX_PDFS_FOR_AI)

        for (f in toParse) {
            val name = f.name ?: "report.pdf"
            val mod = f.lastModified()
            val len = f.length()
            val uriStr = f.uri.toString()

            val cached = try {
                pdfCacheDao?.get(uriStr)?.takeIf {
                    it.lastModified == mod && it.sizeBytes == len && (it.pages == null || it.pages > 0)
                }
            } catch (_: Exception) { null }

            if (cached != null) {
                docs.add(
                    PdfDoc(
                        name = name,
                        uri = uriStr,
                        pages = cached.pages,
                        text = cached.text,
                        readOk = true,
                        lastModified = mod,
                        sizeBytes = len
                    )
                )
                continue
            }

            try {
                val stream = context.contentResolver.openInputStream(f.uri)
                if (stream == null) {
                    docs.add(PdfDoc(name, uriStr, readOk = false, lastModified = mod, sizeBytes = len))
                    warnings.add("باز کردن $name ناموفق بود")
                    continue
                }
                stream.use { ins ->
                    PDDocument.load(ins).use { doc ->
                        val pages = try { doc.numberOfPages } catch (e: Exception) { null }
                        val text = try {
                            normalizeText(PDFTextStripper().getText(doc) ?: "")
                        } catch (e: Exception) {
                            ""
                        }
                        docs.add(
                            PdfDoc(
                                name = name,
                                uri = uriStr,
                                pages = pages,
                                text = text,
                                readOk = true,
                                lastModified = mod,
                                sizeBytes = len
                            )
                        )
                        try {
                            pdfCacheDao?.upsert(
                                PdfCacheEntity(
                                    uri = uriStr,
                                    name = name,
                                    lastModified = mod,
                                    sizeBytes = len,
                                    pages = pages,
                                    text = text,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        } catch (_: Exception) { }
                        if (text.isBlank()) warnings.add("متنی از «$name» استخراج نشد (فایل پیوست همچنان ارسال می‌شود)")
                    }
                }
            } catch (e: Exception) {
                docs.add(PdfDoc(name, uriStr, readOk = false, lastModified = mod, sizeBytes = len))
                warnings.add("پردازش «$name» ناموفق: " + (e.message?.take(80) ?: ""))
            }
        }

        val sidecarData = sidecar?.let { parseSidecar(domain, it) }
        val scannedData = parsePdfFacts(domain, docs)
        val report = merge(sidecarData, scannedData)

        val selected = AttachmentPolicy.selectTrialPair(docs)
        if (allPdfFiles.isEmpty()) {
            warnings.add("هیچ PDF در پوشه ${folder.name} پیدا نشد")
        }
        if (allPdfFiles.size > MAX_PDFS_FOR_AI) {
            warnings.add("از ${allPdfFiles.size} فایل PDF در پوشه، فقط ۲ نسخه جدیدترین برای تحلیل هوش مصنوعی خوانده شد")
        }
        if (selected.isEmpty() && allPdfFiles.isNotEmpty()) {
            warnings.add("جفت معتبر فارسی/انگلیسی trial با دقیقاً دو صفحه و قابلیت خواندن پیدا نشد؛ هیچ PDFای پیوست نخواهد شد")
        }

        return ReportBundle(
            domain = domain,
            folderName = folder.name,
            data = report,
            pdfs = docs,
            sidecarText = sidecar,
            warnings = warnings
        )
    }

    /** Kept for compatibility: analyse a single PDF URI. */
    fun parsePdfText(context: Context, domain: String, uri: Uri): ReportData? = try {
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        stream.use { ins ->
            PDDocument.load(ins).use { doc ->
                val pages = try { doc.numberOfPages } catch (e: Exception) { null }
                val text = normalizeText(PDFTextStripper().getText(doc) ?: "")
                val name = AttachmentPolicy.displayName(uri.toString())
                parsePdfFacts(domain, listOf(PdfDoc(name, uri.toString(), pages, text)))
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Sidecar files are untrusted input. Enforce a byte ceiling before parsing,
     * and never let a sidecar for another customer/domain become authoritative.
     */
    internal fun readBoundedText(input: InputStream, maxBytes: Int): String {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("Sidecar exceeds the allowed size")
            output.write(buffer, 0, count)
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    /** JSON sidecar is authoritative for validated numbers; PDF text is still used for evidence. */
    internal fun parseSidecar(domain: String, text: String): ReportData? {
        return try {
            val o = JSONObject(text)
            val expectedDomain = domain.trim().lowercase().removePrefix("www.").trimEnd('.')
            val declaredDomain = o.optString("domain", expectedDomain)
                .trim().lowercase().removePrefix("www.").trimEnd('.')
            if (declaredDomain.isBlank() || !declaredDomain.equals(expectedDomain, ignoreCase = true)) {
                null
            } else ReportData(
            domain = expectedDomain,
            scanId = jStr(o, "scan_id"),
            score = jInt(o, "score"),
            findings = jInt(o, "findings"),
            critical = jInt(o, "critical"),
            high = jInt(o, "high"),
            medium = jInt(o, "medium"),
            low = jInt(o, "low"),
            freeFindingTitle = jStr(o, "free_finding_title"),
            freeFindingCvss = jStr(o, "free_finding_cvss"),
            freeFindingImpact = jStr(o, "free_finding_impact"),
            freeFindingSolution = jStr(o, "free_finding_solution"),
            subdomains = jInt(o, "subdomains")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun jInt(o: JSONObject, key: String): Int? {
        val value = when (val raw = o.opt(key)) {
            is Number -> {
                val number = raw.toDouble()
                if (!number.isFinite() || number % 1.0 != 0.0 || number < 0.0 || number > 100_000.0) {
                    return null
                }
                number.toInt()
            }
            is String -> raw.trim().takeIf { it.matches(Regex("""[0-9]{1,6}""")) }?.toIntOrNull()
            else -> null
        } ?: return null

        if (key == "score" && value !in 0..100) return null
        return value
    }

    private fun jStr(o: JSONObject, key: String): String? =
        o.optString(key, "").trim().takeIf { it.isNotBlank() && it != "null" }

    private fun merge(base: ReportData?, extra: ReportData?): ReportData? {
        if (base == null) return extra
        if (extra == null) return base
        return base.copy(
            scanId = base.scanId ?: extra.scanId,
            score = base.score ?: extra.score,
            findings = base.findings ?: extra.findings,
            critical = base.critical ?: extra.critical,
            high = base.high ?: extra.high,
            medium = base.medium ?: extra.medium,
            low = base.low ?: extra.low,
            freeFindingTitle = base.freeFindingTitle ?: extra.freeFindingTitle,
            freeFindingCvss = base.freeFindingCvss ?: extra.freeFindingCvss,
            freeFindingImpact = base.freeFindingImpact ?: extra.freeFindingImpact,
            freeFindingSolution = base.freeFindingSolution ?: extra.freeFindingSolution,
            subdomains = base.subdomains ?: extra.subdomains,
            posture = base.posture ?: extra.posture,
            secondaryFindingTitle = base.secondaryFindingTitle ?: extra.secondaryFindingTitle,
            secondaryFindingCvss = base.secondaryFindingCvss ?: extra.secondaryFindingCvss,
            secondaryFindingImpact = base.secondaryFindingImpact ?: extra.secondaryFindingImpact,
            secondaryFindingSolution = base.secondaryFindingSolution ?: extra.secondaryFindingSolution,
            threatExposure = base.threatExposure ?: extra.threatExposure,
            securityVectors = base.securityVectors ?: extra.securityVectors,
            owaspSummary = base.owaspSummary ?: extra.owaspSummary,
            lockedSummary = base.lockedSummary ?: extra.lockedSummary,
            wafStatus = base.wafStatus ?: extra.wafStatus
        )
    }

    /** Extract real facts from the parsed PDF subset (at most 2 latest PDFs; never invents numbers). */
    fun parsePdfFacts(domain: String, docs: List<PdfDoc>): ReportData? {
        val readableDocs = docs.filter { it.readOk && it.text.isNotBlank() }.take(MAX_PDFS_FOR_AI)
        if (readableDocs.isEmpty()) return null

        // Prefer English version first when pairing so non-RTL-reversed Latin titles/headers parse cleanly,
        // while keeping Persian text immediately available for bilingual extraction.
        val orderedDocs = readableDocs.sortedBy { if (AttachmentPolicy.isPersianName(it.name)) 1 else 0 }
        val combined = normalizeText(orderedDocs.joinToString("\n") { it.text })

        val score = firstInt(combined, listOf(
            Regex("""([0-9]{1,3})\s*/\s*100"""),
            Regex("""(?:نمره\s*کلی|امتیاز(?:\s*امنیتی)?|Overall\s*Grade|Overall\s*Score|Security\s*Score|Score)[^0-9\n]{0,20}([0-9]{1,3})\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:نمره\s*کلی|امتیاز|Overall\s*Grade|Overall\s*Score|Security\s*Score)\s*(?:0\s+100\s+)?([0-9]{1,3})\b""", RegexOption.IGNORE_CASE)
        ))?.takeIf { it in 0..100 }

        val findings = firstInt(combined, listOf(
            Regex("""(?:در\s*مجموع|تعداد\s*کل\s*یافته[‌\s]*ها)\s*([0-9]+)\s*یافته"""),
            Regex("""([0-9]+)\s*finding\(s\)\s*in\s*total""", RegexOption.IGNORE_CASE),
            Regex("""(?:Total|کل)\s*([0-9]+)\s*/\s*(?:shown|نمایش)""", RegexOption.IGNORE_CASE),
            Regex("""(?:تعداد\s*یافته|Total\s*Findings|Findings)[^0-9\n]{0,20}([0-9]+)""", RegexOption.IGNORE_CASE),
            Regex("""([0-9]+)\s*(?:یافته|findings?)""", RegexOption.IGNORE_CASE)
        ))

        // KPI cards place the count ABOVE the label (e.g., "0\nCRITICAL\n0\nHIGH\n8\nMEDIUM\n4\nLOW"
        // or "4\nکم\n8\nمتوسط\n0\nزیاد\n0\nبحرانی"). Match count-before-label FIRST, and keep
        // label-before-count strictly single-line ([ \t:]*) so High never grabs Medium's number.
        val critical = firstInt(combined, listOf(
            Regex("""\b([0-9]+)\s*(?:بحرانی|Critical)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:بحرانی|Critical)[ \t:=-]{1,10}([0-9]+)\b""", RegexOption.IGNORE_CASE)
        ))
        val high = firstInt(combined, listOf(
            Regex("""\b([0-9]+)\s*(?:زیاد|High)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:زیاد|High)[ \t:=-]{1,10}([0-9]+)\b""", RegexOption.IGNORE_CASE)
        ))
        val medium = firstInt(combined, listOf(
            Regex("""\b([0-9]+)\s*(?:متوسط|Medium)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b([0-9]+)[ \t]+(?:متوسط|Medium)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:متوسط|Medium)[ \t:=-]{1,10}([0-9]+)\b""", RegexOption.IGNORE_CASE)
        ))
        val low = firstInt(combined, listOf(
            Regex("""\b([0-9]+)\s*(?:کم|Low)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:کم|Low)[ \t:=-]{1,10}([0-9]+)\b""", RegexOption.IGNORE_CASE)
        ))

        val subdomains = firstInt(combined, listOf(
            Regex("""(?:زیردامنه|Subdomains?)[^0-9\n]{0,18}([0-9]+)""", RegexOption.IGNORE_CASE),
            Regex("""([0-9]+)[ \t]*(?:زیردامنه|Subdomains?)""", RegexOption.IGNORE_CASE)
        ))

        val scanId = Regex("""(?:scan[_ -]?(?:id)?|شناسه[ \t]*اسکن)[ \t:=-]{0,8}([A-Za-z0-9-]{8,40})""", RegexOption.IGNORE_CASE)
            .find(combined)?.groupValues?.get(1)
            ?: Regex("""([a-f0-9]{16,32})[ \t]*(?:اسکن\s*شناسه|Scan\s*ID)""", RegexOption.IGNORE_CASE)
                .find(combined)?.groupValues?.get(1)
            ?: Regex("""[a-f0-9]{16,32}""", RegexOption.IGNORE_CASE).find(combined)?.value

        val posture = when {
            Regex("""(?:OVERALL POSTURE|وضعیت کلی امنیت)\s*(?:WEAK|ضعیف)""", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "ضعیف (WEAK)"
            Regex("""(?:OVERALL POSTURE|وضعیت کلی امنیت)\s*(?:CRITICAL|بحرانی)""", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "بحرانی (CRITICAL)"
            Regex("""(?:OVERALL POSTURE|وضعیت کلی امنیت)\s*(?:MODERATE|MEDIUM|متوسط)""", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "متوسط (MODERATE)"
            Regex("""(?:OVERALL POSTURE|وضعیت کلی امنیت)\s*(?:STRONG|GOOD|قوی|مناسب)""", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "مناسب (STRONG)"
            else -> null
        }

        // Parse Proven Findings (Finding 1 and Finding 2)
        val finding1 = parseProvenFindingBlock(combined, 1)
        val finding2 = parseProvenFindingBlock(combined, 2)

        val lines = combined.lines().map { it.trim() }.filter { it.length > 8 }
        val issueWords = listOf(
            "DMARC", "SPF", "DKIM", "HSTS", "CSP", "Content-Security-Policy", "TLS", "SSL",
            "X-Frame-Options", "SQL Injection", "XSS", "Open Redirect", "CORS", "Subdomain Takeover",
            "نفوذ", "آسیب", "تزریق", "جعل", "نشت", "پیکربندی"
        )
        val fallbackTitle = lines.firstOrNull { l ->
            issueWords.any { l.contains(it, ignoreCase = true) } &&
                !l.startsWith("REPRODUCE", true) && !l.startsWith("curl", true) && !l.startsWith("dig", true)
        }?.take(180)
        val fallbackCvss = Regex("""CVSS[^0-9\n]{0,20}([0-9]{1,2}(?:[.][0-9])?)""", RegexOption.IGNORE_CASE)
            .find(combined)?.groupValues?.get(1)
            ?: Regex("""([0-9]{1,2}[.][0-9])[ \t]*CVSS""", RegexOption.IGNORE_CASE)
                .find(combined)?.groupValues?.get(1)
        val fallbackImpact = labelledLine(lines, listOf("Impact:", "تاثیر:", "پیامد:", "Consequence:"))
        val fallbackSolution = labelledLine(lines, listOf("RECOMMENDED FIX", "راه حل پیشنهادی", "راهحل پیشنهادی", "Remediation:", "Solution:"))

        // Business threat exposure percentages
        val infraExp = firstDecimalPercent(combined, listOf(
            Regex("""Infrastructure[^\n]{0,45}?([0-9]{1,3}(?:\.[0-9]+)?)%""", RegexOption.IGNORE_CASE),
            Regex("""زیرساخت[^\n]{0,45}?([0-9]{1,3}(?:\.[0-9]+)?)%""")
        ))
        val dataExp = firstDecimalPercent(combined, listOf(
            Regex("""Data\s*breach[^\n]{0,45}?([0-9]{1,3}(?:\.[0-9]+)?)%""", RegexOption.IGNORE_CASE),
            Regex("""نشت\s*داده[^\n]{0,45}?([0-9]{1,3}(?:\.[0-9]+)?)%""")
        ))
        val clientExp = firstDecimalPercent(combined, listOf(
            Regex("""Client-side[^\n]{0,45}?([0-9]{1,3}(?:\.[0-9]+)?)%""", RegexOption.IGNORE_CASE),
            Regex("""سمت\s*کلاینت[^\n]{0,45}?([0-9]{1,3}(?:\.[0-9]+)?)%""")
        ))
        val threatExposure = listOfNotNull(
            clientExp?.let { "سمت کلاینت (XSS/فیشینگ): $it%" },
            dataExp?.let { "نشت داده / جعل ایمیل: $it%" },
            infraExp?.let { "زیرساخت (نفوذ/باج‌افزار): $it%" }
        ).takeIf { it.isNotEmpty() }?.joinToString(" | ")

        // Security Vectors
        val vectorMatches = listOf(
            "Email Auth" to Regex("""Email\s*Auth\s*([0-9]{1,3})%""", RegexOption.IGNORE_CASE),
            "Headers" to Regex("""Headers\s*([0-9]{1,3})%""", RegexOption.IGNORE_CASE),
            "Cookies" to Regex("""Cookies\s*([0-9]{1,3})%""", RegexOption.IGNORE_CASE),
            "SSL/TLS" to Regex("""SSL/TLS\s*([0-9]{1,3})%""", RegexOption.IGNORE_CASE),
            "Data Leak" to Regex("""Data\s*Leak\s*([0-9]{1,3})%""", RegexOption.IGNORE_CASE),
            "Network" to Regex("""Network\s*([0-9]{1,3})%""", RegexOption.IGNORE_CASE)
        ).mapNotNull { (label, rx) ->
            rx.find(combined)?.groupValues?.getOrNull(1)?.let { "$label $it%" }
        }
        val securityVectors = vectorMatches.takeIf { it.isNotEmpty() }?.joinToString(" | ")

        // OWASP Top 10 non-zero counts
        val owaspItems = mutableListOf<String>()
        val owaspRx = Regex("""(A0[1-9]|A10)\b[^\n]{0,45}?\b([1-9][0-9]*)\b""")
        val seenOwasp = linkedSetOf<String>()
        for (m in owaspRx.findAll(combined)) {
            val code = m.groupValues[1].uppercase()
            val cnt = m.groupValues[2].toIntOrNull() ?: continue
            if (cnt in 1..99 && seenOwasp.add(code)) {
                owaspItems.add("$code: $cnt")
            }
        }
        val owaspSummary = owaspItems.takeIf { it.isNotEmpty() }?.joinToString("، ")

        // Locked summary
        val lockedSummary = Regex("""([0-9]+)\s*more\s*finding\(s\)\s*are\s*locked[^\n.:]{0,20}:\s*([^\n.]+)""", RegexOption.IGNORE_CASE)
            .find(combined)?.let { m -> "${m.groupValues[1]} یافته قفل (${m.groupValues[2].trim()})" }
            ?: Regex("""([0-9]+)\s*یافته[‌\s]*ی?\s*بیشتر[^\n:]{0,35}قفل\s*است\s*:\s*([^\n.]+)""")
                .find(combined)?.let { m -> "${m.groupValues[1]} یافته قفل (${m.groupValues[2].trim()})" }

        // WAF status
        val wafStatus = when {
            Regex("""WAF\s*LAYER\s*NOT\s*DETECTED""", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "بدون لایه WAF (NOT DETECTED)"
            Regex("""WAF\s*LAYER\s*DETECTED""", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "WAF فعال (DETECTED)"
            else -> null
        }

        return ReportData(
            domain = domain,
            scanId = scanId,
            score = score,
            findings = findings,
            critical = critical,
            high = high,
            medium = medium,
            low = low,
            freeFindingTitle = finding1?.title ?: fallbackTitle,
            freeFindingCvss = finding1?.cvss ?: fallbackCvss,
            freeFindingImpact = finding1?.impact ?: fallbackImpact,
            freeFindingSolution = finding1?.fix ?: fallbackSolution,
            subdomains = subdomains,
            posture = posture,
            secondaryFindingTitle = finding2?.title,
            secondaryFindingCvss = finding2?.cvss,
            secondaryFindingImpact = finding2?.impact,
            secondaryFindingSolution = finding2?.fix,
            threatExposure = threatExposure,
            securityVectors = securityVectors,
            owaspSummary = owaspSummary,
            lockedSummary = lockedSummary,
            wafStatus = wafStatus
        )
    }

    private data class ParsedProvenFinding(
        val title: String?,
        val cvss: String?,
        val impact: String?,
        val fix: String?
    )

    private fun parseProvenFindingBlock(combined: String, index: Int): ParsedProvenFinding? {
        val startRx = if (index == 1) {
            Regex("""(?:PROVEN FINDING\s*(?:#\s*1|1\s*OF\s*2)|یافته[‌\s]*ی?\s*اثبات\s*شده\s*(?:#\s*1|1\s*از\s*2))""", RegexOption.IGNORE_CASE)
        } else {
            Regex("""(?:PROVEN FINDING\s*(?:#\s*2|2\s*OF\s*2)|یافته[‌\s]*ی?\s*اثبات\s*شده\s*(?:#\s*2|2\s*از\s*2))""", RegexOption.IGNORE_CASE)
        }
        val startMatch = startRx.find(combined) ?: return null
        val tail = combined.substring(startMatch.range.first)
        val endRx = if (index == 1) {
            Regex("""(?:PROVEN FINDING\s*(?:#\s*2|2\s*OF\s*2)|یافته[‌\s]*ی?\s*اثبات\s*شده\s*(?:#\s*2|2\s*از\s*2)|02\s+Business threat exposure)""", RegexOption.IGNORE_CASE)
        } else {
            Regex("""(?:02\s+Business threat exposure|Business threat exposure|02\s+میزان مواجهه|SaliSecure Core)""", RegexOption.IGNORE_CASE)
        }
        val endMatch = endRx.find(tail, startIndex = 10)
        val block = if (endMatch != null) tail.substring(0, endMatch.range.first) else tail.take(1500)
        val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }

        val topChunk = lines.take(4).joinToString(" ")
        val cvss = Regex("""CVSS[^0-9\n]{0,8}([0-9]{1,2}(?:\.[0-9])?)""", RegexOption.IGNORE_CASE)
            .find(topChunk)?.groupValues?.get(1)
            ?: Regex("""([0-9]{1,2}\.[0-9])[ \t]*CVSS""", RegexOption.IGNORE_CASE)
                .find(topChunk)?.groupValues?.get(1)

        // Title is typically the next meaningful line right after the PROVEN FINDING header
        val title = lines.drop(1).firstOrNull { l ->
            !l.equals("WHY IT MATTERS", true) &&
                !l.equals("چرا مهم است", true) &&
                !l.startsWith("MEDIUM", true) &&
                !l.startsWith("HIGH", true) &&
                !l.startsWith("CRITICAL", true) &&
                !l.startsWith("LOW", true) &&
                l.length > 4
        }?.take(180)

        val impact = labelledLine(lines, listOf("Impact:", "تاثیر:", "What could happen:"))
        val fixIdx = lines.indexOfFirst {
            it.equals("RECOMMENDED FIX", true) || it.contains("راهحل پیشنهادی") || it.contains("راه حل پیشنهادی")
        }
        val fix = if (fixIdx >= 0 && fixIdx + 1 < lines.size) {
            lines[fixIdx + 1].take(200)
        } else {
            labelledLine(lines, listOf("RECOMMENDED FIX", "راهحل پیشنهادی", "راه حل پیشنهادی", "HOW TO FIX:"))
        }
        return ParsedProvenFinding(title, cvss, impact, fix)
    }

    private fun firstDecimalPercent(text: String, patterns: List<Regex>): String? {
        for (p in patterns) {
            val raw = p.find(text)?.groupValues?.getOrNull(1)?.trim() ?: continue
            val d = raw.toDoubleOrNull() ?: continue
            if (d in 0.0..100.0) return raw
        }
        return null
    }

    private fun labelledLine(lines: List<String>, labels: List<String>): String? {
        for (l in lines) {
            for (k in labels) {
                val i = l.indexOf(k, ignoreCase = true)
                if (i >= 0 && l.length > i + k.length + 5) {
                    return l.substring(i).take(220)
                }
            }
        }
        return null
    }

    private fun firstInt(text: String, patterns: List<Regex>): Int? {
        for (p in patterns) {
            val v = p.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (v != null && v in 0..100000) return v
        }
        return null
    }

    /** Normalise Persian/Arabic digits and letters, keep line structure. */
    private fun normalizeText(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(
                when (ch) {
                    '۰' -> '0'; '۱' -> '1'; '۲' -> '2'; '۳' -> '3'; '۴' -> '4'
                    '۵' -> '5'; '۶' -> '6'; '۷' -> '7'; '۸' -> '8'; '۹' -> '9'
                    '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'
                    '٥' -> '5'; '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'
                    'أ', 'إ', 'آ' -> 'ا'; 'ة' -> 'ه'; 'ي', 'ى' -> 'ی'; 'ك' -> 'ک'
                    '\u200C', '\u200F', '\u200E' -> ' '
                    else -> ch
                }
            )
        }
        return sb.toString().replace(Regex("""[ \t]+"""), " ")
    }
}

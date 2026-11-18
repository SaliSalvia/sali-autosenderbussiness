package ir.salisec.autosender.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ForwardToInbox
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import ir.salisec.autosender.App
import ir.salisec.autosender.CampaignDraftItem
import ir.salisec.autosender.data.AggregateStats
import ir.salisec.autosender.data.AttachmentGuard
import ir.salisec.autosender.data.AttachmentSnapshotReader
import ir.salisec.autosender.data.ContactsParser
import ir.salisec.autosender.data.DomainMatch
import ir.salisec.autosender.data.MessageEntity
import ir.salisec.autosender.data.Prefs
import ir.salisec.autosender.data.ReportBundle
import ir.salisec.autosender.data.ReportReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CampaignStudioScreen(
    app: App,
    onOpenSettings: () -> Unit,
    onGoToSend: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val rawInput by app.session.rawEmailsInput.collectAsState()
    val matches by app.session.matches.collectAsState()
    val selected by app.session.selected.collectAsState()
    val drafts by app.session.drafts.collectAsState()
    val persistedDrafts by app.session.persistedDrafts.collectAsState()

    var step by remember { mutableIntStateOf(if (drafts.isNotEmpty()) 1 else 0) }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var sitestUri by remember { mutableStateOf(app.prefs.load().sitestFolderUri) }
    var matchSearch by remember { mutableStateOf("") }
    var matchFilter by remember { mutableStateOf("ALL") } // ALL | READY | MISSING
    var draftSearch by remember { mutableStateOf("") }
    var draftFilter by remember { mutableStateOf("ALL") } // ALL | READY | APPROVED | FAILED

    var batchGenerating by remember { mutableStateOf(false) }
    var batchProgressText by remember { mutableStateOf("") }
    var genError by remember { mutableStateOf<String?>(null) }
    var testNotice by remember { mutableStateOf<String?>(null) }
    var activeModelLabel by remember { mutableStateOf(app.prefs.load().hcnsModel) }
    var defaultStyle by remember { mutableStateOf("BUSINESS") }

    var previewPdfUri by remember { mutableStateOf<String?>(null) }
    var previewHtmlItem by remember { mutableStateOf<CampaignDraftItem?>(null) }
    var showTemplatesDialog by remember { mutableStateOf(false) }
    var approvingKeys by remember { mutableStateOf(emptySet<String>()) }

    BackHandler(enabled = step == 1) { step = 0 }

    val suppressed = remember { app.prefs.suppressedSet() }
    val allParsedContacts = remember(rawInput) { ContactsParser.parse(rawInput) }
    val invalidInputLines = remember(rawInput) {
        rawInput.lines().count { line ->
            line.isNotBlank() && !ContactsParser.isHeaderLine(line) && ContactsParser.parse(line).isEmpty()
        }
    }
    val contacts = remember(allParsedContacts, suppressed) {
        allParsedContacts.filter {
            !suppressed.contains(it.email.lowercase()) && !suppressed.contains(it.domain.lowercase())
        }
    }
    val blockedCount = allParsedContacts.size - contacts.size

    // Keep session contacts & domains synchronized with parsed emails
    LaunchedEffect(contacts) {
        app.session.contacts.value = contacts.associateBy { it.email.ifBlank { it.domain } }
        app.session.domains.value = contacts.map { it.domain }.distinct()
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val content = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                }
                if (content.isNotBlank()) app.session.rawEmailsInput.value = content
            }
        }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val s = app.prefs.load()
            app.prefs.save(s.copy(sitestFolderUri = uri.toString()))
            sitestUri = uri.toString()
        }
    }

    fun launchSitestPicker() {
        val initUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ReportReader.initialSitestTreeUri()
        } else null
        treePicker.launch(initUri)
    }

    fun openPdfPreview(uriString: String) {
        previewPdfUri = uriString
    }

    fun openExternalPdf(uriString: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uriString), "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            scanError = "اپلیکیشنی برای باز کردن PDF روی گوشی پیدا نشد."
        }
    }

    fun runAutoMatchScan() {
        if (contacts.isEmpty() || sitestUri.isBlank()) return
        scope.launch {
            scanning = true
            scanError = null
            try {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(sitestUri))
                if (root == null || !root.canRead()) {
                    scanError = "دسترسی به پوشه sitest معتبر نیست؛ لطفاً پوشه را دوباره انتخاب کنید."
                    scanning = false
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    val subfolders = ReportReader.indexSubfolders(root)
                    val resolvedFolders = contacts.associate { c ->
                        c.email to ReportReader.findFolderInIndex(subfolders, c.domain, c.email)
                    }
                    val uniqueFolderTargets = resolvedFolders.values
                        .filterNotNull()
                        .distinctBy { it.uri.toString() }

                    val gate = Semaphore(4)
                    val pdfDao = app.db.pdfCacheDao()
                    val bundleByUri = coroutineScope {
                        uniqueFolderTargets.map { folder ->
                            async {
                                gate.withPermit {
                                    val domainHint = folder.name ?: ""
                                    folder.uri.toString() to (
                                        folder.name to ReportReader.readBundle(context, domainHint, folder, pdfDao)
                                    )
                                }
                            }
                        }.awaitAll()
                    }.toMap()

                    contacts.map { c ->
                        val folder = resolvedFolders[c.email]
                        val pair = folder?.let { bundleByUri[it.uri.toString()] }
                        val folderName = pair?.first ?: folder?.name
                        val bundle = pair?.second?.copy(domain = c.domain) ?: ReportBundle(
                            domain = c.domain,
                            warnings = listOf("پوشه‌ای با نام «${c.domain}» یا مشابه در sitest پیدا نشد")
                        )
                        DomainMatch(
                            domain = c.domain,
                            email = c.email,
                            contactName = c.name,
                            folderName = folderName,
                            bundle = bundle,
                            report = bundle.data,
                            hasEmail = true
                        )
                    }
                }
                app.session.matches.value = result
                val readyKeys = result.filter { it.readyForApproval }.map { it.key }.toSet()
                val preserved = app.session.selected.value.intersect(readyKeys)
                app.session.selected.value = if (preserved.isNotEmpty()) preserved else readyKeys
                val completedScanFingerprint = withContext(Dispatchers.IO) {
                    val latestRoot = DocumentFile.fromTreeUri(context, Uri.parse(sitestUri))
                    latestRoot?.let { folderScanFingerprint(it, contactsSignature, sitestUri) }.orEmpty()
                }
                app.session.lastScanSignature = completedScanFingerprint
                app.session.lastScanSitestUri = sitestUri
            } catch (e: SecurityException) {
                scanError = "مجوز دسترسی به پوشه sitest منقضی شده؛ لطفاً دوباره انتخاب کنید."
            } catch (e: Exception) {
                scanError = "خطا در بررسی پوشه sitest: " + (e.message?.take(140) ?: "")
            }
            scanning = false
        }
    }

    // Automatic matching whenever emails or sitest folder changes (skip rescan if already scanned for same inputs)
    val contactsSignature = remember(contacts) { contactsSignatureForScan(contacts) }
    LaunchedEffect(contactsSignature, sitestUri) {
        if (contacts.isNotEmpty() && sitestUri.isNotBlank()) {
            val currentFolderFingerprint = withContext(Dispatchers.IO) {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(sitestUri))
                if (root == null || !root.canRead()) "" else folderScanFingerprint(root, contactsSignature, sitestUri)
            }
            if (currentFolderFingerprint.isNotBlank() &&
                app.session.lastScanSignature == currentFolderFingerprint &&
                app.session.lastScanSitestUri == sitestUri &&
                matches.isNotEmpty()
            ) {
                return@LaunchedEffect
            }
            delay(400)
            runAutoMatchScan()
        } else if (contacts.isEmpty()) {
            app.session.matches.value = emptyList()
            app.session.selected.value = emptySet()
            app.session.lastScanSignature = ""
            app.session.lastScanSitestUri = ""
        }
    }

    fun prepareDraftsAndGoToStep2() {
        val currentMap = drafts.associateBy { it.key }
        val s = app.prefs.load()
        val scores = matches.mapNotNull { it.report?.score }
        val aggregate = if (scores.isEmpty()) null else AggregateStats(scores.size, scores.average().toInt())

        val nextDrafts = selected.mapNotNull { key ->
            val m = matches.firstOrNull { it.key == key } ?: return@mapNotNull null
            currentMap[key]?.copy(match = m) ?: run {
                val saved = persistedDrafts[key]
                if (saved != null && saved.generated) {
                    val alts = try {
                        val arr = JSONArray(saved.subjectAltsJson)
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { x -> x.isNotBlank() } }
                    } catch (_: Exception) { emptyList() }
                    val restored = CampaignDraftItem(
                        key = key,
                        domain = m.domain,
                        email = m.email,
                        name = m.contactName,
                        match = m,
                        style = saved.style,
                        subject = saved.subject,
                        preheader = saved.preheader,
                        body = saved.body,
                        subjectAlternatives = alts,
                        generated = saved.generated,
                        approved = saved.approved,
                        messageUuid = saved.messageUuid,
                        modelUsed = saved.modelUsed,
                        subjectVariant = saved.subjectVariant.ifBlank { "PRIMARY" },
                        promptTokens = saved.promptTokens,
                        completionTokens = saved.completionTokens
                    )
                    val q = app.ai.check(
                        result = restored.toResult(),
                        s = s,
                        bundle = m.bundle,
                        aggregate = aggregate,
                        contactName = m.contactName,
                        isFollowUp = false,
                        domain = m.domain,
                        email = m.email
                    )
                    restored.copy(quality = q)
                } else {
                    CampaignDraftItem(
                        key = key,
                        domain = m.domain,
                        email = m.email,
                        name = m.contactName,
                        match = m,
                        style = defaultStyle
                    )
                }
            }
        }
        app.session.drafts.value = nextDrafts
        step = 1
    }

    // Automatically rebuild session drafts from Room `studio_drafts` when returning after process death
    LaunchedEffect(matches, persistedDrafts) {
        if (matches.isNotEmpty() && drafts.isEmpty() && persistedDrafts.isNotEmpty() && selected.isNotEmpty()) {
            val s = app.prefs.load()
            val scores = matches.mapNotNull { it.report?.score }
            val aggregate = if (scores.isEmpty()) null else AggregateStats(scores.size, scores.average().toInt())
            val rebuilt = selected.mapNotNull { key ->
                val m = matches.firstOrNull { it.key == key } ?: return@mapNotNull null
                val saved = persistedDrafts[key]?.takeIf { it.generated } ?: return@mapNotNull null
                val alts = try {
                    val arr = JSONArray(saved.subjectAltsJson)
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { x -> x.isNotBlank() } }
                } catch (_: Exception) { emptyList() }
                val restored = CampaignDraftItem(
                    key = key,
                    domain = m.domain,
                    email = m.email,
                    name = m.contactName,
                    match = m,
                    style = saved.style,
                    subject = saved.subject,
                    preheader = saved.preheader,
                    body = saved.body,
                    subjectAlternatives = alts,
                    generated = saved.generated,
                    approved = saved.approved,
                    messageUuid = saved.messageUuid,
                    modelUsed = saved.modelUsed,
                    subjectVariant = saved.subjectVariant.ifBlank { "PRIMARY" },
                    promptTokens = saved.promptTokens,
                    completionTokens = saved.completionTokens
                )
                val q = app.ai.check(
                    result = restored.toResult(),
                    s = s,
                    bundle = m.bundle,
                    aggregate = aggregate,
                    contactName = m.contactName,
                    isFollowUp = false,
                    domain = m.domain,
                    email = m.email
                )
                restored.copy(quality = q)
            }
            if (rebuilt.isNotEmpty()) {
                app.session.drafts.value = rebuilt
            }
        }
    }

    fun updateDraft(key: String, persist: Boolean = true, transform: (CampaignDraftItem) -> CampaignDraftItem) {
        val scores = app.session.matches.value.mapNotNull { it.report?.score }
        val aggregate = if (scores.isEmpty()) null else AggregateStats(scores.size, scores.average().toInt())
        val currentSettings = app.prefs.load()
        val transition = app.session.updateDraftAtomic(key) { current ->
            val next = transform(current)
            val reviewed = if (next.generated) {
                val q = app.ai.check(
                    result = next.toResult(),
                    s = currentSettings,
                    bundle = next.match.bundle,
                    aggregate = aggregate,
                    contactName = next.name,
                    isFollowUp = false,
                    domain = next.domain,
                    email = next.email
                )
                next.copy(quality = q)
            } else next
            if (current.approved) reviewed.copy(approved = false) else reviewed
        } ?: return

        val (before, after) = transition
        if (before.approved && !before.messageUuid.isNullOrBlank()) {
            val uuid = before.messageUuid
            scope.launch {
                app.db.messageDao().demote(uuid, "متن پس از تأیید ویرایش شد؛ نیاز به تأیید دوباره")
                app.engine.notifyQueueChanged()
            }
        }
        if (persist && after.generated) {
            app.persistDraft(after)
        }
    }

    suspend fun approveSingleDraft(item: CampaignDraftItem): String? {
        if (item.approved || approvingKeys.contains(item.key)) return null
        approvingKeys = approvingKeys + item.key
        try {
            if (item.match.attachmentUris.isEmpty()) return "${item.domain}: فایل PDF برای پیوست پیدا نشد"
            val nameProblem = AttachmentGuard.validateNames(item.match.attachmentNames)
            if (nameProblem != null) return "${item.domain}: $nameProblem"

        val scores = matches.mapNotNull { it.report?.score }
        val aggregate = if (scores.isEmpty()) null else AggregateStats(scores.size, scores.average().toInt())
        val q = app.ai.check(
            result = item.toResult(),
            s = app.prefs.load(),
            bundle = item.match.bundle,
            aggregate = aggregate,
            contactName = item.name,
            isFollowUp = false,
            domain = item.domain,
            email = item.email
        )
        if (!q.passed) {
            updateDraft(item.key) { it.copy(quality = q) }
            return "${item.domain}: " + q.blockers.joinToString(" | ")
        }
        val s = app.prefs.load()
        val maxAttachmentBytes = minOf(
            AttachmentGuard.MAX_PER_FILE_BYTES,
            s.maxAttachmentMb.coerceIn(1, 4) * 1024L * 1024L
        )
        val snapshots = try {
            withContext(Dispatchers.IO) {
                item.match.attachmentUris.map { uri ->
                    AttachmentSnapshotReader.read(context = app, uriString = uri, maxBytes = maxAttachmentBytes)
                }
            }
        } catch (e: Exception) {
            return "${item.domain}: خطا در خواندن PDF — ${e.message?.take(120)}"
        }

        val snapshotProblem = AttachmentGuard.validateFiles(
            names = item.match.attachmentNames,
            approvedPages = snapshots.map { it.pages },
            approvedSha256 = snapshots.map { it.sha256 },
            actualPages = snapshots.map { it.pages },
            payloads = snapshots.map { it.bytes },
            perFileCapMb = s.maxAttachmentMb
        )
        if (snapshotProblem != null) return "${item.domain}: $snapshotProblem"

        val uuid = item.messageUuid ?: UUID.randomUUID().toString()
        val existing = withContext(Dispatchers.IO) { app.db.messageDao().byUuid(uuid) }
        val entity = MessageEntity(
            id = existing?.id ?: 0L,
            domain = item.domain,
            email = item.email,
            name = item.name,
            subject = item.subject,
            preheader = item.preheader,
            body = item.body,
            pdfPaths = item.match.attachmentUris.joinToString("||"),
            attachmentNames = item.match.attachmentNames.joinToString("||"),
            attachmentPages = snapshots.joinToString("||") { it.pages.toString() },
            attachmentSha256 = snapshots.joinToString("||") { it.sha256 },
            evidenceHash = item.match.bundle.fingerprint(),
            qualityOk = true,
            messageUuid = uuid,
            campaignId = "active",
            status = "APPROVED",
            styleUsed = item.style,
            modelUsed = item.modelUsed,
            subjectVariant = item.subjectVariant,
            promptTokens = item.promptTokens,
            completionTokens = item.completionTokens
        )
        withContext(Dispatchers.IO) {
            if (existing != null) app.db.messageDao().update(entity)
            else app.db.messageDao().insert(entity)
        }
        app.engine.notifyQueueChanged()
        val transition = app.session.updateDraftAtomic(item.key) { current ->
            current.copy(approved = true, quality = q, messageUuid = uuid, error = null)
        }
        transition?.second?.let { app.persistDraft(it) }
            return null
        } finally {
            approvingKeys = approvingKeys - item.key
        }
    }

    fun generateBatch(keysToGenerate: List<String>) {
        if (batchGenerating || keysToGenerate.isEmpty()) return
        scope.launch {
            batchGenerating = true
            genError = null
            testNotice = null
            try {
                var s = app.prefs.load()
                if (s.hcnsKey.isBlank()) {
                    genError = "کلید API سایت hcnsec در تنظیمات وارد نشده است."
                    batchGenerating = false
                    return@launch
                }

                // 1. Probe & select strongest available hcnsec model if auto-select is enabled & unverified
                if (s.autoSelectBestModel && (!s.hcnsModelVerified || s.hcnsModel.isBlank())) {
                    batchProgressText = "در حال سنجش API hcnsec و انتخاب قوی‌ترین مدل در دسترس..."
                    val probeRes = app.ai.probeAndSelectBestModel(s)
                    probeRes.onSuccess { probe ->
                        s = s.copy(
                            hcnsModel = probe.selectedModel,
                            hcnsReviewModel = probe.reviewModel,
                            hcnsAvailableModels = probe.availableModels.joinToString(","),
                            hcnsModelVerified = true
                        )
                        app.prefs.save(s)
                        activeModelLabel = probe.selectedModel
                    }.onFailure { err ->
                        if (s.hcnsModel.isBlank()) {
                            genError = "سنجش مدل hcnsec ناموفق بود: ${err.message?.take(140)}"
                            batchGenerating = false
                            batchProgressText = ""
                            return@launch
                        }
                    }
                } else {
                    activeModelLabel = s.hcnsModel
                }

                val scores = matches.mapNotNull { it.report?.score }
                val aggregate = if (scores.isEmpty()) null else AggregateStats(scores.size, scores.average().toInt())
                val total = keysToGenerate.size
                val done = AtomicInteger(0)
                val gate = Semaphore(3)

                coroutineScope {
                    keysToGenerate.map { key ->
                        async {
                            gate.withPermit {
                                val snapshotItem = app.session.drafts.value.firstOrNull { it.key == key }
                                    ?: return@withPermit
                                updateDraft(key, persist = false) {
                                    it.copy(generating = true, error = null, statusNote = "جستجوی کسب‌وکار در You.com...")
                                }
                                try {
                                    val ctx = app.session.contexts.value[snapshotItem.domain]
                                        ?.takeIf { it.summary.isNotBlank() }
                                        ?: app.enricher.enrich(s, snapshotItem.domain).also { fresh ->
                                            if (fresh.summary.isNotBlank()) {
                                                app.session.contexts.update { it + (snapshotItem.domain to fresh) }
                                            }
                                        }

                                    updateDraft(key, persist = false) {
                                        it.copy(statusNote = "طراحی متن با ${activeModelLabel.ifBlank { "AI" }} (پاس کامل)...")
                                    }

                                    val r = app.ai.generate(
                                        s = s,
                                        bundle = snapshotItem.match.bundle,
                                        contactName = snapshotItem.name,
                                        context = ctx,
                                        aggregate = aggregate,
                                        style = snapshotItem.style,
                                        isFollowUp = false,
                                        domain = snapshotItem.domain,
                                        email = snapshotItem.email,
                                        onResolvedModel = { probe ->
                                            val updated = s.copy(
                                                hcnsModel = probe.selectedModel,
                                                hcnsReviewModel = probe.reviewModel,
                                                hcnsAvailableModels = probe.availableModels.joinToString(","),
                                                hcnsModelVerified = true
                                            )
                                            app.prefs.save(updated)
                                            scope.launch(Dispatchers.Main) {
                                                s = updated
                                                activeModelLabel = probe.selectedModel
                                            }
                                        }
                                    )
                                    updateDraft(key, persist = true) {
                                        it.copy(
                                            subject = r.subject,
                                            preheader = r.preheader,
                                            body = r.body,
                                            subjectAlternatives = r.subjectAlternatives,
                                            quality = r.quality,
                                            generated = true,
                                            approved = false,
                                            generating = false,
                                            statusNote = "",
                                            modelUsed = r.modelUsed.ifBlank { activeModelLabel },
                                            subjectVariant = "PRIMARY",
                                            promptTokens = r.promptTokens,
                                            completionTokens = r.completionTokens
                                        )
                                    }
                                } catch (e: Exception) {
                                    updateDraft(key, persist = false) {
                                        it.copy(
                                            generating = false,
                                            statusNote = "",
                                            error = e.message?.take(180) ?: "خطا در تولید"
                                        )
                                    }
                                } finally {
                                    val completed = done.incrementAndGet()
                                    withContext(Dispatchers.Main) {
                                        batchProgressText = "پیشرفت تولید: $completed از $total"
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                batchGenerating = false
                batchProgressText = ""
            }
        }
    }

    ScreenScaffold {
        // 2-Step Segmented Studio Header
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = step == 0,
                onClick = { step = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                modifier = Modifier.testTag("studio_step_1")
            ) {
                Text("۱. لیست سطری و sitest", maxLines = 1)
            }
            SegmentedButton(
                selected = step == 1,
                onClick = {
                    if (drafts.isEmpty() && selected.isNotEmpty()) prepareDraftsAndGoToStep2()
                    else step = 1
                },
                enabled = selected.isNotEmpty() || drafts.isNotEmpty(),
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                modifier = Modifier.testTag("studio_step_2")
            ) {
                Text("۲. You.com و تولید AI", maxLines = 1)
            }
        }

        if (step == 0) {
            Section(
                header = "ورود گروهی آدرس ایمیل‌ها (هر سطر یک ایمیل)",
                icon = Icons.Default.Email,
                badge = if (contacts.isNotEmpty()) "${contacts.size} ایمیل" else null,
                badgeColor = GreenOk
            ) {
                SubTitle("آدرس‌های ایمیل هدف را به صورت گروهی (هر ایمیل در یک سطر) اینجا وارد یا Paste کنید؛ همچنین فرمت CSV یا ورود از فایل پشتیبانی می‌شود.")
                OutlinedTextField(
                    value = rawInput,
                    onValueChange = { app.session.rawEmailsInput.value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("campaign_emails_input"),
                    placeholder = {
                        Text(
                            "ceo@inchand.com\ninfo@numberland.ir\nsalar@example.com",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    minLines = 6,
                    maxLines = 15,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            filePicker.launch(
                                arrayOf(
                                    "text/*",
                                    "text/csv",
                                    "application/csv",
                                    "application/vnd.ms-excel",
                                    "text/comma-separated-values"
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("بارگذاری فایل TXT / CSV")
                    }
                    if (rawInput.isNotBlank()) {
                        TextButton(onClick = {
                            scope.launch { app.db.studioDraftDao().clearAll() }
                            app.session.reset()
                        }) {
                            Text("پاک کردن", color = RedErr)
                        }
                    }
                }
                if (invalidInputLines > 0) {
                    Chip("$invalidInputLines سطر فاقد آدرس ایمیل معتبر بود و نادیده گرفته شد", OrangeWarn)
                }
                if (blockedCount > 0) {
                    Chip("$blockedCount مخاطب به دلیل لیست عدم ارسال (opt-out) کنار گذاشته شد", OrangeWarn)
                }
            }

            if (sitestUri.isBlank()) {
                Section(
                    header = "اتصال به پوشه sitest",
                    icon = Icons.Default.FolderOpen,
                    badge = "الزامی (۱ بار)",
                    badgeColor = OrangeWarn
                ) {
                    SubTitle("برای تطبیق ایمیل‌ها با پوشه دامنه و انتخاب جفت Trial معتبر فارسی و انگلیسی (هرکدام دقیقاً ۲ صفحه)، پوشه sitest را یک‌بار انتخاب کنید.")
                    BigButton(
                        text = "انتخاب پوشه sitest",
                        icon = Icons.Default.FolderOpen,
                        modifier = Modifier.testTag("campaign_pick_sitest")
                    ) {
                        launchSitestPicker()
                    }
                }
            } else {
                Section(
                    header = "تطبیق پوشه و انتخاب جفت Trial دوزبانه",
                    icon = Icons.Default.FolderOpen,
                    badge = if (matches.isNotEmpty()) "${matches.count { it.readyForApproval }} از ${matches.size} آماده" else "متصل",
                    badgeColor = if (matches.any { it.readyForApproval }) GreenOk else MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { runAutoMatchScan() },
                            enabled = contacts.isNotEmpty() && !scanning,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("اسکن مجدد")
                        }
                        OutlinedButton(
                            onClick = { launchSitestPicker() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("تغییر پوشه sitest")
                        }
                    }

                    scanError?.let { Chip(it, RedErr) }
                    if (scanning) {
                        LoadingBox("در حال جستجوی پوشه‌ها و بررسی جفت Trial فارسی/انگلیسی...")
                    } else if (matches.isNotEmpty()) {
                        // Search, status filter & bulk selection controls
                        LabeledField(
                            label = "جستجو در ایمیل یا دامنه",
                            value = matchSearch,
                            singleLine = true,
                            placeholder = "مثلاً inchand"
                        ) { matchSearch = it }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "ALL" to "همه (${matches.size})",
                                "READY" to "آماده (${matches.count { it.readyForApproval }})",
                                "MISSING" to "ناقص (${matches.count { !it.readyForApproval }})"
                            ).forEach { (code, lbl) ->
                                FilterChip(
                                    selected = matchFilter == code,
                                    onClick = { matchFilter = code },
                                    label = { Text(lbl) }
                                )
                            }
                            TextButton(onClick = {
                                app.session.selected.value = matches.filter { it.readyForApproval }.map { it.key }.toSet()
                            }) { Text("انتخاب همه آماده") }
                            TextButton(onClick = {
                                app.session.selected.value = emptySet()
                            }) { Text("لغو انتخاب", color = RedErr) }
                        }

                        val filteredMatches = remember(matches, matchSearch, matchFilter) {
                            matches.filter { m ->
                                val qOk = matchSearch.isBlank() ||
                                    m.email.contains(matchSearch, true) ||
                                    m.domain.contains(matchSearch, true) ||
                                    (m.folderName?.contains(matchSearch, true) == true)
                                val fOk = when (matchFilter) {
                                    "READY" -> m.readyForApproval
                                    "MISSING" -> !m.readyForApproval
                                    else -> true
                                }
                                qOk && fOk
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            filteredMatches.forEach { m ->
                                MatchCard(
                                    m = m,
                                    checked = selected.contains(m.key),
                                    onOpenPdf = { uri -> openPdfPreview(uri) },
                                    onToggle = { chk ->
                                        val next = app.session.selected.value.toMutableSet()
                                        if (chk) next.add(m.key) else next.remove(m.key)
                                        app.session.selected.value = next
                                    }
                                )
                            }
                        }
                    } else if (contacts.isEmpty()) {
                        SubTitle("پس از ورود ایمیل‌ها در کادر بالا، برنامه به صورت خودکار پوشه‌های هم‌نام را در sitest پیدا می‌کند.")
                    }
                }
            }

            BigButton(
                text = "مرحله بعد: سرچ You.com و طراحی پیام (${selected.size} مخاطب)",
                enabled = selected.isNotEmpty() && !scanning,
                icon = Icons.Default.AutoAwesome,
                modifier = Modifier.testTag("campaign_next_step")
            ) {
                prepareDraftsAndGoToStep2()
            }
        } else {
            // STEP 2: You.com + hcnsec Strongest Model + 4 Reference Templates + Review/Approve
            val contextsMap by app.session.contexts.collectAsState()
            val currentSettings = app.prefs.load()
            val tplCount = currentSettings.referenceTemplates().size
            val approvedCount = drafts.count { it.approved }
            val readyToApproveCount = drafts.count { it.generated && !it.approved && it.quality.passed }
            val campaignTokens = drafts.sumOf { it.totalTokens }

            Section(
                header = "تنظیم سبک، ۴ الگوی مرجع و تولید هوشمند",
                icon = Icons.Default.AutoAwesome,
                badge = if (activeModelLabel.isNotBlank()) activeModelLabel else "انتخاب خودکار قوی‌ترین مدل",
                badgeColor = if (activeModelLabel.isNotBlank()) GreenOk else OrangeWarn
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Chip(
                        if (tplCount > 0) "$tplCount الگوی ساختاری فعال (کلیک برای ویرایش سریع)" else "بدون الگوی مرجع — کلیک برای افزودن ۴ الگو",
                        if (tplCount > 0) GreenOk else OrangeWarn,
                        onClick = { showTemplatesDialog = true }
                    )
                    Chip("پاس دو مرحله‌ای ویراستار فروش: ${if (currentSettings.enableCriticPass) "فعال ✓" else "خاموش"}", GreenOk)
                    if (campaignTokens > 0) {
                        Chip("مصرف کمپین: $campaignTokens توکن", MaterialTheme.colorScheme.tertiary)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "BUSINESS" to "فروش B2B",
                        "TECHNICAL" to "فنی / CTO",
                        "DIRECT" to "کوتاه و مستقیم"
                    ).forEach { (code, label) ->
                        FilterChip(
                            selected = defaultStyle == code,
                            onClick = {
                                defaultStyle = code
                                app.session.drafts.value = app.session.drafts.value.map {
                                    if (!it.generated) it.copy(style = code) else it
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }

                if (currentSettings.hcnsKey.isBlank() || currentSettings.youKey.isBlank()) {
                    Chip(
                        if (currentSettings.hcnsKey.isBlank()) "کلید hcnsec وارد نشده است (کلیک برای تنظیمات)"
                        else "کلید You.com وارد نشده (فقط از PDF استفاده می‌شود)",
                        if (currentSettings.hcnsKey.isBlank()) RedErr else OrangeWarn,
                        onClick = onOpenSettings
                    )
                }

                BigButton(
                    text = if (batchGenerating) batchProgressText.ifBlank { "در حال تولید..." }
                    else "سرچ You.com + طراحی همه پیام‌ها (${drafts.size})",
                    loading = batchGenerating,
                    enabled = drafts.isNotEmpty(),
                    icon = Icons.Default.AutoAwesome,
                    modifier = Modifier.testTag("campaign_generate_all")
                ) {
                    generateBatch(drafts.map { it.key })
                }

                if (batchGenerating) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                genError?.let { Chip(it, RedErr) }
                testNotice?.let { Chip(it, GreenOk) }
            }

            if (drafts.size > 1) {
                Section(header = "جستجو و فیلتر پیش‌نویس‌ها") {
                    LabeledField("جستجو بر اساس ایمیل، دامنه یا موضوع", draftSearch, singleLine = true) { draftSearch = it }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "ALL" to "همه (${drafts.size})",
                            "READY" to "آماده تأیید ($readyToApproveCount)",
                            "APPROVED" to "تأییدشده ($approvedCount)",
                            "FAILED" to "نیاز به اصلاح (${drafts.count { it.generated && !it.quality.passed }})"
                        ).forEach { (code, lbl) ->
                            FilterChip(
                                selected = draftFilter == code,
                                onClick = { draftFilter = code },
                                label = { Text(lbl) }
                            )
                        }
                    }
                }
            }

            val visibleDrafts = remember(drafts, draftSearch, draftFilter) {
                drafts.filter { d ->
                    val qOk = draftSearch.isBlank() ||
                        d.email.contains(draftSearch, true) ||
                        d.domain.contains(draftSearch, true) ||
                        d.subject.contains(draftSearch, true)
                    val fOk = when (draftFilter) {
                        "READY" -> d.generated && !d.approved && d.quality.passed
                        "APPROVED" -> d.approved
                        "FAILED" -> d.generated && !d.quality.passed
                        else -> true
                    }
                    qOk && fOk
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                visibleDrafts.forEach { item ->
                    DraftReviewCard(
                        item = item,
                        approving = approvingKeys.contains(item.key),
                        companySummary = contextsMap[item.domain]?.summary.orEmpty(),
                        onStyle = { st -> updateDraft(item.key) { it.copy(style = st) } },
                        onSubject = { v ->
                            updateDraft(item.key) { d ->
                                val altIdx = d.subjectAlternatives.indexOf(v)
                                val variant = if (altIdx >= 0) "ALT_${altIdx + 1}" else "CUSTOM"
                                d.copy(subject = v, subjectVariant = variant)
                            }
                        },
                        onSelectAlternative = { idx, alt ->
                            updateDraft(item.key) { it.copy(subject = alt, subjectVariant = "ALT_${idx + 1}") }
                        },
                        onPreheader = { v -> updateDraft(item.key) { it.copy(preheader = v) } },
                        onBody = { v -> updateDraft(item.key) { it.copy(body = v) } },
                        onOpenPdf = { uri -> openPdfPreview(uri) },
                        onPreviewHtml = { previewHtmlItem = item },
                        onSendTestToSelf = {
                            scope.launch {
                                genError = null
                                testNotice = null
                                val res = app.engine.sendTestToSelf(
                                    subject = item.subject,
                                    preheader = item.preheader,
                                    body = item.body,
                                    attachmentUris = item.match.attachmentUris,
                                    attachmentNames = item.match.attachmentNames
                                )
                                res.onSuccess { testNotice = it }
                                    .onFailure { genError = "خطای ارسال تست: ${it.message?.take(140)}" }
                            }
                        },
                        onRegenerate = { generateBatch(listOf(item.key)) },
                        onApprove = {
                            scope.launch {
                                val err = approveSingleDraft(item)
                                if (err != null) genError = err
                            }
                        }
                    )
                }
            }

            if (readyToApproveCount > 0) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            genError = null
                            val errs = mutableListOf<String>()
                            for (d in drafts.filter { it.generated && !it.approved && it.quality.passed }) {
                                approveSingleDraft(d)?.let { errs.add(it) }
                            }
                            if (errs.isNotEmpty()) genError = errs.first()
                        }
                    },
                    enabled = approvingKeys.isEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag("campaign_approve_all")
                ) {
                    Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("تأیید یکجای پیام‌های استاندارد ($readyToApproveCount)")
                }
            }

            BigButton(
                text = if (approvedCount > 0) "صف ارسال آماده و بررسی ضداسپم ($approvedCount پیام) ←"
                else "ابتدا پیام‌ها را تولید و تأیید کنید",
                enabled = approvedCount > 0,
                icon = Icons.AutoMirrored.Filled.Send,
                modifier = Modifier.testTag("campaign_go_send")
            ) {
                onGoToSend()
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    previewPdfUri?.let { uriStr ->
        PdfPreviewDialog(
            uriString = uriStr,
            onOpenExternal = { openExternalPdf(uriStr) },
            onDismiss = { previewPdfUri = null }
        )
    }

    previewHtmlItem?.let { item ->
        HtmlPreviewDialog(
            item = item,
            settings = app.prefs.load(),
            onDismiss = { previewHtmlItem = null }
        )
    }

    if (showTemplatesDialog) {
        QuickTemplatesDialog(
            app = app,
            onDismiss = { showTemplatesDialog = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatchCard(
    m: DomainMatch,
    checked: Boolean,
    onOpenPdf: (String) -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            if (m.readyForApproval) GreenOk.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(
                checked = checked,
                enabled = m.readyForApproval,
                onCheckedChange = onToggle
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.email, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        SubTitle("دامنه: ${m.domain}" + (m.folderName?.let { " • پوشه: $it" } ?: " • پوشه یافت نشد"))
                    }
                    if (m.readyForApproval) {
                        Chip("${m.attachmentNames.size} PDF آماده", GreenOk)
                    } else {
                        Chip("ناقص", RedErr)
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (ContactsParser.isRoleEmail(m.email)) {
                        Chip("ایمیل عمومی (role)", OrangeWarn)
                    }
                    m.report?.score?.let { Chip("امتیاز $it/100", if (it < 50) RedErr else OrangeWarn) }
                    m.report?.findings?.let { Chip("$it یافته", MaterialTheme.colorScheme.primary) }
                    m.bundle.trialAttachments.forEach { pdf ->
                        val sizeMb = String.format(java.util.Locale.US, "%.2f MB", pdf.sizeBytes / (1024.0 * 1024.0))
                        Chip("${pdf.name} (${pdf.pages ?: "?"} ص • $sizeMb)", GreenOk, onClick = { onOpenPdf(pdf.uri) })
                    }
                }

                m.warnings.take(2).forEach { w ->
                    SubTitle("• $w", color = OrangeWarn)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftReviewCard(
    item: CampaignDraftItem,
    approving: Boolean,
    companySummary: String,
    onStyle: (String) -> Unit,
    onSubject: (String) -> Unit,
    onSelectAlternative: (Int, String) -> Unit,
    onPreheader: (String) -> Unit,
    onBody: (String) -> Unit,
    onOpenPdf: (String) -> Unit,
    onPreviewHtml: () -> Unit,
    onSendTestToSelf: () -> Unit,
    onRegenerate: () -> Unit,
    onApprove: () -> Unit
) {
    var expanded by remember(item.key, item.generated) { mutableStateOf(!item.approved) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            when {
                item.approved -> GreenOk.copy(alpha = 0.55f)
                item.error != null -> RedErr.copy(alpha = 0.55f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            }
        ),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.email, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    SubTitle(
                        item.domain +
                            (item.modelUsed.takeIf { it.isNotBlank() }?.let { " • $it" } ?: "") +
                            (if (item.totalTokens > 0) " • ${item.totalTokens} توکن" else "") +
                            (item.subject.takeIf { it.isNotBlank() }?.let { " • $it" } ?: "")
                    )
                }
                when {
                    item.generating -> Chip(item.statusNote.ifBlank { "در حال تولید..." }, MaterialTheme.colorScheme.primary)
                    item.approved -> Chip("تأیید شد ✓", GreenOk)
                    item.generated && item.quality.passed -> Chip("${item.quality.score}/100 ✓", GreenOk)
                    item.generated -> Chip("${item.quality.score}/100", OrangeWarn)
                    else -> Chip("در انتظار تولید", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            if (item.generating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            item.error?.let { Chip(it, RedErr) }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Verified bilingual two-page trial pair preview (tappable to open in-app PDF viewer)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item.match.bundle.trialAttachments.forEach { pdf ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                                modifier = Modifier.clickable { onOpenPdf(pdf.uri) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Text(pdf.name, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (item.generated && item.totalTokens > 0) {
                            Chip("توکن: ${item.promptTokens}+${item.completionTokens}", MaterialTheme.colorScheme.tertiary)
                        }
                        if (item.generated) {
                            Chip("موضوع: ${item.subjectVariant}", MaterialTheme.colorScheme.primary)
                        }
                        if (ContactsParser.isRoleEmail(item.email)) {
                            Chip("ایمیل عمومی (role)", OrangeWarn)
                        }
                    }

                    if (companySummary.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "تحلیل You.com: " + companySummary.take(260),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("BUSINESS" to "فروش", "TECHNICAL" to "فنی", "DIRECT" to "مستقیم").forEach { (c, l) ->
                            FilterChip(
                                selected = item.style == c,
                                onClick = { onStyle(c) },
                                label = { Text(l) }
                            )
                        }
                    }

                    LabeledField("موضوع ایمیل (Subject)", item.subject, onChange = onSubject)

                    if (item.subjectAlternatives.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            item.subjectAlternatives.take(3).forEachIndexed { idx, alt ->
                                FilterChip(
                                    selected = item.subject == alt,
                                    onClick = { onSelectAlternative(idx, alt) },
                                    label = { Text(alt.take(34), fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    LabeledField("پیش‌نمایش ایمیل (Preheader)", item.preheader, onChange = onPreheader)
                    LabeledField("متن ایمیل بازاریابی", item.body, multiline = true, onChange = onBody)

                    if (item.generated) {
                        item.quality.blockers.forEach { SubTitle("⛔ $it", color = RedErr) }
                        item.quality.issues.take(2).forEach { SubTitle("• $it", color = OrangeWarn) }
                    }

                    if (item.generated && item.subject.isNotBlank() && item.body.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onPreviewHtml,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("پیش‌نمایش HTML", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = onSendTestToSelf,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ForwardToInbox, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("تست به خودم", fontSize = 12.sp)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRegenerate,
                            enabled = !item.generating && !approving,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (item.generated) "تولید مجدد" else "تولید تکی")
                        }
                        Button(
                            onClick = onApprove,
                            enabled = item.generated && item.quality.passed &&
                                item.subject.isNotBlank() && item.body.isNotBlank() &&
                                item.match.attachmentUris.isNotEmpty() && !item.approved && !approving,
                            modifier = Modifier.weight(1.3f)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    item.approved -> "تأییدشده"
                                    approving -> "در حال ثبت..."
                                    else -> "تأیید و ارسال به صف"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPreviewDialog(
    uriString: String,
    onOpenExternal: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pageIndex by remember(uriString) { mutableIntStateOf(0) }
    var pageCount by remember(uriString) { mutableIntStateOf(0) }
    var bitmap by remember(uriString) { mutableStateOf<Bitmap?>(null) }
    var renderingPage by remember(uriString) { mutableStateOf(true) }
    var errorText by remember(uriString) { mutableStateOf<String?>(null) }

    val rendererHolder = remember(uriString) { arrayOfNulls<PdfRenderer>(1) }
    val pfdHolder = remember(uriString) { arrayOfNulls<ParcelFileDescriptor>(1) }

    DisposableEffect(uriString) {
        onDispose {
            try { rendererHolder[0]?.close() } catch (_: Exception) { }
            try { pfdHolder[0]?.close() } catch (_: Exception) { }
            rendererHolder[0] = null
            pfdHolder[0] = null
        }
    }

    LaunchedEffect(uriString, pageIndex) {
        renderingPage = true
        errorText = null
        val rendered = withContext(Dispatchers.IO) {
            try {
                if (rendererHolder[0] == null) {
                    val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
                        ?: throw IllegalStateException("فایل PDF قابل باز شدن نیست")
                    pfdHolder[0] = pfd
                    rendererHolder[0] = PdfRenderer(pfd)
                }
                val r = rendererHolder[0]!!
                val count = r.pageCount
                val idx = pageIndex.coerceIn(0, maxOf(0, count - 1))
                val bmp = if (count > 0) {
                    r.openPage(idx).use { page ->
                        val targetScale = minOf(2f, 1600f / page.width.coerceAtLeast(1))
                        val w = (page.width * targetScale).toInt().coerceAtLeast(100)
                        val h = (page.height * targetScale).toInt().coerceAtLeast(100)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(android.graphics.Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                } else null
                Triple(count, bmp, null as String?)
            } catch (e: Exception) {
                Triple(pageCount, bitmap, e.message?.take(140) ?: "خطا در نمایش PDF")
            }
        }
        pageCount = rendered.first
        bitmap = rendered.second
        errorText = rendered.third
        renderingPage = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("پیش‌نمایش PDF", style = MaterialTheme.typography.titleMedium)
                if (pageCount > 0) {
                    Text(
                        "صفحه ${pageIndex + 1} از $pageCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                errorText?.let { Chip(it, RedErr) }
                if (renderingPage && bitmap == null) {
                    LoadingBox("در حال رندر صفحه PDF...")
                } else {
                    if (renderingPage) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    bitmap?.let { bmp ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "صفحه PDF",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White)
                            )
                        }
                    }
                }

                if (pageCount > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (pageIndex > 0) pageIndex-- },
                            enabled = pageIndex > 0 && !renderingPage
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "صفحه قبل")
                        }
                        Text("${pageIndex + 1} / $pageCount", style = MaterialTheme.typography.bodyMedium)
                        IconButton(
                            onClick = { if (pageIndex < pageCount - 1) pageIndex++ },
                            enabled = pageIndex < pageCount - 1 && !renderingPage
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "صفحه بعد")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("بستن") }
        },
        dismissButton = {
            TextButton(onClick = onOpenExternal) { Text("برنامه خارجی") }
        }
    )
}

@Composable
private fun HtmlPreviewDialog(
    item: CampaignDraftItem,
    settings: ir.salisec.autosender.data.Settings,
    onDismiss: () -> Unit
) {
    val sigTrimmed = settings.signature.trim()
    val unsubTrimmed = settings.unsubscribeFooter.trim()
    val cleanBody = remember(item.body, sigTrimmed, unsubTrimmed) {
        var b = item.body.trim()
        if (sigTrimmed.isNotBlank() && b.endsWith(sigTrimmed)) {
            b = b.removeSuffix(sigTrimmed).trim()
        }
        if (unsubTrimmed.isNotBlank() && b.endsWith(unsubTrimmed)) {
            b = b.removeSuffix(unsubTrimmed).trim()
        }
        b
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("پیش‌نمایش قالب خروجی ایمیل") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("موضوع: ${item.subject}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                if (item.preheader.isNotBlank()) {
                    SubTitle("Preheader: ${item.preheader}")
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(cleanBody, style = MaterialTheme.typography.bodyMedium)
                        if (sigTrimmed.isNotBlank() || unsubTrimmed.isNotBlank()) {
                            HorizontalDivider()
                        }
                        if (sigTrimmed.isNotBlank()) {
                            Text(sigTrimmed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (unsubTrimmed.isNotBlank()) {
                            Text(unsubTrimmed, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (item.match.attachmentNames.isNotEmpty()) {
                    Chip("پیوست‌ها: ${item.match.attachmentNames.joinToString(" + ")}", GreenOk)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("تأیید و بستن") }
        }
    )
}

@Composable
private fun QuickTemplatesDialog(
    app: App,
    onDismiss: () -> Unit
) {
    var s by remember { mutableStateOf(app.prefs.load()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.EditNote, contentDescription = null)
                Text("ویرایش سریع ۴ الگوی مرجع hcnsec")
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SubTitle("این ۴ متن الگوی ساختار و لحن hcnsec هستند و در همین کمپین اعمال می‌شوند.")
                LabeledField("الگوی ۱", s.template1, multiline = true) { s = s.copy(template1 = it) }
                LabeledField("الگوی ۲", s.template2, multiline = true) { s = s.copy(template2 = it) }
                LabeledField("الگوی ۳", s.template3, multiline = true) { s = s.copy(template3 = it) }
                LabeledField("الگوی ۴", s.template4, multiline = true) { s = s.copy(template4 = it) }
                OutlinedButton(
                    onClick = {
                        s = s.copy(
                            template1 = s.template1.ifBlank { Prefs.SAMPLE_TEMPLATES[0] },
                            template2 = s.template2.ifBlank { Prefs.SAMPLE_TEMPLATES[1] },
                            template3 = s.template3.ifBlank { Prefs.SAMPLE_TEMPLATES[2] },
                            template4 = s.template4.ifBlank { Prefs.SAMPLE_TEMPLATES[3] }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("درج نمونه‌های پیشنهادی در موارد خالی") }
            }
        },
        confirmButton = {
            Button(onClick = {
                app.prefs.save(s)
                onDismiss()
            }) { Text("ذخیره الگوها") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("بستن") }
        }
    )
}


/**
 * Stable scan cache key over all contact inputs and the live SAF folder tree.
 * File name, size and modified time are included so replacing/updating a PDF or
 * sidecar in the same folder URI invalidates cached report matches.
 */
private fun folderScanFingerprint(
    root: DocumentFile,
    contactsSignature: String,
    sitestUri: String
): String {
    val canonical = buildString {
        append("sitest=").append(sitestUri).append('\n')
        append("contacts=").append(contactsSignature).append('\n')
        val folders = runCatching { root.listFiles().filter { it.isDirectory } }
            .getOrElse { return "" }
            .sortedBy { it.uri.toString() }
        folders.forEach { folder ->
            append("folder|").append(folder.name.orEmpty()).append('|')
                .append(folder.uri).append('|').append(folder.lastModified()).append('\n')
            val relevantFiles = runCatching {
                folder.listFiles().filter { file ->
                    file.isFile && (
                        file.name?.endsWith(".pdf", ignoreCase = true) == true ||
                            file.name?.endsWith(".json", ignoreCase = true) == true
                        )
                }
            }.getOrElse { return "" }.sortedBy { it.uri.toString() }
            relevantFiles.forEach { file ->
                append("file|").append(file.name.orEmpty()).append('|')
                    .append(file.uri).append('|').append(file.length()).append('|')
                    .append(file.lastModified()).append('\n')
            }
        }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun contactsSignatureForScan(contacts: List<ir.salisec.autosender.data.Contact>): String =
    contacts.joinToString("|") { it.email }

package ir.salisec.autosender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.salisec.autosender.App
import ir.salisec.autosender.data.OutgoingMessage
import ir.salisec.autosender.mail.BrevoSender
import ir.salisec.autosender.mail.InboxMessage
import ir.salisec.autosender.mail.SmtpSender
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val emailRegex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}""")

/** Thunderbird-like basics: list inbox, read a message, reply, honour opt-outs. */
@Composable
fun InboxScreen(app: App, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf(listOf<InboxMessage>()) }
    var open by remember { mutableStateOf<InboxMessage?>(null) }
    var openText by remember { mutableStateOf("") }
    var replyText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val s = remember { app.prefs.load() }
    val fmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US) }

    val optedOut = remember(openText) { detectOptOut(openText) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                messages = app.inbox.list(s)
            } catch (e: Exception) {
                error = "اتصال به صندوق ناموفق: " + (e.message?.take(160) ?: "")
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (s.imapHost.isNotBlank() && s.imapUser.isNotBlank() && s.imapPass.isNotBlank()) load()
    }

    ScreenScaffold {
        Title("صندوق ورودی")
        notice?.let { Chip(it, GreenOk) }
        if (s.imapHost.isBlank() || s.imapUser.isBlank() || s.imapPass.isBlank()) {
            SubTitle("برای دیدن پاسخها، تنظیمات IMAP را در بخش تنظیمات کامل کن.")
        } else if (open == null) {
            OutlinedButton(onClick = { load() }) { Text("دریافت / بروزرسانی") }
            if (loading) LoadingBox("در حال دریافت ایمیلها...")
            error?.let { Chip(it, RedErr) }
            Text(if (messages.isEmpty()) "پیامی نیست" else messages.size.toString() + " پیام")
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 470.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { m ->
                    Surface(Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            error = null
                            try {
                                val (_, body) = app.inbox.read(s, m.position)
                                openText = body
                                open = m
                            } catch (e: Exception) {
                                error = "خواندن پیام ناموفق: " + (e.message?.take(120) ?: "")
                            }
                        }
                    }) {
                        Column(Modifier.padding(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(m.from, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(fmt.format(Date(m.date)), fontSize = 11.sp)
                            }
                            Text(m.subject.ifBlank { "(بدون موضوع)" }, fontSize = 14.sp)
                            Text(
                                m.preview, fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            }
        } else {
            val m = open!!
            Title(m.subject.ifBlank { "(بدون موضوع)" })
            SubTitle("از: " + m.from)
            Text(openText, fontSize = 13.sp)
            if (optedOut) {
                Section("درخواست خروج از فهرست") {
                    Chip("این پاسخ حاوی درخواست حذف / unsubscribe است", RedErr)
                    SubTitle("طبق خط قرمز پروژه، بلافاصله این مخاطب را از لیست خارج کن و دیگر به او ایمیل نفرست.")
                    Button(onClick = {
                        val address = emailRegex.find(m.from)?.value
                        if (address != null) {
                            app.prefs.addSuppressed(address)
                            app.prefs.addSuppressed(address.substringAfter('@').lowercase())
                            scope.launch {
                                app.db.messageDao().all()
                                    .filter { it.email.equals(address, ignoreCase = true) && it.pipelineState != "PAID" }
                                    .forEach { app.db.messageDao().update(it.copy(pipelineState = "LOST", status = "SKIPPED")) }
                            }
                            notice = "${address.substringAfter('@')} به فهرست عدم ارسال اضافه شد"
                            open = null
                        } else {
                            error = "آدرس فرستنده پیدا نشد"
                        }
                    }) { Text("ثبت در فهرست عدم ارسال") }
                }
            }
            Section("پاسخ") {
                LabeledField("متن پاسخ", replyText, multiline = true, onChange = { replyText = it })
                Button(enabled = replyText.isNotBlank() && !sending && !optedOut, onClick = {
                    scope.launch {
                        sending = true
                        error = null
                        try {
                            val to = emailRegex.find(m.from)?.value
                                ?: throw IllegalStateException("آدرس فرستنده در پیام پیدا نشد")
                            val out = OutgoingMessage(
                                to = to,
                                subject = "Re: " + m.subject,
                                htmlBody = replyText,
                                textBody = replyText
                            )
                            val sender = if (s.sendMode == "BREVO_API") BrevoSender(app.ok) else SmtpSender()
                            sender.send(s, out)
                            replyText = ""
                            open = null
                        } catch (e: Exception) {
                            error = "ارسال پاسخ ناموفق: " + (e.message?.take(160) ?: "")
                        }
                        sending = false
                    }
                }) { Text(if (sending) "در حال ارسال..." else "ارسال پاسخ") }
                if (optedOut) SubTitle("ارسال پاسخ تا ثبت درخواست حذف متوقف شده است.")
            }
            TextButton(onClick = { open = null }) { Text("بازگشت به صندوق") }
        }
        error?.let { Chip(it, RedErr) }
        TextButton(onClick = onBack) { Text("بازگشت") }
    }
}

private fun detectOptOut(text: String): Boolean {
    val t = text.lowercase()
    val markers = listOf(
        "unsubscribe", "opt out", "opt-out", "remove me", "stop sending",
        "لغو اشتراک", "دیگر ارسال نکن", "دیگر ایمیل نفرست", "تمایلی به دریافت",
        "از لیست حذف", "از فهرست حذف", "حذف کنید", "عدم تمایل", "دیگر نمیخواهم"
    )
    if (markers.any { t.contains(it) }) return true
    return t.contains("حذف") && (t.contains("لیست") || t.contains("فهرست") || t.contains("ایمیل"))
}

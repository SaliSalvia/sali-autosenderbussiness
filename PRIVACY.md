#Privacy Policy / خطمشی حریم خصوصی — SaliBussiness

**آخرین بهروزرسانی / Last updated:** 2026-11-03 · **نسخه اپ / App version:** 2.0.0 (versionCode 3)

---

## فارسی

### ۱. خلاصه
SaliBussiness یک ابزار **روی دستگاه خود شما** است. **هیچ سرور متعلق به ما وجود ندارد** و هیچ داده ایمیل، مخاطب یا گزارشی به بکند ما ارسال نمیشود. تنها ترافیک خروجی، همان سرویسهایی است که خودتان در «تنظیمات» با کلید شخصی فعال میکنید.

### ۲. دادههایی که پردازش میشوند
| داده | منبع | محل نگهداری | هدف |
|---|---|---|---|
| دامنهها، ایمیل و نام مخاطبان | **فقط لیست خودتان** (چسباندن متن / CSV) | Room `sali.db` روی دستگاه + پیشنویس کمپین | ساخت ایمیل شخصیسازیشده |
| موضوع، متن، پیشنمایش و وضعیت هر ایمیل | ساخت AI + ویرایش و تأیید شما | Room `sali.db` | صف ارسال و بازبینی انسانی |
| متن و تعداد صفحات PDFهای پوشه دامنه | پوشهای که با SAF انتخاب میکنید | حافظه موقتی + مسیرهای SAF | زمینه نگارش پیام؛ تعیین دو نسخه trial |
| گزارش فعالیت ارسال | موتور ارسال | Room `sali.db` | عیبیابی؛ **ایمیل مخاطب ماسک میشود** |
| کلیدهای API و رمزهای IMAP/SMTP | ورودی شما در تنظیمات | `EncryptedSharedPreferences` (AES، Android Keystore) | اتصال به سرویسها |
| زمینه عمومی شرکت (صنعت/محصولات) | You.com Search API | کش حافظهای و پیشنویس کمپین | شخصیسازی B2B؛ **هرگز داده شخصی** |

### ۳. طرفهای ثالث (فقط در صورتیکه خودتان کلید وارد کنید)
- **Brevo** (`api.brevo.com`) — تحویل ایمیل تراکنشی؛ گیرنده، موضوع، متن و دو پیوست trial را دریافت میکند. یک UUID پایدار بهعنوان `headers.idempotencyKey` ارسال میشود تا ارسال تکراری رخ ندهد.
- **hcnsec** (`api.hcnsec.cn`) — تولید/ویراستاری متن؛ **فقط شواهد همان گزارش** (نه لیست مخاطبان) را دریافت میکند.
- **You.com** (`ydc-index.io`) — فقط جستوجوی زمینه عمومی سطح شرکت برای هر دامنه.
- **سرور IMAP/SMTP خودتان** — خواندن پاسخها / ارسال از طریق SMTP اختصاصی.

هیچ تحلیلگر، تبلیغات، SDK ردیابی، تلمتری یا crash reporter شخص ثالث در اپ وجود ندارد. لاگ ساختاریافتهای خارج از دستگاه ارسال نمیشود.

### ۴. آنچه انجام نمیدهیم
- ❌ هیچ scraping، harvest، حدس آدرس یا غنیسازی داده شخصی انجام نمیشود.
- ❌ هیچ ایمیلی بدون **تأیید صریح انسانی** ارسال نمیشود؛ ویرایش پس از تأیید، approval را باطل میکند.
- ❌ عدد، ادعا، مهلت یا فوریت ساختگی تولید نمیشود (`QualityGate`).
- ❌ دور زدن فیلتر اسپم، هدر جعلی، `Re:/Fwd:` جعلی یا چرخش قالب ممنوع است.
- ❌ API کیلیدها، ایمیلها و متن پیامها وارد لاگ، گزارش خطا، artifact بیلد یا خروجی لاگ نمیشوند (`PrivacyRedactor`).
- ❌ پشتیبانگیری از دادههای اپ: `android:allowBackup="false"` و `usesCleartextTraffic="false"` + Network Security Config.

### ۵. نگهداری و حذف
دادهها تا زمانیکه اپ نصب است باقی میمانند؛ هیچ نگهداری ابری از سوی ما وجود ندارد. حذف کامل:
1. «تنظیمات → پاکسازی» / پاک کردن داده اپ از تنظیمات اندروید (Room و کلیدهای رمزنگاریشده حذف میشوند)؛
2. پاک کردن دستی فایلهای خروجی لاگ که خودتان export کردهاید؛
3. برای حذف سوابق طرف ثالث، مستقیماً با Brevo / ارائهدهنده ایمیل خود تماس بگیرید.

درخواست **opt-out** یک مخاطب بلافاصله در فهرست عدم ارسال ثبت میشود و پیش از هر ارسال دوباره بررسی میگردد؛ پیامهای در صف او به `SKIPPED` تغییر میکنند.

### ۶. مسئولیت کاربر
شما بهعنوان فرستنده مسئولید فقط به سرنخهایی ایمیل بزنید که **رابطه تجاری یا رضایت معتبر** دارند، قوانین محلی (GDPR / CAN-SPAM / قوانین ایران) و شرایط سرویس ارائهدهنده را رعایت کنید، و دامنه فرستنده را با SPF / DKIM / DMARC تأیید کرده باشید.

### ۷. تماس
مسائل امنیتی: [`SECURITY.md`](SECURITY.md) (گزارش خصوصی). سایر موارد: Issue خصوصی یا پیام به مالک مخزن `SaliSalvia`.

---

## English

### 1. Summary
SaliBussiness is an **on-device** tool. There is **no backend operated by us**; no contact, message or report data is uploaded to a server of ours. The only outbound traffic goes to services you explicitly enable with your own API keys in Settings.

### 2. Data we process
| Data | Source | Storage | Purpose |
|---|---|---|---|
| Domains, recipient emails, names | **Your own list only** (paste / CSV) | Room `sali.db` on device + persisted campaign draft | Personalised email |
| Subject, body, preheader, delivery status | AI draft + your edits/approval | Room `sali.db` | Queue & human review |
| PDF text and page counts | Folder you pick via SAF | In-memory + SAF URIs | Grounding the copy; selecting the two trial PDFs |
| Delivery activity log | Send engine | Room `sali.db` | Troubleshooting; **recipient addresses are masked** |
| API keys / IMAP-SMTP passwords | Your Settings input | `EncryptedSharedPreferences` (AES, Android Keystore) | Service connectivity |
| Public company context | You.com Search API | In-memory cache + campaign draft | B2B personalisation; **never personal data** |

### 3. Third parties (only when you supply a key)
- **Brevo** — email delivery; receives recipient, subject, body and the two trial attachments. A stable UUID is sent as `headers.idempotencyKey` to prevent duplicate sends.
- **hcnsec** — copy generation; receives **only that report's evidence**, never the contact list.
- **You.com** — public, company-level web context only.
- **Your own IMAP / SMTP server** — reading replies / optional SMTP delivery.

No analytics, advertising, tracking SDK, telemetry or third-party crash reporter exists in the app. No structured log leaves the device.

### 4. What we never do
- ❌ No scraping, harvesting, address guessing or personal-data enrichment.
- ❌ No email is sent without **explicit human approval**; editing after approval revokes it.
- ❌ No invented numbers, claims, deadlines or fake urgency (`QualityGate`).
- ❌ No spam-filter evasion, fake headers, fake `Re:/Fwd:` or template rotation.
- ❌ No API keys, recipient addresses or message bodies in logs, crash reports, build artifacts or log exports (`PrivacyRedactor`).
- ❌ No Android backup of app data (`allowBackup=false`), no cleartext traffic.

### 5. Retention and deletion
Data lives while the app is installed; we have no cloud retention. To erase everything: clear app data / uninstall (removes Room DB and encrypted keys), delete any log file you exported, and contact Brevo or your mail provider for their own records. An opt-out is recorded immediately, re-checked before every send, and queues for that recipient are marked `SKIPPED`.

### 6. Your responsibility
As the sender you must only contact leads with a valid business relationship or consent, comply with applicable law and the provider's Terms of Service, and authenticate your sending domain with SPF / DKIM / DMARC.

### 7. Contact
Security issues: [`SECURITY.md`](SECURITY.md) (private reporting). Otherwise open an issue or message the repository owner `SaliSalvia`.

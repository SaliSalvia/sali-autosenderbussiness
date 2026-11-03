# 🗺️ نقشه راه SaliBussiness: از نمونه کارآمد تا محصول کلاس جهانی (v2.0 → v3.0)

**تاریخ:** ۲۰۲۶-۱۱-۰۳ · **وضعیت:** سند برنامهریزی قابل اجرا · **مبنای ارزیابی:** `main` در `38ff5f8` (نسخه v2.0، بیلد سبز، APK نصبپذیر)
**پیشنیاز مطالعه:** [`docs/MASTER_BLUEPRINT.md`](MASTER_BLUEPRINT.md) (مرجع معماری و خطوط قرمز) · [`docs/RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md) (چکلیست Go/No-Go)

> **یک جمله:** «کلاس جهانی» در این محصول یعنی **ابزاری که بتوان بدون نگرانی به یک تیم فروش سپرد**: دادهاش گم نمیشود، ایمیلی دوباره ارسال نمیشود، عددی جعل نمیشود، مخاطبی که «حذف» گفته هرگز پیام نمیگیرد، و انتشارش قابلیت تکرار و برگشت دارد. نه یعنی «ارسال بیشتر» یا «هوشمندتر شدن فیلترشکنی».

---

## ۰. تعریف «آماده / درجه یک» (Definition of Done)

محصول زمانی «آماده» است که **همه**ی اینها همزمان برقرار باشد:

| بُعد | تعریفDone |
|---|---|
| **Correctness** | رفتار قابل پیشبینی در ۲۰۰ دامنه؛ ۰ عدد ساختگی؛ ۰ ایمیل بدون تأیید انسان؛ ۰ ارسال تکراری |
| **Reliability** | قطع برق/کشتن اپ/بستن صفحه وسط کمپین ۷۰تایی → ادامه دقیقاً از همانجا، بدون از دست رفتن داده |
| **Safety & Privacy** | کلیدها و PII هرگز وارد لاگ/پشتیبان/گزارش خطا نمیشوند؛ سیاست حفظ حریم خصوصی منتشر شده؛ حذف داده درخواستی ممکن است |
| **Compliance** | انطباق با ToS ارائهدهنده ارسال، opt-out در کمتر از یک دقیقه، هویت فرستنده واقعی، SPF/DKIM/DMARC تأییدشده |
| **Maintainability** | تست خودکار، لینت، پوشش، نسخهدهی معنایی، وابستگیهای پینشده، معماری قابل تغییر بدون ترس |
| **Observability** | هر ارسال/شکست/هزینه API قابل ردیابی؛ داشبورد سلامت؛ هشدار قبل از تمام شدن اعتبار |
| **UX** | راهاندازی اولین کمپین < ۱۰ دقیقه، بدون اصطلاح فنی، RTL بی نقص، دسترسیپذیر، حالتهای خطا/خالی/آفلاین |
| **Distributable** | AAB امضاشده با Play App Signing، انتشار مرحلهای، برگشتپذیر، SBOM و یادداشت انتشار |
| **Economics** | هزینه هر ایمیل و نرخ پاسخ به تفکیک سبک/subject اندازهگیری و قابل بهینهسازی است |

---

## ۱. ارزیابی صادقانه نقطه شروع (Baseline)

| بُعد | وضعیت v2.0 | شاهد در کد | درجه |
|---|---|---|---|
| ایده / تمایز | عالی: داده واقعی محلی + بازبینی انسانی + گیت صحت | `ReportBundle`, `QualityGate` | 🟢 A |
| عملکرد پایه | بیلد سبز، APK نصبپذیر، Release `latest` | Actions run ۴۱۵۵۲۹۷۹۷۷۴ | 🟢 A |
| خطوط قرمز | پیادهسازی شده و مستند | `MASTER_BLUEPRINT.md` §۳ | 🟢 A |
| تست | **هیچ تستی وجود ندارد** (`src/test` / `src/androidTest` خالی) | ریپو | 🔴 F |
| پایداری وضعیت | کمپین فقط در حافظه است → با kill شدن اپ گم میشود | `App.kt::CampaignSession` | 🔴 F |
| نسخهدهی | `versionCode=1`, `versionName="1.0.0"` در حالی که محصول v2.0 است | `app/build.gradle.kts` | 🔴 F |
| انتشار Play | `targetSdk=34` — **از ۳۱ اوت ۲۰۲۶ اپهای جدید/بهروزشده باید API 36 را هدف بگیرند و مهلت تمدید هم ۱ نوامبر تمام شده** [2](https://primetestlab.com/blog/google-play-target-api-level-2026) | `app/build.gradle.kts` | 🔴 F |
| امنیت بیلد | `isMinifyEnabled=false` (بدون R8)، توزیع APK debug-signed، بدون Play App Signing | `app/build.gradle.kts` | 🟠 D |
| حریم خصوصی | `allowBackup` تنظیم نشده → DB حاوی ایمیل/موضوع میتواند وارد پشتیبان شود؛ فاقد `network_security_config`؛ `security-crypto` نسخه alpha | `AndroidManifest.xml`, `build.gradle.kts` | 🟠 D |
| پیوست | سقف پیشفرض `maxAttachmentMb=8` **بیش از محدودیت مستند Brevo (۴MB برای هر فایل / ۲۰MB کل)** [1](https://help.brevo.com/hc/en-us/articles/4402811730962-Add-an-attachment-to-a-transactional-email) (صفحه محصول ۲۵MB میگوید [5](https://www.brevo.com/features/email-api/) → محافظهکارانه ۴MB ملاک شود). تعداد صفحات هنگام ارسال دوباره چک نمیشود | `Prefs.kt`, `SendEngine.kt` | 🟠 D |
| Idempotency | فراخوانی Brevo بدون کلید idempotency → retry بعد از timeout میتواند **دو بار ارسال کند**؛ Brevo از کلید UUID با TTL ۳۰ دقیقه پشتیبانی میکند [2](https://www.brevo.com/products/transactional-email/) | `MailSender.kt` | 🔴 F |
| Bounce / Complaint | فقط opt-out دستی از متن پاسخ؛ **هیچ اتصالی به webhookهای bounce/spam complaint نیست** (Brevo این رویدادها را realtime میدهد [5](https://www.brevo.com/features/email-api/)) | `InboxScreen.kt` | 🟠 D |
| مشاهدهپذیری | لاگ ساده داخل اپ + خروجی txt؛ بدون کد خطا، بدون هزینه/توکن، بدون crash reporting | `SendEngine.kt`, `Db.kt` | 🟠 D |
| بومیسازی | فقط `app_name` در `strings.xml`؛ بقیه متنها هاردکد داخل Composableها | `ui/*.kt` | 🟠 D |
| UX | تم تیره ثابت، بدون onboarding، بدون جستجو/فیلتر در ۲۰۰ دامنه، بدون undo تأیید، بدون حالت آفلاین | `Theme.kt`, `GenerateScreen.kt` | 🟡 C |
| هزینه / آزمایش | ۳ سبک هست ولی **هیچ ردگیری از اینکه کدام نسخه پاسخ گرفته** وجود ندارد؛ مصرف توکن/اعتبار اندازهگیری نمیشود | `GenerateScreen.kt` | 🟡 C |
| CI | فقط `assembleDebug/Release`؛ بدون `test`, `lint`, `detekt`, پوشش، اسکن وابستگی | `.github/workflows/build-apk.yml` | 🟠 D |

**نتیجه:** هسته محصول (truthfulness + human-in-the-loop) در سطح جهانی طراحی شده؛ **لایه عملیاتی/مهندسی اطراف آن در سطح پروتوتایپ است**. دقیقاً همان چیزی که نقشه راه زیر میسازد.

---

## ۲. شکافهای P0 — قبل از هر فیچر جدید

| # | شکاف | اثر واقعی | اقدام |
|---|---|---|---|
| P0-1 | `targetSdk=34` | اپ قابل ارسال به Play نیست [2](https://primetestlab.com/blog/google-play-target-api-level-2026) | `compileSdk/targetSdk=36` + AGP/Gradle/Kotlin سازگار + تست رفتارهای Android 16 (edge-to-edge، سرویس foreground، اعلان) |
| P0-2 | بدون تست | هر تغییر پرامپت/گیت/Policy میتواند بیصدا خراب شود | تست واحد برای ۷ منطق خالص + تست طلایی «۱۰۰ ایمیل بدون عدد جعلی» |
| P0-3 | کمپین در حافظه | کشتن اپ وسط ۷۰ ایمیل = از دست رفتن دامنهها، مخاطبان، متنهای ویرایششده | `CampaignRepository` روی Room + بازیابی وضعیت |
| P0-4 | retry بدون idempotency | **ارسال تکراری به مدیرعامل یک شرکت** = ضربه جبرانناپذیر به اعتبار | UUID پایدار بهازای هر `MessageEntity` + هدر Brevo [2](https://www.brevo.com/products/transactional-email/) |
| P0-5 | سقف پیوست ۸MB | رد شدن ناگهانی API، یا ارسال فایل اشتباه | پیشفرض ۴MB/فایل + ۲۰MB کل [1](https://help.brevo.com/hc/en-us/articles/4402811730962-Add-an-attachment-to-a-transactional-email)، بررسی magic bytes `%PDF` و تعداد صفحات واقعی با PDFBox در لحظه ارسال |
| P0-6 | PII در پشتیبان | ایمیل/موضوع سرنخها به بکاپ ابری/رایانه میرود | `allowBackup=false` + `dataExtractionRules` + خروج صریح `sali.db` و prefs |
| P0-7 | نسخهگذاری غلط | تشخیص نسخه نصبشده روی گوشی غیرممکن → پشتیبانی و برگشت نشدنی | `versionCode=3`, `versionName="2.0.0"` + `CHANGELOG` + تگ SemVer |
| P0-8 | بدون سیاست حریم خصوصی/امنیتی | مانع Play Data Safety و اعتماد مشتریان سازمانی | `SECURITY.md`, `PRIVACY.md`, موجودی داده، فرم حذف داده |

> ⚡ **همین حالا در این بهروزرسانی انجام شد (فاز ۰، قسمت مستنداتی):** `CHANGELOG.md`, `SECURITY.md`, `.github/dependabot.yml`, `docs/RELEASE_CHECKLIST.md` و لینکدهی `README.md`. بقیه موارد فاز ۰ نیاز به تغییر کد/CI دارند و در فازها فهرست شدهاند.

---

## ۳. نقشه فازها (۹ هفته / یک توسعهدهنده + بازبینی صاحب محصول)

### فاز ۰ — مبانی انتشار و تکرارپذیری (هفته ۱) · P0
**هدف:** هر کامیت، یک بیلد قابل اعتماد و قابل شناسایی بسازد.

- [ ] `compileSdk=36`, `targetSdk=36`, `versionCode=3`, `versionName="2.0.0"`؛ ارتقای AGP/Gradle/Kotlin با تست سازگاری؛ بررسی رفتارهای Android 16 [2](https://primetestlab.com/blog/google-play-target-api-level-2026)
- [ ] `libs.versions.toml` (Version Catalog) + پین کردن تمام وابستگیها؛ جایگزینی `androidx.security:security-crypto:1.1.0-alpha06` با نسخه پایدار یا `EncryptedFile`/Keystore مستقیم
- [ ] `kapt` → **KSP** (Kotlin 2.0 دیگر به زبان ۱٫۹ برنمیگردد)؛ `isMinifyEnabled=true` + `proguard-rules.pro` برای Room/OkHttp/javax.mail/PDFBox
- [ ] Commit کردن Gradle wrapper + `gradle-wrapper.properties` و checksum (بهجای تولید wrapper در CI) → بیلد reproducible
- [ ] `CHANGELOG.md`, `SECURITY.md`, `LICENSE` (تصمیم مالکیت)، `CONTRIBUTING.md`, `CODEOWNERS`, Branch Protection + required checks
- [ ] Secrets جدولدار: `SALI_KEYSTORE_*`, `SALI_KEYSTORE_BASE64`؛ خروجی نهایی **AAB امضاشده** نه APK debug-signed
- [ ] `.github/dependabot.yml` (انجام شد) + `gradle/actions/dependency-submission` + هشدار CVE

**معیار پذیرش:** `./gradlew clean test lint assembleRelease bundleRelease` در CI سبز؛ SHA256 خروجی در Release؛ `versionName` روی گوشی با تگ گیت یکی است.

---

### فاز ۱ — کیفیت مهندسی و تست (هفته ۲–۳) · P0
**هدف:** تغییر پرامپت یا قانون پیوست، دیگر یک قمار نباشد.

- [ ] استخراج منطق خالص به `:core` بدون وابستگی Android: `AttachmentPolicy`, `QualityGate`, `DomainValidator`, `ContactsParser`, `ReportReader.parsePdfFacts`, `AiClient.parseAiResult`, `Enricher.parse`, `SendEngine.attachmentProblem` → `SendPolicy`
- [ ] تست واحد (JUnit5 + `kotlinx-coroutines-test` + Turbine برای `StateFlow`) با **حداقل ۷۰٪ پوشش روی منطق دامنه**
- [ ] **Golden Set:** ۱۰۰ ایمیل تولیدشده از ۲۰ بسته شواهد نمونه → assertion «هیچ عددی خارج از شواهد نیست»، «CTA دقیقاً یکی»، «خط opt-out هست»، «subject ≤ ۵۰ نویسه»؛ این تست، خط قرمز را به کد تبدیل میکند
- [ ] تست قرارداد API با `MockWebServer` (پاسخهای hcnsec/You.com/Brevo، ۴۲۹، ۵xx، JSON داخل code fence، بدنه خالی)
- [ ] تست Compose (`createComposeRule`) برای `GenerateScreen`: غیرفعال بودن «تأیید» تا عبور گیت؛ نمایش ۲ پیوست؛ تغییر subject
- [ ] Paparazzi/screenshot + تست Espresso smoke برای جریان ۵ مرحلهای
- [ ] CI جدید `quality.yml`: `testDebugUnitTest` + `lintDebug` + `detekt`/`ktlint` + JaCoCo + آپلود گزارش؛ شکست = بلاک شدن PR
- [ ] `baseline-profiling` و `StrictMode` در debug

**معیار پذیرش:** هیچ PR بدون تست سبز merge نمیشود؛ Golden Set در CI اجرا میشود؛ ۰ عدد جعلی در ۱۰۰ نمونه.

---

### فاز ۲ — معماری و پایداری وضعیت (هفته ۳–۴) · P0
**هدف:** اپ مثل یک ابزار کاری رفتار کند، نه مثل یک دموی نمایشی.

- [ ] `CampaignRepository` (Room) بهجای `CampaignSession` درونحافظهای: دامنهها، مخاطبان، تطبیقها، متنهای ویرایششده، کش You.com، سبک انتخابی → **بازیابی بعد از process death**
- [ ] `ViewModel` + `UiState` sealed + `Effect` canal بهازای هر صفحه؛ حذف `mutableStateListOf` محلی بهعنوان منبع حقیقت
- [ ] مدل خطای typed: `DomainError.Network / RateLimited / InvalidKey / MissingTrial / QualityFailed / Suppressed`؛ پیام فارسی قابل اقدام به جای `e.message`
- [ ] رابطها برای قابل تعویض شدن: `AiProvider`, `SearchProvider`, `MailTransport`, `ReportSource`, `SuppressionStore` (هم DI ساده باقی میماند، هم fakes برای تست)
- [ ] modularization سبک (`:core:model`, `:core:data`, `:core:ai`, `:core:mail`, `:feature:*`) **فقط اگر تیم/حجم کد توجیه کرد**؛ Hilt/Koin همچنان «غیرضروری تا اطلاع ثانوی» (توضیح: رد شدن قبلیِ DI سنگین همچنان معتبر است تا وقتی چند ماژول واقعی وجود داشته باشد)
- [ ] Migration واقعی Room بهجای `fallbackToDestructiveMigration` + `MigrationTestHelper` (بعد از انتشار عمومی، destructive migration دیگر مجاز نیست)

**معیار پذیرش:** کشتن اپ در مرحله ۴ از ۵ → بازگشت دقیقاً به همان متن و همان انتخابها؛ ۰ داده گمشده.

---

### فاز ۳ — امنیت، حریم خصوصی، انطباق (هفته ۴–۵) · P0
**هدف:** قابل سپردن به مشتریان سازمانی.

- [ ] `android:allowBackup="false"` + `dataExtractionRules` + `fullBackupContent`؛ `network_security_config.xml` (cleartext off)؛ بررسی `android:exported`
- [ ] کلیدها: Keystore + `KeyGenParameterSpec`، چرخش کلید، قفل بایومتریک اختیاری برای تنظیمات، **عدم چاپ هیچ کلید/PII در لاگ یا پیام خطا**
- [ ] رمزنگاری DB (SQLCipher یا فیلدهای حساس) + خطمشی نگهداری (مثلاً ۹۰ روز لاگ) + «حذف همه دادههای من» از داخل اپ
- [ ] اعتبارسنجی فایل هنگام الصاق: magic bytes `%PDF`, `pages == 2`, MIME، نام امن، هش شواهد = هش پیام (جلوی الصاق گزارش دامنهای دیگر)
- [ ] `List-Unsubscribe` / `List-Unsubscribe-Post` **واقعی** (هدر قانونی، نه جعلی) + `Message-ID`, `In-Reply-To`, `References` برای پاسخها + `multipart/alternative`
- [ ] **Bounce/Complaint**: وبحوک Brevo (delivered/bounce/spam/unsubscribed [5](https://www.brevo.com/features/email-api/)) → سرکوب خودکار؛ جایگزین سبک بدون سرور: پویش صندوق ورودی برای `Mail Delivery Subsystem` و ثبت خودکار
- [ ] `PRIVACY.md` (FA/EN): موجودی داده، اهداف، طرفهای سوم (hcnsec/You.com/Brevo)، محل ذخیره، حق حذف؛ فرم Data Safety برای Play؛ ثبت «رضایت/رابطه تجاری» بهازای هر مخاطب + audit trail
- [ ] مدل تهدید سبک (STRIDE) + بررسی وابستگیها (Dependabot/OWASP) + `SECURITY.md` (انجام شد)

**معیار پذیرش:** تست نشت PII در لاگها سبز؛ بکاپ حاوی `sali.db` نیست؛ هر hard bounce طی ۲۴ ساعت سرکوب میشود.

---

### فاز ۴ — قابلیت اطمینان و سلامت ارسال (هفته ۵–۶) · P0
**هدف:** صفر ارسال تکراری، صفر ارسال ناقص، قابل ازسرگیری.

- [ ] **Idempotency**: `messageUuid` پایدار در `MessageEntity` → هدر/پارامتر UUID پشتیبانی شده Brevo با TTL ۳۰ دقیقه [2](https://www.brevo.com/products/transactional-email/)؛ قبل از هر retry همان کلید
- [ ]State machine صریح: `DRAFT → APPROVED → QUEUED → SENDING → SENT|FAILED|SKIPPED` همراه `leaseUntil` برای جلوگیری از دو worker همزمان؛ `requeueStuck` با lease منقضی
- [ ] جایگزینی/تقویت `SendService`: `WorkManager` (unique work + constraints + backoff نمایی + `setForeground`) یا حفظ foreground service همراه `START_STICKY`، WakeLock، و ذخیره وضعیت هر ثانیه — **هر دو فقط پیامهای APPROVED را میفرستند** (خط قرمز حفظ میشود)
- [ ] محدودیتها: ۴MB/فایل و ۲۰MB/ایمیل [1](https://help.brevo.com/hc/en-us/articles/4402811730962-Add-an-attachment-to-a-transactional-email)، سقف ساعتی/روزانه/سقف متناسب با منطقه زمانی کاربر، ساعات کاری، تقویم کسبوکار، circuit breaker روی ۴۲۹/۵xx
- [ ] خواندن جریانی پیوستها بهجای `readBytes()` تمام فایل در حافظه؛ پاکسازی بافر بعد از ارسال
- [ ] ساختار لاگ: `errorCode`, `providerMessageId`, `durationMs`, `bytesSent`, `attempt`, `costTokens`؛ خروجی CSV/JSON؛ داشبورد سلامت
- [ ] Crash/ANR reporting **با رضایت و بدون PII** + Kill-switch محلی برای توقف ارسال/غیرفعال کردن AI

**معیار پذیرش:** قطع شبکه و کشتن اپ وسط ارسال → دقیقاً یک بار ارسال؛ ۰ duplicate در ۱۰۰۰ شبیهسازی؛ ۰ OOM با ۲۰۰ پیوست.

---

### فاز ۵ — تجربه کاربری کلاس جهانی (هفته ۶–۷) · P1
- [ ] Onboarding: چکلیست «کلیدها ← تست اتصال ← ایمیل آزمایشی ← انتخاب پوشه»، اعتبارسنجی زنده کلیدها (یک فراخوانی کمهزینه)، راهنمای نامگذاری `_trial_fa` با نمونه بصری
- [ ] استخراج **تمام** رشتهها به `strings.xml` (+ `values-en`)، plurals، `BidiFormatter`، تاریخ/عدد با `Locale("fa")`
- [ ] Material 3 پویا (dynamic color)، تم روشن/تیره، تایپوگرافی و فونت مجاز (Vazirmatin)، آیکون و آرم حرفهای
- [ ] دسترسیپذیری: `contentDescription`، TalkBack، هدف لمسی ۴۸dp، کنتراست ≥ ۴٫۵:۱، مقیاس فونت، `semantics`
- [ ] ۲۰۰ دامنه: جستجو/فیلتر/مرتبسازی/انتخاب گروهی/Undo تأیید/پیشرفت ذخیرهشده؛ حالتهای خالی، خطا، آفلاین، در حال بارگذاری
- [ ] صفحات بزرگ/تبلت/landscape، edge-to-edge، `WindowSizeClass`
- [ ] کارایی: Baseline Profile، `LazyColumn` با `key`، `derivedStateOf`، لغو پردازش PDF، cold start < ۲s، ۰ jank

**معیار پذیرش:** Play Accessibility Scanner پاک؛ دو زبان کامل؛ سناریوی «اولین کمپین در ۱۰ دقیقه» توسط فرد غیرفنی موفق.

---

### فاز ۶ — اثربخشی فروش، مبتنی بر داده (هفته ۷–۸) · P1
**هدف:** «بیشترین نرخ تبدیل» از شعار به عدد قابل اندازهگیری تبدیل شود.

- [ ] **Experiment ID** روی هر پیام: سبک، نسخه subject، نسخه پرامپت، مدل، `evidenceHash` → جدول `experiment_assignments`
- [ ] پاسخ/پاسخ مثبت/مذاکره/پرداخت به تفکیک variant + بازه اطمینان ساده (بدون ادعای علمی بیپشتوانه)؛ نمودار درون اپ
- [ ] توالی پیگیری قابل تنظیم (مثلاً روز ۳/۷/۱۴، ۳ نوبت در هفته)، توقف خودکار هنگام پاسخ، **بازبینی انسانی برای تکتک لمسها** (خط قرمز حفظ میشود)
- [ ] `Pipeline`: ارزش معامله، پیشبینی درآمد، یادداشت، جستجو، خروجی CRM CSV، جلوگیری از سرنخ تکراری
- [ ] کنترل هزینه: بودجه توکن بهازای کمپین، کش پاسخ بر حسب `evidenceHash`، سقف هزینه روزانه، مدل fallback، تلورانس خطا، **نمایش مصرف اعتبار ۳٬۵۰۰$ / ۲۰۰$ در اپ**
- [ ] نسخهبندی پرامپت + مجموعه ارزیابی انسانی (rating ۱–۵ روی ۵۰ ایمیل) قبل از تغییر پرامپت در نسخه production
- [ ] امتیاز «پوشش شواهد» (چند واقعیت واقعی استفاده شده) و خوانایی متن، بهعنوان سیگنال کیفیت در UI

**معیار پذیرش:** برای هر ایمیل مشخص است کدام variant فرستاده شد و آیا پاسخ گرفت؛ هزینه هر ایمیل نمایش داده میشود؛ تغییر پرامپت بدون افت Golden Set ممکن نیست.

---

### فاز ۷ — CI/CD، توزیع و عملیات (هفته ۸–۹) · P0
- [ ] Pipeline کامل: `test → lint → detekt → coverage → bundleRelease → sign → SBOM → vuln-scan → Release notes → staged rollout`
- [ ] **AAB + Play App Signing**، `targetSdk=36` [2](https://primetestlab.com/blog/google-play-target-api-level-2026)، ۱۶KB page size، فهرست فروشگاه FA/EN، Data Safety، تست داخلی → بسته ۱۰٪ → ۵۰٪ → ۱۰۰٪
- [ ] معیارهای سلامت: crash-free sessions ≥ ۹۹٫۵٪، ANR < ۰٫۵٪؛ برنامه برگشت (rollback) و In-App Updates
- [ ] Runbook حوادث: «ارسال تکراری»، «کلید منقضی»، «سقف Brevo»، «بلاک شدن دامنه»؛ kill switch
- [ ] SBOM + checksums + امضای artifact + نگهداری ۶۰ روزه لاگها؛ هشدار انقضای اعتبار API
- [ ] توزیع مستقیم GitHub Release همچنان حفظ شود (برای نصب بدون Play)

**معیار پذیرش:** انتشار مرحلهای انجام شد، برگشت آزمایش شد، SBOM پیوست شد، هیچ CVE بحرانی باز نیست.

---

### فاز ۸ — مقیاس / چندکاربره (فقط با توجیه کسبوکاری) · P2
Backend اختیاری برای: وبحوکها، همگامسازی suppression و خط لوله بین چند دستگاه/اپراتور، کلیدهای per-tenant، RBAC، audit log مرکزی، ورودی API گزارشها، صورت حساب. **تا وقتی یک اپراتور/یک گوشی است، عمداً انجام نمیشود** — همان درسی که از رد کردن فورک Thunderbird و معماری سنگین گرفتیم.

---

## ۴. ماتریس اولویت (Impact × Effort)

| آیتم | اثر | هزینه | اولویت |
|---|---|---|---|
| Idempotency ارسال | بحرانی | کم | **P0 - هفته ۱** |
| تست واحد + Golden Set | بحرانی | متوسط | **P0 - هفته ۱–۲** |
| ذخیره کمپین (process death) | بحرانی | متوسط | **P0 - هفته ۲** |
| `targetSdk=36` + AAB امضاشده | بحرانی (انسداد انتشار) | متوسط | **P0 - هفته ۱** |
| Backup/PII + Privacy | بحرانی | کم | **P0 - هفته ۱** |
| سقف ۴MB + بررسی صفحات واقعی | بالا | کم | **P0 - هفته ۱** |
| Bounce/complaint خودکار | بالا | متوسط | P1 - هفته ۵ |
| Crash reporting + لاگ ساختاریافته | بالا | کم | P1 - هفته ۵ |
| Onboarding + strings/EN + a11y | بالا | متوسط | P1 - هفته ۶ |
| Experiment tracking + هزینه | بالا (مستقیم روی درآمد) | متوسط | P1 - هفته ۷ |
| Modularization / Hilt | متوسط | زیاد | P2 - فقط در صورت تیمی شدن |
| Backend چندکاربره | نامشخص | خیلی زیاد | P2 - مشروط |

---

## ۵. KPI و SLO (اعداد هدف v3.0)

| شاخص | هدف | نحوه اندازهگیری |
|---|---|---|
| ارسال تکراری | **۰** | تست idempotency + `providerMessageId` یکتا |
| ایمیل بدون تأیید انسان | **۰** | تست وضعیت `APPROVED` |
| عدد/ادعای جعلی | **۰** | Golden Set در CI |
| درخواست حذف → توقف ارسال | < ۶۰ ثانیه | تست suppression |
| از دست رفتن کمپین | **۰** | تست process death |
| موفقیت ارسال (بهجز hard bounce) | ≥ ۹۹٪ | داشبورد سلامت |
| Crash-free sessions | ≥ ۹۹٫۵٪ | Play Vitals / reporting با رضایت |
| Cold start | < ۲s | Baseline Profile |
| پوشش تست منطق دامنه | ≥ ۷۰٪ | JaCoCo |
| زمان اولین کمپین (کاربر تازه) | < ۱۰ دقیقه | تست انسانی |
| هزینه هر ایمیل | نمایش داده شود | شمارش توکن + فراخوانیها |
| نرخ پاسخ / تبدیل | قابل تفکیک به variant | جدول آزمایشها |

---

## ۶. ماتریس ریسک

| ریسک | احتمال | شدت | مهار |
|---|---|---|---|
| ارسال دوباره به یک مدیرعامل | متوسط | **فاجعهبار** | idempotency UUID + lease + تست |
| بلاک/تعلیق حساب Brevo (ToS ایمیل سرد) | متوسط | بالا | فقط لیست شخصی، بازبینی انسان، opt-out، bounce suppression، SPF/DKIM/DMARC تأییدشده |
| نشت کلید API / PII | کم | بالا | Keystore، بدون لاگ، بکاپ بسته، `SECURITY.md` |
| تمام شدن اعتبار hcnsec/You.com وسط کمپین | متوسط | متوسط | بودجه، سقف روزانه، هشدار، مدل fallback |
| عدد جعلی در ایمیل → از دست رفتن اعتبار حرفهای | کم | **بالا** | `QualityGate` + Golden Set |
| تغییر رفتار Android 16 / Play | قطعی | متوسط | `targetSdk=36` زودتر، تست دستگاه، انتشار مرحلهای |
| وابستگی به `pdfbox-android` / `security-crypto` alpha | متوسط | متوسط | پین نسخه، Dependabot، جایگزین پایدار |

---

## ۷. Go / No-Go
چکلیست کامل و قابل تیکزدن: **[`docs/RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md)**.
قاعده: **هر بند P0 که تیک نخورده = No-Go**، حتی اگر بیلد سبز باشد. بیلد سبز «لازم» است نه «کافی».

---

## ۸. اگر فقط ۲ هفته وقت داری (MVP+)
هفته ۱: P0-1 تا P0-8 (همه شکافهای بحرانی) + تست واحد منطق خالص.
هفته ۲: `CampaignRepository` + idempotency + Golden Set در CI + AAB امضاشده + `PRIVACY.md`.
نتیجه: محصولی که **قابل اعتماد و قابل انتشار** است، هرچند هنوز onboarding/آزمایش A/B/چندزبانه ندارد. این همان «آماده» با کمترین ریسک است.

---

## ۹. هزینهها و منابع

| مورد | وضعیت | نکته |
|---|---|---|
| hcnsec | ۳٬۵۰۰$ اعتبار | هدف: ≤ ۳ فراخوانی/ایمیل، کش بر `evidenceHash`، سقف هزینهای که در فاز ۶ اضافه میشود |
| You.com | ۲۰۰$ اعتبار | ۱ جستجو/دامنه (۷۰ جستجو)؛ کش دوسطحی |
| Brevo | پلن رایگان ۳۰۰ ایمیل/روز [3](https://www.captaindns.com/en/blog/brevo-transactional-email-technical-guide) | برای ۷۰ ایمیل/نوبت کافی است؛ محدودیت پیوست ۴MB رعایت شود [1](https://help.brevo.com/hc/en-us/articles/4402811730962-Add-an-attachment-to-a-transactional-email) |
| Google Play | حساب توسعهدهنده + App Signing | `targetSdk=36` الزامی [2](https://primetestlab.com/blog/google-play-target-api-level-2026) |
| زیرساخت | فعلاً صفر (همه on-device) | فاز ۸ فقط در صورت چند اپراتور |

---

## ۱۰. چیزهایی که عمداً **انجام نمیدهیم** (Non-Goals)

برای حفظ هویت محصول، حتی در نسخه «کلاس جهانی»:
- ❌ scraping / حدس ایمیل / خرید لیست؛ ❌ ارسال بدون بازبینی انسان؛ ❌ دور زدن فیلتر اسپم، هدر جعلی، چرخش قالب؛
- ❌ مهلت/تخفیف/ترساندن ساختگی (فوریت فقط از یافته واقعی)؛ ❌ عدد، درصد یا نام مشتری ساختگی؛
- ❌ ارسال خودکار «به محض تولید» حتی با WorkManager (WorkManager فقط **صف تأییدشده** را اجرا میکند)؛
- ❌ فورک Thunderbird؛ ❌ بکاند چندکاربره تا زمان توجیه؛ ❌ ادعای «تضمین رسیدن به اینباکس».

اینها همان خطوط قرمز [`MASTER_BLUEPRINT.md`](MASTER_BLUEPRINT.md) §۳ هستند و در تعریفDone هم گنجانده شدهاند: **۰ ایمیل بدون تأیید، ۰ عدد جعلی، ۰ ارسال تکراری**.

---

## ۱۱. پیوست الف — نگاشت شکاف → فایل

| فایل | تغییرات پیشبینیشده |
|---|---|
| `app/build.gradle.kts` | SDK 36، versionCode، R8، KSP، Version Catalog، test deps |
| `AndroidManifest.xml` | `allowBackup=false`, `dataExtractionRules`, `networkSecurityConfig`, مرور `exported` |
| `app/src/main/res/xml/network_security_config.xml` 🆕 | غیرفعال کردن cleartext |
| `data/Db.kt` | `messageUuid`, `experimentId`, `costTokens`, `providerMessageId`, `consent`, `leaseUntil`, Migrations |
| `data/Prefs.kt` | `maxAttachmentMb=4`, budget، kill switch، رضایت reporting |
| `data/ReportBundle.kt` | `AttachmentPolicy`: بررسی `%PDF`, `pages==2`, هش |
| `ai/AiClient.kt` | `AiProvider` interface، نسخه پرامپت، شمارش توکن، fallback مدل |
| `ai/QualityGate.kt` | Golden Set، امتیاز پوشش شواهد |
| `mail/MailSender.kt` | UUID idempotency، `List-Unsubscribe`، `Message-ID`، `multipart/alternative`، سقف ۴MB |
| `send/SendEngine.kt` | `SendPolicy`, lease، backoff، circuit breaker، لاگ ساختاریافته، جریانی کردن پیوست |
| `ui/*` | ViewModel/UiState، استخراج رشتهها، onboarding، a11y، جستجو، Undo |
| `.github/workflows/quality.yml` 🆕 | test/lint/detekt/coverage/vuln |
| `.github/workflows/build-apk.yml` | AAB، امضا، SBOM،Release notes |
| `docs/*` | `PRIVACY.md`, `CONTRIBUTING.md`, runbook حوادث |

---

## ۱۲. پیوست ب — Issueهای قابل کپی در GitHub (ترتیب اجرا)

1. `P0: targetSdk/compileSdk 36 + versionCode 3 + R8 + KSP` `build` `blocker`
2. `P0: Disable backup & network cleartext; PRIVACY.md` `security`
3. `P0: Brevo idempotency UUID per message` `reliability`
4. `P0: Attachment cap 4MB/file + %PDF + 2-page verification at send time` `reliability`
5. `P0: Unit tests for 7 pure logics + 100-email Golden Set` `tests`
6. `P0: Persist campaign to Room (process-death recovery)` `architecture`
7. `P0: quality.yml — test/lint/detekt/coverage gate` `ci`
8. `P0: Play AAB + Play App Signing + staged rollout` `release`
9. `P1: Bounce/complaint webhook → auto suppression` `deliverability`
10. `P1: Structured logs + cost/token telemetry + crash reporting (opt-in)` `observability`
11. `P1: Onboarding + string extraction + EN locale + a11y` `ux`
12. `P1: Experiment IDs, variant report, prompt versioning, spend cap` `growth`
13. `P2: Room migrations (remove destructive fallback)` `data`
14. `P2: Modularization / DI — only if team grows` `architecture`

---

## ۱۳. منابع / مبانی تصمیم

- الزام Google Play به `targetSdk/API 36` برای اپهای جدید و بهروزشده از ۳۱ اوت ۲۰۲۶ و پایان تمدید در ۱ نوامبر ۲۰۲۶: [2](https://primetestlab.com/blog/google-play-target-api-level-2026)، [4](https://ecorpit.com/android-target-api-36-play-store-deadline-migration-2026/)
- محدودیت پیوست ایمیل تراکنشی Brevo (۴MB هر فایل / ۲۰MB کل): [1](https://help.brevo.com/hc/en-us/articles/4402811730962-Add-an-attachment-to-a-transactional-email) · رقم ۲۵MB در صفحه محصول [5](https://www.brevo.com/features/email-api/) → ملاک محافظهکارانه ۴MB
- پشتیبانی Brevo از Idempotency UUID (TTL ۳۰ دقیقه)، وبحوکهای delivered/bounce/complaint و suppression: [2](https://www.brevo.com/products/transactional-email/), [5](https://www.brevo.com/features/email-api/)
- پلن رایگان Brevo (۳۰۰ ایمیل/روز) و محدودیتها: [3](https://www.captaindns.com/en/blog/brevo-transactional-email-technical-guide)
- خطوط قرمز پروژه و معماری فعلی: [`docs/MASTER_BLUEPRINT.md`](MASTER_BLUEPRINT.md)

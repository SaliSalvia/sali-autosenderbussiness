# Changelog

همه تغییرات قابلتوجه این پروژه در این فایل ثبت میشود. قالب بر اساس [Keep a Changelog](https://keepachangelog.com/fa/1.1.0/) و نسخهدهی [Semantic Versioning](https://semver.org/lang/fa/) است.
> نسخه فعلی کاندید: `versionCode = 6` و `versionName = "2.0.3"` در `app/build.gradle.kts`.

## [2.0.3] — 2026-11-18 · Final hardening candidate

### Security and reliability
- ارسال صفی درست پیش از درخواست provider دوباره cancellation، وضعیت approval، محتوای تأییدشده، مالک lease و opt-out را بررسی می‌کند؛ در صورت لغو پیش از درخواست، claim به صف بازمی‌گردد.
- settlement وضعیت نهایی را روی آخرین ردیف Room اعمال می‌کند تا snapshot قدیمی ویرایش یا demotion هم‌زمان را overwrite نکند.
- پاکسازی کامل داده‌ها ابتدا queue را متوقف می‌کند، cacheها و draftها را پاک می‌کند و مجوز پایدار SAF را آزاد می‌کند.
- JSON sidecar با سقف ۲۵۶KiB خوانده می‌شود، domain آن باید با پوشه هدف منطبق باشد و اعداد منفی، اعشاری یا خارج از محدوده رد می‌شوند.
- promptها صریحاً PDF، sidecar، You.com، قالب‌های مرجع، پاسخ مشتری و draftهای قبلی را محتوای غیرقابل اعتماد می‌دانند؛ دستورهای جاسازی‌شده نباید اجرا شوند.
- هدر `List-Unsubscribe-Post` حذف شد؛ برنامه تنها mailto unsubscribe را ادعا می‌کند و قابلیت RFC 8058 one-click را بدون endpoint واقعی تبلیغ نمی‌کند.

### Privacy and release readiness
- خط‌مشی حریم خصوصی اکنون ذخیره محلی متن PDF در Room `pdf_cache` و نبود رمزنگاری مستقل دیتابیس را افشا می‌کند.
- انتشار فقط با تگ نسخه‌ای منطبق انجام می‌شود؛ `v2.0.2` قدیمی بدون asset باقی می‌ماند و برای این کاندید، نسخه جدید `v2.0.3` / `versionCode 6` آماده می‌شود.

### Validation
CI این کاندید پس از بازبینی باید تست، lint، APK و AAB را تأیید کند. تست دستگاه واقعی Android 16 و موارد باقی‌مانده P0 در `docs/RELEASE_CHECKLIST.md` هنوز شرط انتشار عمومی/Play هستند.

## [2.0.2] — 2026-11-18 · UX and copy polish

### Improved
- داشبورد خانه برای نمایش پایدار در عرض‌های کوچک به چیدمان دو در دو KPI تغییر کرد؛ عنوان شاخص مصرف توکن نیز روشن‌تر شد.
- وضعیت تکمیل ویزارد ایمیل اکنون reactive است؛ پس از راه‌اندازی اولیه، بازکردن دوباره تنظیم ایمیل دکمه بازگشت را پنهان نمی‌کند.
- متن‌های خانه، کمپین و تنظیمات پیوست با سیاست واقعی انتخاب جفت Trial فارسی/انگلیسی (هر فایل دقیقاً دو صفحه) همگام شدند.
- متن تأیید ارسال اکنون بررسی تازگی شواهد و جفت دوزبانه را توضیح می‌دهد و دیگر ادعای انتخاب دو PDF جدیدتر را ندارد.

### Release status
این نسخه کاندید UX است؛ CI و بررسی نصب روی دستگاه واقعی باید پیش از اعلام آمادگی انتشار انجام شود. موانع P0 در `docs/RELEASE_CHECKLIST.md` همچنان معتبرند.

## [2.0.1] — 2026-11-18 · Release hardening candidate

### Fixed
- پیش از ارسال، بسته شواهد از پوشه انتخاب‌شده دوباره خوانده می‌شود؛ هش کامل SHA-256 و هویت/ترتیب جفت PDF با وضعیت زمان تأیید مقایسه می‌شود. تغییر شواهد، مجوز از دست‌رفته یا تغییر پیوست باعث توقف امن پیام می‌شود.
- ایمیل تست به صندوق فرستنده نیز نام، نوع، تعداد صفحات و حجم پیوست را دوباره اعتبارسنجی می‌کند؛ تست بدون پیوست همچنان مجاز است.
- انتخاب نامزدهای پیوست فقط بهترین جفت Trial هم‌خانواده را پیش از سقف شش فایل اولویت می‌دهد.
- محدودیت خواندن PDF با Guard همسان شد: فایل دقیقاً ۴MB مجاز است و فقط فایل بزرگ‌تر رد می‌شود.

### Tests
- تست‌های خالص برای هش شواهد، تغییر هویت پیوست، نام/ترتیب فایل‌ها و انتخاب بهترین جفت.
- تست مرزی برای فایل دقیقاً در حد ۴MB.
- نگهداری لاگ‌های عملیاتی روی دستگاه به ۹۰ روز محدود شد؛ پاکسازی رکوردهای منقضی‌شده در startup انجام می‌شود.

### Release status
این یک candidate سخت‌سازی است، نه اعلام Go برای انتشار عمومی. اجرای تازه CI و تست دستگاهی Android 16 هنوز لازم است. رضایت/مبنای مجاز مخاطبان، SPF/DKIM/DMARC، کلید امضای Play و دیگر موانع P0 در `docs/RELEASE_CHECKLIST.md` باز می‌مانند.

## [Unreleased] — hardening follow-up (versionCode 4)
### Fixed — رگرسیون `main`
- **بیلد شکسته `main` رفع شد.** PRهای Dependabot بدون اینکه CI روی `pull_request` اجرا شود ادغام شده بودند و `AGP 9.5.0` + `Kotlin 2.4.21` + `Gradle 8.9` + `core-ktx 1.19.1` را روی `compileSdk 34` گذاشته بودند. AGP 9.5 به Gradle ≥ 9.6 و built-in Kotlin نیاز دارد و `core-ktx 1.19.1` به `compileSdk ≥ 37`.
- ابزار ساخت به یک مجموعه **سازگار و پینشده** برگشت/ارتقا یافت: AGP `8.13.2` + Gradle `8.13` + Kotlin `2.2.21` + `compileSdk/targetSdk = 36` + `buildTools 36.0.0` + Compose BOM `2025.12.00`.
- workflow اکنون روی `pull_request` به `main` و تگهای `v*` هم اجرا میشود؛ `dependabot.yml` از merge تکبهتک major ابزار ساخت جلوگیری میکند.

### Added — ایمنی ارسال و پایداری
- `MessageState` (DRAFT → APPROVED → QUEUED → SENDING → SENT/FAILED/SKIPPED/**UNCERTAIN**) و **claim/lease اتمی** در Room: فقط ردیفهای `APPROVED/QUEUED` قابل تصاحباند و اجرای کشتهشده پیام `SENDING` را رها نمیکند.
- UUID پایدار هر پیام برای `headers.idempotencyKey` در Brevo؛ پاسخ دقیق `400 + code=duplicate_parameter` از پردازش مجدد همان کلید جلوگیری می‌کند. در SMTP، `Message-ID` فقط برای threading/traceability است و deduplication را تضمین نمی‌کند.
- نتیجه مبهم (HTTP 408/5xx، قطع شبکه، یا هر خطا پس از آغاز `SMTP sendMessage`) به `UNCERTAIN` می‌رود و خودکار retry نمی‌شود.
- `AttachmentGuard`: بررسی **بایتهای واقعی** پیش از network call — امضای `%PDF-`، دقیقاً ۲ نسخه trial (فا + en)، `pages == 2`، ≤ ۴MB/فایل و ≤ ۲۰MB کل (مستند محافظهکارانه Brevo). خواندن جریانی با سقف سخت بهجای `readBytes()` بینهایت.
- `PrivacyRedactor`: ماسک ایمیل در لاگ/UI/خروجی لاگ، حذف کلیدها و ایمیلها از پیامهای خطا و `lastError`.
- ویرایش متن **پس از تأیید**، approval را باطل و ردیف صف را `DRAFT` میکند (هیچ نسخه تأییدنشدهای ارسال نمیشود).
- **`MIGRATION_2_3`** واقعی Room جدول `campaigns` برای ذخیره پیشنویس کمپین (دامنهها/مخاطبان/انتخاب/زمینه عمومی)، بدون متن PDF و بدون کلید.
- تست واحد JVM (بدون امولاتور): `ContactsParser`, `DomainValidator`, `QualityGate`, `AttachmentPolicy`, `AttachmentGuard`, `MessageState`, `PrivacyRedactor` و **Golden Set** مصنوعی/بینامشده.

### Added — محصول و UI
- داشبورد کاری شبیه Thunderbird: شمارنده دامنه/تأییدشده/وضعیت صف، و **یک دکمه «ارسال همه پیامهای تأییدشده»** با دیالوگ تأیید (فقط سرویس صف را استارت میکند، بازبینی را دور نمیزند).
- کنسول صف: وضعیت هر ایمیل، مکث/ادامه/لغو، لاگ زنده، خروجی لاگ بدون PII.
- تم Material 3 روشن/تیره + RTL ثابت، رشتههای UI در `strings.xml`، `contentDescription` برای آیکونها، `ContextCompat.startForegroundService` (سازگار با minSdk 24).

### Changed — امنیت و انتشار
- `allowBackup=false`، `usesCleartextTraffic=false`، `res/xml/network_security_config.xml` و `res/xml/data_extraction_rules.xml` (حذف cloud backup / device transfer در Android 12+).
- «پاکسازی کامل دادههای اپ» در تنظیمات با دیالوگ تأیید: `App.clearAllData()` جداول `messages`/`logs`/`campaigns`، تنظیمات رمزنگاریشده و session را پاک میکند؛ `PRIVACY.md` FA/EN به همان نام دکمه بهروز شد.
- `isMinifyEnabled=true` + `isShrinkResources=true` با قواعد R8 محافظهکارانه؛ `lint { abortOnError = false }` بهعنوان گزارش.
- `versionCode = 4`, `versionName = "2.0.1"`, `targetSdk/compileSdk = 36` (الزام Play از ۲۰۲۶-۰۸-۳۱).
- CI: `clean test lintDebug assembleDebug assembleRelease bundleRelease`، نصب صریح `platforms;android-36`، artifact `SaliBussiness-APK` شامل APK + AAB + `SHA256SUMS.txt`، Release `latest` فقط روی `main`، امضای ریلیس فقط با چهار سیکرت `SALI_KEYSTORE_*`.
- `SECURITY.md` بهوضعیت جدید بهروز شد؛ [`PRIVACY.md`](PRIVACY.md) (FA/EN) و [`CONTRIBUTING.md`](CONTRIBUTING.md) اضافه شدند.

### Known limitations (باقیمانده)
Golden Set فعلاً **مصنوعی** است (نمونه واقعی بینامشده در مخزن قرار نمیگیرد)؛ وبحوک bounce/complaint، رضایت مستند بهازای هر مخاطب، detekt/ktlint، KSP، AAB/Play App Signing امضاشده و تأیید عملی SPF/DKIM/DMARC هنوز انجام نشدهاند. «پاکسازی کامل دادهها» داخل تنظیمات و `@xml/data_extraction_rules` پیادهسازی شدند. فاز ۰ نقشه راه بهمعنای «آماده Play» نیست.

## [Unreleased] — اسناد نقشه راه
### Added
- [`docs/WORLD_CLASS_ROADMAP.md`](docs/WORLD_CLASS_ROADMAP.md): نقشه راه ۹ هفتهای رسیدن به محصول کلاس جهانی/آماده انتشار، شامل ارزیابی baseline، شکافهای P0، ماتریس ریسک، KPI/SLO و فهرست Issueها.
- [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md): چکلیست Go/No-Go برای انتشار.
- [`SECURITY.md`](SECURITY.md): خطمشی امنیتی و مسیر گزارش آسیبپذیری.
- `.github/dependabot.yml`: بهروزرسانی هفتگی وابستگیهای Gradle و GitHub Actions.

## [2.0.0] — 2026-11-03
نخستین نسخه قابل نصب؛ [PR #1](https://github.com/SaliSalvia/sali-autosenderbussiness/pull/1) merged در `main` (`38ff5f8`)، [بیلد سبز ۴۱۵۵۲۹۷۹۷۷۴](https://github.com/SaliSalvia/sali-autosenderbussiness/actions/runs/41552979774) و [Release `latest`](https://github.com/SaliSalvia/sali-autosenderbussiness/releases/tag/latest).

### Fixed
- بیلد #۴: `LabeledField` (trailing lambda به پارامتر انتهایی `Boolean` میرفت)، escape نامعتبر `\.` و `continue` داخل `ifEmpty {}` در `ContactsParser`.
- CI: آپلود لاگ کامپایلر هنگام شکست، بیلد روی شاخههای `arena/**`، APK نصبپذیر همیشه موجود.

### Added
- **قانون PDF:** AI تمام PDFهای پوشه دامنه را میخواند (`ReportBundle`، `readBundle`)؛ فقط دو نسخه trial دوصفحهای فارسی + انگلیسی پیوست و ارسال میشود (`AttachmentPolicy`)، با گارد مجدد در `SendEngine`.
- **موتور متن ۳ مرحلهای** hcnsec (پیشنویس ← ویراستار تبدیل ← اصلاح انطباق) + `QualityGate` محلی ضد عدد/ادعای جعلی، ۳ سبک، ۳ جایگزین subject، preheader، امضا و opt-out قطعی.
- You.com Search API مستند (`ydc-index.io/v1/search` + `X-API-Key` + `count`)، کش یک جستجو بهازای هر دامنه، پاکسازی داده شخصی.
- فهرست عدم ارسال، تشخیص درخواست حذف در صندوق ورودی، بازبینی انسانی پیگیریها، شمارندههای واقعی ارسال، `requeueStuck`.
- [`docs/MASTER_BLUEPRINT.md`](docs/MASTER_BLUEPRINT.md) و [`examples/sidecar.example.json`](examples/sidecar.example.json).

### Changed
- Room v2؛ `Db.kt` ستونهای `preheader`, `attachmentNames`, `evidenceHash`, `qualityOk`؛ `Prefs` تنظیمات مدل/ویراستار/سقف فراخوانی/سیاست پیوست.

### Known limitations
- بدون تست خودکار، `targetSdk=34`، `versionCode=1`، `isMinifyEnabled=false`، کمپین فقط در حافظه، بدون idempotency ارسال، متنهای UI هاردکد — همه در نقشه راه P0 فهرست شدهاند.

## [1.0.0] — 2026-11-02
- پیادهسازی اولیه ۲۵ فایل (Kotlin + Compose، Room، OkHttp، PDFBox، JavaMail)، ساختار ۵ مرحلهای کمپین، IMAP، خط لوله فروش، CI بیلد APK.
- بیلدهای #۱ تا #۴ همگی در مرحله کامپایل شکست خوردند (وابستگیها، ایمپورت `dp`، مختصات `pdfbox-android`، خطاهای کامپایل Kotlin)؛ جزئیات در `MASTER_BLUEPRINT.md` §۹.

# Changelog

همه تغییرات قابلتوجه این پروژه در این فایل ثبت میشود. قالب بر اساس [Keep a Changelog](https://keepachangelog.com/fa/1.1.0/) و نسخهدهی [Semantic Versioning](https://semver.org/lang/fa/) است.
> ✅ همگامسازی انجام شد: `versionCode = 3` و `versionName = "2.0.0"` در `app/build.gradle.kts` (پیشتر `1 / 1.0.0` بود). بند فاز ۰ نقشه راه بسته شد.

## [Unreleased] — سختسازی 2.1 / Hardening (versionCode 3)
### Fixed — رگرسیون `main`
- **بیلد شکسته `main` رفع شد.** PRهای Dependabot بدون اینکه CI روی `pull_request` اجرا شود ادغام شده بودند و `AGP 9.5.0` + `Kotlin 2.4.21` + `Gradle 8.9` + `core-ktx 1.19.1` را روی `compileSdk 34` گذاشته بودند. AGP 9.5 به Gradle ≥ 9.6 و built-in Kotlin نیاز دارد و `core-ktx 1.19.1` به `compileSdk ≥ 37`.
- ابزار ساخت به یک مجموعه **سازگار و پینشده** برگشت/ارتقا یافت: AGP `8.9.1` + Gradle `8.11.1` + Kotlin `2.0.21` + `compileSdk/targetSdk = 36` + `buildTools 36.0.0` + Compose BOM `2024.10.01`.
- workflow اکنون روی `pull_request` به `main` و تگهای `v*` هم اجرا میشود؛ `dependabot.yml` از merge تکبهتک major ابزار ساخت جلوگیری میکند.

### Added — ایمنی ارسال و پایداری
- `MessageState` (DRAFT → APPROVED → QUEUED → SENDING → SENT/FAILED/SKIPPED/**UNCERTAIN**) و **claim/lease اتمی** در Room: فقط ردیفهای `APPROVED/QUEUED` قابل تصاحباند و اجرای کشتهشده پیام `SENDING` را رها نمیکند.
- **idempotency UUID پایدار** بهازای هر پیام: `headers.idempotencyKey` در Brevo (پاسخ `duplicate_parameter` = موفقیت، نه retry) و `Message-ID` قطعی در SMTP.
- نتیجه **گمشده** (timeout/5xx/قطع شبکه) دیگر هرگز بهصورت خودکار retry نمیشود → وضعیت `UNCERTAIN` و تصمیم انسان.
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
- `versionCode = 3`, `versionName = "2.0.0"`, `targetSdk/compileSdk = 36` (الزام Play از ۲۰۲۶-۰۸-۳۱).
- CI: `clean test lintDebug assembleDebug assembleRelease bundleRelease`، نصب صریح `platforms;android-36`، artifact `SaliBussiness-APK` شامل APK + AAB + `SHA256SUMS.txt`، Release `latest` فقط روی `push` به `main`، امضای ریلیس فقط با چهار سیکرت `SALI_KEYSTORE_*`. گروه concurrency شامل `github.event_name` است تا اجرای `pull_request` بیلد `main` را لغو نکند؛ شرط انتشار صریحاً `github.event_name == 'push'` را میخواهد.
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

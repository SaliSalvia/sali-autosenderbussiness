# Changelog

همه تغییرات قابلتوجه این پروژه در این فایل ثبت میشود. قالب بر اساس [Keep a Changelog](https://keepachangelog.com/fa/1.1.0/) و نسخهدهی [Semantic Versioning](https://semver.org/lang/fa/) است.
> نکته: تا قبل از v2.0، `versionCode`/`versionName` داخل `app/build.gradle.kts` همچنان `1 / 1.0.0` بود؛ همگامسازی آن با تگها یکی از بندهای [`docs/WORLD_CLASS_ROADMAP.md`](docs/WORLD_CLASS_ROADMAP.md) فاز ۰ است.

## [Unreleased]
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

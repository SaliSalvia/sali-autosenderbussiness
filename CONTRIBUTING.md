# مشارکت / Contributing — SaliBussiness

> خطوط قرمز این مخزن **غیرقابل مذاکرهاند**. پیش از هر PR، [`docs/MASTER_BLUEPRINT.md`](docs/MASTER_BLUEPRINT.md) و [`SECURITY.md`](SECURITY.md) را بخوانید.

## گردش کار
1. شاخه جدید از `main` بسازید (نامگذاری `arena/**` یا `feat/**`).
2. تغییر بدهید و **حتماً** `./gradlew clean test lintDebug assembleDebug` را اجرا کنید.
3. PR بدهید. CI روی **همه PRها به `main`** اجرا میشود (تست + lint + بیلد APK/AAB)؛ بدون بیلد سبز merge نکنید.
4. ارتقای **major** ابزار ساخت (AGP / Gradle / Kotlin / compileSdk / Compose BOM) بهصورت هماهنگ و در یک PR مجزا همراه Migration Guide انجام شود، نه تکبهتک با Dependabot.

## چکلیست قبل از PR
- [ ] هیچ کلید، توکن، رمز، لاگ واقعی یا PII در کد/تست/اسکرینشات/توضیح PR نیست.
- [ ] تست واحد برای منطق خالص اضافه شد (`app/src/test/...`)؛ Golden Set همچنان سبز است.
- [ ] هیچ مسیر جدیدی از `DRAFT` به `MailSender` اضافه نشده؛ فقط `APPROVED/QUEUED` قابل claim است.
- [ ] قانون «فقط ۲ نسخه trial دوصفحهای» همچنان در `AttachmentGuard` + `SendEngine` برقرار است.
- [ ] پیام خطا/لاگ جدید از `PrivacyRedactor` عبور میکند.
- [ ] تغییر اسکیمای Room همراه **migration واقعی** است (نه `fallbackToDestructiveMigration` بهتنهایی).
- [ ] رشتههای جدید UI در `res/values/strings.xml` قرار گرفتند و `contentDescription` دارند.

## ممنوعیتها
 scraping / جمعآوری خودکار مخاطب · غنیسازی داده شخصی · ارسال بدون تأیید انسان · عدد یا ادعای جعلی · فوریت/مهلت ساختگی · دور زدن فیلتر اسپم · هدر یا `Re:/Fwd:` جعلی · چرخش قالب برای فرار از تشخیص · قرار دادن داده real مشتری در تستها.

## سبک
Kotlin + Jetpack Compose Material 3، RTL فارسی، Persian-first copy، نامگذاری روشن، کامنت فقط برای «چرا» (نه «چه»). نسخهها در [`CHANGELOG.md`](CHANGELOG.md).

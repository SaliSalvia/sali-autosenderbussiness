# Security Policy — SaliBussiness

## Reporting a vulnerability
لطفاً آسیبپذیریها را **در ملأ عام Issue باز نکنید**. از مسیر امن زیر استفاده کنید:
**GitHub → Security → Report a vulnerability** (Private Vulnerability Reporting) برای این ریپو،
یا یک پیام خصوصی به مالک ریپو از طریق پروفایل `SaliSalvia`.

موارد لازم در گزارش: نسخه اپ/کامیت، سناریوی تکثیر، اثر، و اینکه آیا داده real کاربر درگیر است.
هدف پاسخ اولیه: ۷۲ ساعت. تا زمان رفع، جزئیات فنی منتشر نمیشود.

## Scope
- اپلیکیشن اندروید `ir.salisec.autosender` و کد منبع این ریپو
- ذخیرهسازی کلیدها، مدیریت پیوستها، ارسال ایمیل، و پردازش گزارشهای PDF
- خارج از دامنه: سرویسهای شخص ثالث (hcnsec / You.com / Brevo) — آسیبپذیری آنها باید مستقیماً به ارائهدهنده گزارش شود

## Data handled (and how)
| داده | محل | محافظت فعلی |
|---|---|---|
| کلیدهای hcnsec / You.com / Brevo / IMAP | `EncryptedSharedPreferences` (AES، Keystore دستگاه) | رمزنگاری at rest؛ هرگز در لاگ/artifact/Release چاپ نمیشود |
| لیست مخاطبان (دامنه، ایمیل، نام)، موضوع و متن ایمیلها، لاگها | Room `sali.db` روی دستگاه | فقط محلی؛ بدون آپلود به سرور ما (هیچ بکندی وجود ندارد). آدرسها در لاگها و خروجی لاگ **ماسک** میشوند (`PrivacyRedactor`) |
| متن گزارشهای PDF | حافظه موقتی حین تولید متن | فقط به hcnsec بهعنوان زمینه نگارش ارسال میشود |
| زمینه عمومی شرکت | You.com Search API | فقط اطلاعات سطح شرکت؛ snippetها از ایمیل/تلفن پاکسازی میشوند |

✅ **رفعشده در 2.1.0:** `android:allowBackup="false"`، `usesCleartextTraffic="false"`، `network_security_config` (فقط system Trust Anchors)، لاگها و خطاها بدون PII/کلید، Migration واقعی Room `2→3`، و idempotency UUID با حالت `UNCERTAIN` بهجای retry خودکار هنگام گمشدن پاسخ. سیاست کامل: [`PRIVACY.md`](PRIVACY.md).

> ⚠️ **شکافهای باقیمانده (قبل از انتشار عمومی):** Golden Set واقعی، وبحوک bounce/complaint، رضایت مستند بهازای هر مخاطب، تأیید عملی SPF/DKIM/DMARC دامنه فرستنده، و Play App Signing / AAB امضاشده. «پاکسازی کامل دادهها» داخل اپ و `dataExtractionRules` اضافه شدند. جزئیات: [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md).

## Non-negotiable rules for contributors
- هیچ کلید، توکن، رمز یا نمونهای از PII واقعی را در کد، لاگ، اسکرینشات، تست یا توضیح PR قرار ندهید.
- هیچ ماژولی برای scraping ایمیل/نام/تلفن، حدس آدرس، یا دور زدن فیلتر اسپم اضافه نکنید.
- ارسال فقط بعد از تأیید صریح انسان؛ متن فقط از داده واقعی همان گزارش؛ عدد/ادعای جدید ممنوع.
- هدرها باید هویت واقعی فرستنده باشند؛ `Re:/Fwd:` جعلی، spoofing و چرخش قالب ممنوع.
- درخواست حذف (opt-out) باید فوراً و بهصورت دائمی اعمال شود.

## Dependency & build security
- وابستگیها از `google()` / `mavenCentral()` و **پینشده**اند؛ `Dependabot` هفتگی PR میسازد ولی major ابزار ساخت (AGP/Kotlin) بهصورت هماهنگ و دستی مهاجرت داده میشود.
- **همه PRها به `main`** اکنون `clean test lintDebug assembleDebug assembleRelease bundleRelease` را در CI اجرا میکنند؛ دیگر نمیتوان PR بدون بیلد ادغام کرد.
- سیکرتهای امضا فقط بهصورت GitHub Actions Secrets وارد CI میشوند؛ `*.keystore` در `.gitignore` است. بدون سیکرت، APK ریلیس امضاشده معرفی **نمیشود**.
- لاگ کامپایلر/تست هنگام شکست بهعنوان artifact نگهداری میشود؛ `SHA256SUMS.txt` همراه APK/AAB منتشر میگردد.

## Supported versions
| نسخه | وضعیت |
|---|---|
| `2.0.0` / versionCode 3 (فعلی) | پشتیبانی و رفع اشکال امنیتی |
| `< 2.0.0` | بدون پشتیبانی (هرگز منتشر نشد) |

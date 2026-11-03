# ✅ چکلیست انتشار (Go / No-Go) — SaliBussiness

**قاعده طلایی:** بیلد سبز «لازم» است، نه «کافی». **هر بند `P0` که تیک نخورده باشد = No-Go**، حتی اگر APK ساخته شده باشد.
نسخه چکلیست: ۱٫۱ · ۲۰۲۶-۱۱-۰۳ · مرتبط با [`WORLD_CLASS_ROADMAP.md`](WORLD_CLASS_ROADMAP.md) · [`CHANGELOG.md`](../CHANGELOG.md)

> ## 🔴 تصمیم فعلی: **No-Go برای انتشار عمومی / Play**
> فاز ۰ و بخش بزرگی از فاز ۱–۴ اجرا شده (بیلد سبز، تست واحد، idempotency، lease، migration،
> API 36، بکاپ/شبکه، R8، PRIVACY/SECURITY، AAB در CI)، ولی بندهای P0 زیر همچنان بازاند:
> Golden Set واقعی، وبحوک bounce/complaint، «حذف همه دادهها» داخل اپ، تأیید SPF/DKIM/DMARC،
> Play App Signing، انتشار مرحلهای و تست دستگاه Android 16. **استفاده داخلی کنترلشده با همین APK مجاز است.**

---

## ۱. صحت و خطوط قرمز (P0)
- [ ] تست Golden Set واقعی: ۱۰۰ ایمیل نمونه بینامشده، **۰ عدد/ادعای جعلی** — فعلاً Golden Set **مصنوعی** (`GoldenSetTest`)؛ نمونه real در مخزن قرار نمیگیرد
- [x] تست واحد «فقط `APPROVED/QUEUED` قابل ارسال است» (`MessageStateTest`) و باطلشدن approval هنگام ویرایش (`demote`)
- [ ] تست **انتهایهانتهای** «۰ ایمیل بدون تأیید انسان» روی دستگاه/امولاتور
- [x] منطق idempotency (UUID پایدار، `duplicate_parameter` = موفقیت، `UNCERTAIN` بدون retry) — تست واحد `MessageStateTest`
- [ ] تست شبیهسازی شبکه قابل اطمینان (timeout → دقیقاً ۰ ارسال تکراری) با FakeBrevo/MockWebServer
- [x] فقط ۲ فایل trial دوصفحهای (`_fa` + انگلیسی)؛ `%PDF`، `pages==2` و نامها روی بایتهای واقعی (`AttachmentGuard`)
- [x] حجم هر پیوست ≤ ۴MB و مجموع ≤ ۲۰MB (محدودیت مستند Brevo)
- [x] `QualityGate` بعد از ویرایش دستی هم اجرا میشود؛ دکمه تأیید تا عبور گیت غیرفعال است
- [x] خط opt-out و `Re:/Fwd:` جعلی/فوریت ساختگی توسط `QualityGate` مسدودند (تست واحد)
- [x] فهرست عدم ارسال پیش از هر ارسال و در صف بررسی میشود (`InboxScreen` + `SendEngine`)
- [x] هیچ ادعای «تضمین رسیدن به اینباکس»، مهلت ساختگی، تخفیف یا تهدید در پرامپت/متن نمونه نیست

## ۲. پایداری (P0)
- [x] پیشنویس کمپین (دامنهها/مخاطبان/انتخاب/زمینه) در Room `campaigns` ذخیره میشود؛ پیامهای تأییدشده از قبل در Room بودند
- [x] `requeueStuck(now)` پیامهای `SENDING` با lease منقضی را به `QUEUED` برمیگرداند؛ claim اتمی
- [ ] تست دستی: کشتن اپ / چرخش صفحه / قطع شبکه در میانه کمپین → بازیابی دقیق بدون داده گمشده
- [x] سقف روزانه و فاصله ۶۰–۹۰ ثانیه (منطقه زمانی دستگاه)
- [ ] ۴۲۹/۵xx → backoff نمایی + circuit breaker؛ خطای ۴xx بلافاصله و با پیام قابل اقدام (۵xx اکنون `UNCERTAIN` است)
- [x] پیوستها بهصورت جریانی و با سقف سخت خوانده میشوند (`readBounded`)

## ۳. امنیت و حریم خصوصی (P0)
- [x] `allowBackup=false`؛ `sali.db` و prefs از بکاپ خارجاند (`dataExtractionRules` اضافی نیست ولی allowBackup=false کافی است)
- [x] `network_security_config` نصب شد؛ cleartext غیرفعال؛ `android:exported` بررسی شد
- [x] هیچ کلید API / ایمیل خام / متنی در لاگ، پیام خطا یا artifact نیست (`PrivacyRedactorTest` + ماسک لاگ/export)
- [x] [`PRIVACY.md`](../PRIVACY.md) (FA/EN) منتشر شد؛ موجودی داده و طرفهای سوم (hcnsec/You.com/Brevo/IMAP) درج شد
- [x] [`SECURITY.md`](../SECURITY.md) و مسیر گزارش خصوصی موجود است
- [ ] «حذف همه دادههای من» داخل اپ کار میکند؛ خطمشی نگهداری لاگ اعمال شده
- [ ] رضایت/رابطه تجاری بهازای هر مخاطب ثبت میشود؛ فرم Data Safety تکمیل شده
- [ ] SPF / DKIM / DMARC دامنه فرستنده تأیید و تست شدهاند

## ۴. کیفیت مهندسی (P0)
- [x] `./gradlew clean test lintDebug assembleDebug assembleRelease bundleRelease` در CI (روی push **و PR**)
- [x] تست واحد منطق دامنه: ContactsParser / DomainValidator / QualityGate / AttachmentPolicy / AttachmentGuard / MessageState / PrivacyRedactor / GoldenSet
- [ ] پوشش تست ≥ ۷۰٪ اندازهگیری شود؛ `detekt`/`ktlint` اضافه شود
- [ ] `libs.versions.toml`؛ `kapt` → KSP؛ `security-crypto` پایدار
- [x] `versionCode = 3` / `versionName = 2.0.0` و `CHANGELOG.md` همخواناند
- [x] `isMinifyEnabled=true` + `isShrinkResources=true` و قواعد R8 در بیلد release CI تأیید میشوند
- [x] Migration واقعی `MIGRATION_2_3` اضافه شد (fallback فقط برای نصبهای pre-v2 توسعهدهنده)
- [ ] `MigrationTestHelper` و SBOM / بررسی CVE
- [x] ابزار ساخت پینشده + `gradle/wrapper/gradle-wrapper.properties` در ریپو؛ Dependabot major ابزار ساخت را تکبهتک merge نمیکند

## ۵. توزیع (P0)
- [x] `targetSdk=36` / `compileSdk=36` / `buildTools 36.0.0` و نصب صریح SDK 36 در CI
- [ ] رفتارهای **Android 16** روی دستگاه واقعی تست شدند (edge-to-edge، محدودیت FGS `dataSync`، 16KB page size)
- [x] خروجی **AAB** در CI ساخته و همراه APK + `SHA256SUMS.txt` آپلود میشود
- [ ] **Play App Signing** و امضای واقعی release (چهار سیکرت `SALI_KEYSTORE_*` هنوز تنظیم نشدهاند)
- [x] SHA256 و یادداشت انتشار در GitHub Release `latest` (روی `main`)
- [ ] فهرست فروشگاه (FA/EN)، آیکون، اسکرینشات، Data Safety، دستهبندی
- [ ] تست داخلی → انتشار مرحلهای ۱۰٪ → ۵۰٪ → ۱۰۰٪؛ **rollback آزمایش شد**
- [ ] crash-free ≥ ۹۹٫۵٪ و ANR < ۰٫۵٪ در تست داخلی
- [x] نصب مستقیم از GitHub Release همچنان کار میکند

## ۶. عملیات و مشاهدهپذیری (P1)
- [ ] لاگ ساختاریافته: `errorCode`, `providerMessageId`, `attempt`, `durationMs`, `costTokens`
- [ ] داشبورد سلامت + هشدار اتمام اعتبار hcnsec/You.com + سقف هزینه
- [ ] Crash/ANR reporting **با رضایت و بدون PII**
- [x] Kill switch ارسال (مکث / لغو صف) و توقف کامل سرویس در دسترس است
- [ ] Runbook حوادث: ارسال تکراری / کلید منقضی / سقف Brevo / بلاک دامنه
- [ ] وبحوک bounce/complaint (یا پویش صندوق) به suppression خودکار وصل است

## ۷. تجربه و اثربخشی (P1)
- [x] داشبورد کاری + **یک دکمه ارسال گروهی** با دیالوگ تأیید؛ کنسول صف با وضعیت/مکث/لغو/لاگ زنده
- [ ] سناریوی «اولین کمپین توسط فرد غیرفنی در < ۱۰ دقیقه» تست شد
- [x] تم Material 3 روشن/تیره، RTL ثابت، `contentDescription` و رشتههای صفحات اصلی در `strings.xml`
- [ ] تمام ۹ صفحه به `strings.xml` منتقل شوند؛ زبان EN کامل؛ TalkBack/کنتراست/۴۸dp تست شود
- [ ] ۲۰۰ دامنه: جستجو، فیلتر، Undo تأیید، حالت خالی/خطا/آفلاین
- [ ] هر پیام `experimentId` دارد؛ نرخ پاسخ/تبدیل به تفکیک سبک و subject قابل گزارش است
- [ ] هزینه هر ایمیل و مصرف اعتبار در اپ نمایش داده میشود

---

## امضا
| نقش | نام | تاریخ | تصمیم |
|---|---|---|---|
| صاحب محصول | | | Go / No-Go |
| مهندسی | | | Go / No-Go |
| امنیت / انطباق | | | Go / No-Go |

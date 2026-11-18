# ✅ چکلیست انتشار (Go / No-Go) — SaliBussiness

**قاعده طلایی:** بیلد سبز «لازم» است، نه «کافی». **هر بند `P0` که تیک نخورده باشد = No-Go**، حتی اگر APK ساخته شده باشد.
نسخه چکلیست: ۱٫۲ · ۲۰۲۶-۱۱-۱۸ · مرتبط با [`WORLD_CLASS_ROADMAP.md`](WORLD_CLASS_ROADMAP.md) · [`CHANGELOG.md`](../CHANGELOG.md)

> ## 🔴 تصمیم فعلی: **No-Go برای انتشار عمومی / Play**
> Candidate نهایی سخت‌سازی `2.0.3` / versionCode 6 (این PR هنوز CI نشده است)؛ فاز ۰ و بخش بزرگی از فاز ۱–۴ اجرا شده (بیلد سبز، تست واحد، idempotency، lease، migration،
> API 36، بکاپ/شبکه، R8، PRIVACY/SECURITY، AAB در CI)، ولی بندهای P0 زیر همچنان بازاند:
> Golden Set واقعی، وبحوک bounce/complaint، تأیید SPF/DKIM/DMARC، رضایت مستند بهازای هر مخاطب،
> Play App Signing، انتشار مرحلهای و تست دستگاه Android 16. «حذف همه دادهها» داخل اپ اکنون پیادهسازی شده است.
> **استفاده داخلی کنترلشده با APK امضاشده CI مجاز است؛ اما Release نسخه‌ای `v2.0.2` در GitHub فعلاً هیچ asset ندارد. Release تاریخی `latest` هنوز فایل debug قدیمی دارد و منبع نسخه فعلی محسوب نمی‌شود.**


---

## ۱. صحت و خطوط قرمز (P0)
- [ ] تست Golden Set واقعی: ۱۰۰ ایمیل نمونه بینامشده، **۰ عدد/ادعای جعلی** — فعلاً Golden Set **مصنوعی** (`GoldenSetTest`)؛ نمونه real در مخزن قرار نمیگیرد
- [x] تست واحد «فقط `APPROVED/QUEUED` قابل ارسال است» (`MessageStateTest`) و باطلشدن approval هنگام ویرایش (`demote`)
- [ ] تست **انتهایهانتهای** «۰ ایمیل بدون تأیید انسان» روی دستگاه/امولاتور
- [x] منطق idempotency در Brevo (UUID پایدار، پاسخ دقیق `duplicate_parameter`، پنجرهٔ TTL محدود)؛ در SMTP، `Message-ID` فقط برای threading است و deduplication را تضمین نمی‌کند؛ نتیجهٔ مبهم بدون retry به `UNCERTAIN` می‌رود.
- [ ] تست شبیهسازی شبکه قابل اطمینان (timeout → دقیقاً ۰ ارسال تکراری) با FakeBrevo/MockWebServer
- [ ] تست یکپارچه لغو صف و تغییر approval هم‌زمان با آماده‌سازی پیوست و در مرز provider call
- [x] فقط ۲ فایل trial دوصفحهای (`_fa` + انگلیسی)؛ `%PDF`، `pages==2` و نامها روی بایتهای واقعی (`AttachmentGuard`)
- [x] تازگی Evidence Pack در لحظه ارسال دوباره محاسبه و با هش زمان تأیید مقایسه می‌شود؛ هویت و ترتیب پیوست‌ها نیز دوباره تطبیق داده می‌شوند (`EvidenceApprovalGuard` + تست واحد)
- [x] ایمیل تست به صندوق فرستنده در صورت داشتن پیوست، همان سیاست Trial دوصفحه‌ای و محدودیت اندازه را اجرا می‌کند
- [x] حجم هر پیوست ≤ ۴MB و مجموع ≤ ۲۰MB (محدودیت مستند Brevo)
- [x] `QualityGate` بعد از ویرایش دستی هم اجرا میشود؛ دکمه تأیید تا عبور گیت غیرفعال است
- [ ] تست prompt-injection: دستورهای جاسازی‌شده در PDF، sidecar، You.com، قالب مرجع و متن پاسخ مشتری باید صرفاً داده تلقی و نادیده گرفته شوند
- [x] خط opt-out و `Re:/Fwd:` جعلی/فوریت ساختگی توسط `QualityGate` مسدودند (تست واحد)
- [x] فهرست عدم ارسال در صف و دوباره بلافاصله پیش از درخواست provider بررسی می‌شود (`SendEngine` + `SendPreflightGuard`)
- [x] تست واحد snapshot/preflight: تغییر متن، موضوع، شواهد یا پیوست در آخرین gate ارسال باید fail-closed شود (`MessageStateTest`).
- [ ] تست end-to-end روی دستگاه/امولاتور برای race-condition واقعی بین ویرایش/opt-out/cancel و آغاز provider call
- [x] هیچ ادعای «تضمین رسیدن به اینباکس»، مهلت ساختگی، تخفیف یا تهدید در پرامپت/متن نمونه نیست

## ۲. پایداری (P0)
- [x] پیشنویس کمپین (دامنهها/مخاطبان/انتخاب/زمینه) در Room `campaigns` ذخیره میشود؛ پیامهای تأییدشده از قبل در Room بودند
- [x] lease منقضی همیشه به `UNCERTAIN` بازیابی میشود (`markStuckUncertain`)؛ claim اتمی؛ ارسال خودکار مجدد وجود ندارد
- [ ] تست دستی: کشتن اپ / چرخش صفحه / قطع شبکه در میانه کمپین → بازیابی دقیق بدون داده گمشده
- [x] سقف روزانه و فاصله ۶۰–۹۰ ثانیه (منطقه زمانی دستگاه)
- [ ] ۴۲۹/۵xx → backoff نمایی + circuit breaker؛ خطای ۴xx بلافاصله و با پیام قابل اقدام (۵xx اکنون `UNCERTAIN` است)
- [x] پیوستها بهصورت جریانی و با سقف سخت خوانده میشوند (`readBounded`)

## ۳. امنیت و حریم خصوصی (P0)
- [x] `allowBackup=false` **و** `@xml/data_extraction_rules` (Android 12+ cloud backup / device transfer هر دو `exclude domain="root"`)
- [x] `network_security_config` نصب شد؛ cleartext غیرفعال؛ `android:exported` بررسی شد
- [x] هیچ کلید API / ایمیل خام / متنی در لاگ، پیام خطا یا artifact نیست (`PrivacyRedactorTest` + ماسک لاگ/export)
- [x] [`PRIVACY.md`](../PRIVACY.md) (FA/EN) منتشر شد؛ موجودی داده و طرفهای سوم (hcnsec/You.com/Brevo/IMAP) درج شد
- [x] [`SECURITY.md`](../SECURITY.md) و مسیر گزارش خصوصی موجود است
- [x] «پاکسازی کامل دادههای اپ» داخل تنظیمات با دیالوگ تأیید: صف ارسال ابتدا لغو می‌شود؛ `messages` / `logs` / `campaigns` / `studio_drafts` / `company_cache` / `pdf_cache`، prefs رمزنگاریشده و session پاک می‌شوند و دسترسی پایدار SAF آزاد می‌شود (`App.clearAllData`)
- [x] ذخیره متن PDF در cache محلی `pdf_cache` در خط‌مشی حریم خصوصی افشا شده است؛ Room به‌صورت مستقل با SQLCipher رمز نشده است
- [x] نگهداری لاگ عملیاتی روی دستگاه حداکثر ۹۰ روز است؛ هنگام شروع اپ، رکوردهای قدیمی‌تر به‌صورت خودکار حذف می‌شوند (`LogRetentionPolicy`, `LogDao.deleteBefore`)؛ فایل‌های exportشده‌ای که کاربر خارج از اپ ذخیره کرده باید جداگانه حذف شوند
- [ ] رضایت/رابطه تجاری بهازای هر مخاطب ثبت میشود؛ فرم Data Safety تکمیل شده
- [ ] SPF / DKIM / DMARC دامنه فرستنده تأیید و تست شدهاند

## ۴. کیفیت مهندسی (P0)
- [x] `./gradlew clean test lintDebug assembleDebug assembleRelease bundleRelease` در CI (روی push **و PR**)
- [x] تست واحد منطق دامنه: ContactsParser / DomainValidator / QualityGate / AttachmentPolicy / AttachmentGuard / MessageState / PrivacyRedactor / GoldenSet
- [ ] پوشش تست ≥ ۷۰٪ اندازهگیری شود؛ `detekt`/`ktlint` اضافه شود
- [ ] `libs.versions.toml`؛ `kapt` → KSP؛ `security-crypto` پایدار
- [ ] `versionCode = 6` / `versionName = 2.0.3` و `CHANGELOG.md` همخوان هستند؛ پس از CI سبز تأیید شود
- [x] `isMinifyEnabled=true` + `isShrinkResources=true` و قواعد R8 در بیلد release CI تأیید میشوند
- [x] Migration واقعی `MIGRATION_2_3` اضافه شد (fallback فقط برای نصبهای pre-v2 توسعهدهنده)
- [ ] `MigrationTestHelper` و SBOM / بررسی CVE
- [x] ابزار ساخت پینشده + `gradle/wrapper/gradle-wrapper.properties` در ریپو؛ Dependabot major ابزار ساخت را تکبهتک merge نمیکند

## ۵. توزیع (P0)
- [x] `targetSdk=36` / `compileSdk=36` / `buildTools 36.0.0` و نصب صریح SDK 36 در CI
- [ ] رفتارهای **Android 16** روی دستگاه واقعی تست شدند (edge-to-edge، محدودیت FGS `dataSync`، 16KB page size)
- [x] خروجی **AAB** در CI ساخته و همراه APK + `SHA256SUMS.txt` آپلود میشود
- [x] چهار Secret امضای تولیدی `SALI_KEYSTORE_*` تنظیم شده‌اند؛ CI run #100 هر چهار ورودی را تشخیص داد، APK را با `apksigner verify` تأیید کرد و AAB را ساخت.
- [ ] **Play App Signing** در Play Console و تطبیق کلید آپلود با گواهی مورد انتظار تأیید شود.
- [x] `SHA256SUMS.txt` برای خروجی Release تولید و در Artifactهای CI آپلود شد.
- [ ] انتشار نسخه‌ای بعدی با تگ منطبق (پس از افزایش نسخه/کد) و APK/AAB امضاشده در GitHub Release نهایی شود.
> وضعیت موجود: `v2.0.2` به commit پیش از اصلاحات Release اشاره می‌کند و Release آن asset خالی است؛ تگ و Release را به‌زور جابه‌جا نکن. `latest` نیز صرفاً Release تاریخی با debug APK قدیمی است.
- [ ] فهرست فروشگاه (FA/EN)، آیکون، اسکرینشات، Data Safety، دستهبندی
- [ ] تست داخلی → انتشار مرحلهای ۱۰٪ → ۵۰٪ → ۱۰۰٪؛ **rollback آزمایش شد**
- [ ] crash-free ≥ ۹۹٫۵٪ و ANR < ۰٫۵٪ در تست داخلی
- [ ] نصب مستقیم APK از یک GitHub Release نسخه‌ای پس از انتشار و تأیید واقعی آن دوباره بررسی شود

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

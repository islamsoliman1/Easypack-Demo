# EasyPack Demo

نسخة مستقلة للعرض، تشمل React + Spring Boot + PostgreSQL. يفتح الزائر الرابط مباشرة بدون إدخال بريد أو كلمة مرور؛ الباك إند ينشئ جلسة زائر تلقائيًا وتظل قواعد العمليات والصلاحيات مطبقة.

عند أول تشغيل تُنشأ بيانات وهمية: 3 موديلات، 7 ماكينات وطلبات تصنيع بحالات مختلفة، قوائم مكونات منشورة، مخزون وخامات، مهام تجميع، بلاغ عيب وتجارب تشغيل. التعديلات تُحفظ فعليًا في PostgreSQL وتظهر لكل زوار الديمو. لا تُعاد البيانات تلقائيًا عند إعادة التشغيل.

يمكن تجربة عمليات المصنع، مع عرض المستخدمين والأدوار فقط. تعديل الحسابات والصلاحيات وكلمات المرور ورفع المرفقات غير متاح للزائر.

## GitHub ثم Railway

1. ارفع **محتويات هذا الفولدر فقط** إلى ريبو GitHub جديد. يجب أن يكون `railway.json` و`Dockerfile.Railway` في جذر الريبو. لا ترفع `.env` أو مجلد `output`؛ كلاهما مستبعد من Git.
2. في Railway أنشئ مشروعًا جديدًا للديمو، وأضف قاعدة PostgreSQL **جديدة وفارغة**. اختُبرت النسخة على PostgreSQL 17.
3. أضف خدمة من **GitHub Repo** واختر الريبو. اجعل Root Directory هو `/`. يحدد `railway.json` ملف Docker ومسار فحص الجاهزية؛ لا تحتاج Build Command أو Start Command منفصلين.
4. أضف المتغيرات التالية إلى **خدمة التطبيق**. يفترض المثال أن اسم خدمة قاعدة البيانات هو `Postgres`؛ غيّره في المراجع إذا اختلف الاسم.

| المتغير | القيمة |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `railway,demo` |
| `DB_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
| `DB_USER` | `${{Postgres.PGUSER}}` |
| `DB_PASSWORD` | `${{Postgres.PGPASSWORD}}` |
| `JWT_SECRET` | قيمة عشوائية جديدة، 64 بايت على الأقل؛ طريقة توليدها أدناه |
| `ALLOWED_ORIGINS` | `https://${{RAILWAY_PUBLIC_DOMAIN}}` بعد إنشاء الدومين، أو رابط الديمو HTTPS كاملًا بدون `/` في آخره |

5. من Settings → Networking أنشئ **Generate Domain** لخدمة التطبيق، ثم Deploy. التطبيق يقرأ `PORT` الذي توفره Railway. إذا ظهر طلب تحديد target port استخدم `8080`، أو قيمة `PORT` إن خصصتها يدويًا.
6. افتح الرابط: تظهر لوحة متابعة المصنع مباشرة. يجب أن يعيد `/actuator/health/readiness` الحالة `UP`.

لا تحتاج `BOOTSTRAP_EMAIL` أو `BOOTSTRAP_PASSWORD`. اترك `EASYBACK_SECURE_COOKIES` غير محدد في Railway؛ إعداد Railway يستخدم Cookies آمنة على HTTPS. `compose.yaml` مخصص للتشغيل المحلي، وليس ملف نشر Railway.

لإخراج نسخة سورس نظيفة للرفع اليدوي، شغّل `./scripts/Export-GitHub.ps1`. فك ملف ZIP الناتج وارفع محتوياته إلى جذر الريبو؛ لا ترفع ملف ZIP نفسه. الأرشيف لا يحتوي `.env` أو الاعتمادات أو ملفات البناء.

توليد قيمة `JWT_SECRET` على جهازك (انسخ الناتج إلى متغير Railway فقط):

```powershell
node -e "console.log(require('node:crypto').randomBytes(64).toString('base64'))"
```

الفرونت إند والـ API يعملان من نفس الدومين، فلا يلزم متغير API URL للواجهة. قاعدة البيانات تستخدم شبكة Railway الداخلية. النسخة ترفض تهيئة الديمو فوق قاعدة بها مستخدمون سابقون.

مراجع Railway الرسمية: [نشر الخدمات من GitHub وDocker](https://docs.railway.com/guides/docker-compose)، [متغيرات PostgreSQL](https://docs.railway.com/databases/postgresql).

## التشغيل المحلي

من هذا الفولدر، باستخدام PowerShell 7 وDocker Desktop:

```powershell
./scripts/Initialize-Demo.ps1
docker compose up -d --build
```

افتح [الديمو المحلي](http://localhost:18090). الإعدادات المحلية تُولد في `.env`، وقاعدة البيانات محفوظة في volume مستقل اسمه يبدأ بـ `easypack-public-demo`.

لإيقاف الخدمات مع الاحتفاظ بالبيانات:

```powershell
docker compose down
```

للبناء على Linux أو macOS، أضف ملف `.env` محليًا به `DB_PASSWORD` و`JWT_SECRET` بقيم عشوائية و`DEMO_PORT=18090`، ثم شغّل الأمر نفسه. لا ترفع الملف إلى GitHub.

## نطاق النسخة

- السورس مستقل؛ لا يستورد ملفات أو متغيرات أو قاعدة بيانات من النسخة الأصلية.
- وضع `demo` وحده يتيح دخول الزائر ويجهز البيانات. تشغيل الصورة بإعداداتها الافتراضية يفعّل `railway,demo`.
- لا توجد إعادة ضبط عامة للبيانات. للحصول على عرض جديد تمامًا، استخدم قاعدة PostgreSQL جديدة فارغة.
- الحفظ والتجميع والصرف وتجارب التشغيل تعمل عبر الباك إند الحقيقي، وليست محاكاة في المتصفح.
- هذه نسخة عرض عامة ببيانات مشتركة؛ لا تدخل فيها بيانات شركة أو بيانات شخصية حقيقية.

## التحقق محليًا

نتائج الاختبارات المنفذة وحدودها في [VERIFICATION.md](VERIFICATION.md).

بعد تشغيل الديمو، شغّل `node scripts/Verify-Demo.mjs` لفحص جلسة الزائر والبيانات التجريبية والحفظ الحقيقي والتقارير وتجديد الجلسة. الفحص ينشئ موديلًا تجريبيًا ويؤرشفه، ويقبل localhost فقط.

اختبارات Java: `mvn -f backendjava/pom.xml test`، أو `docker build --target verify -f Dockerfile.Railway .`. بناء الواجهة: من `frontend` شغّل `npm ci` ثم `npm run build`.

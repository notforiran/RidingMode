# RidingMode V24 Silent Listening Audit

## هدف اصلاح
این نسخه برای مشکل محیط پر سر و صدا ساخته شد: بوق/صدای شروع recognition، قطع شدن موزیک هنگام گوش دادن، قطع شدن موزیک هنگام حرف زدن اپ، و اجرا شدن فرمان‌های اشتباه در اثر نویز.

## اصلاح‌های اصلی
- مسیر میکروفون همچنان یکپارچه ماند: فقط `RidingForegroundService` مالک `SpeechRecognizer` است.
- برای شروع recognition، mute موقت روی `STREAM_SYSTEM` و `STREAM_NOTIFICATION` اعمال شد تا بوق/آلارم سیستم recognition تا حد ممکن شنیده نشود؛ `STREAM_MUSIC` دست‌کاری نمی‌شود.
- چند extra vendor-specific برای کاهش beep به intent اضافه شد: `SUPPRESS_BEEP`, `NO_BEEP`, `DICTATION_MODE`.
- fallback اجرای متن ناشناخته حذف شد؛ در محیط موتور/ترافیک فقط عبارت‌هایی که شبیه command واقعی هستند اجرا می‌شوند.
- restart بعد از `NO_MATCH` و `SPEECH_TIMEOUT` از 450ms به 1700ms افزایش یافت تا نویز موتور باعث چرخه سریع beep/restart نشود.
- برای TTS دیگر از `requestAudioFocus` استفاده نمی‌شود؛ به جای آن volume موزیک دستی کم می‌شود و بعد از پایان گفتار برمی‌گردد. این کار احتمال pause شدن پلیرها را کم می‌کند.
- اگر recognition باعث pause شدن موزیک شود و قبل از listening موزیک در حال پخش بوده باشد، اپ تلاش می‌کند playback را دوباره resume کند.
- `ride off` و misrecognitionهای نزدیک مثل `right of`, `rider of`, `write of`, `bike off` بهتر normalize می‌شوند.
- dead codeهای کوچک V23 در MainActivity تمیز شد: empty if، شرط تودرتوی خراب permission، و duplicate drawer toggle.

## نسخه
- versionCode: 24
- versionName: 1.0.24-v24-silent-listening
- GitHub artifact: RidingMode-V24-debug-apk

## نکته فنی مهم
Android `SpeechRecognizer` توسط سرویس recognition سیستم/Google اجرا می‌شود و روی بعضی رام‌ها ممکن است خودش audio focus یا beep داخلی داشته باشد. V24 بهترین تلاش ممکن را برای silent listening انجام می‌دهد بدون اینکه موزیک را عمداً pause کند. اگر روی یک گوشی خاص همچنان beep باقی بماند، راه قطعی‌تر نیازمند موتور wake-word/offline recognition اختصاصی به جای SpeechRecognizer سیستم است.

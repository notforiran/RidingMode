# RidingMode V9 - GitHub Actions APK Build

این نسخه برای ساخت APK واقعی از طریق GitHub Actions آماده شده است.

## خروجی مورد انتظار

وقتی پروژه را در GitHub push کنید یا workflow را دستی اجرا کنید، فایل زیر ساخته می‌شود:

```text
app/build/outputs/apk/debug/app-debug.apk
```

این APK توسط Android Gradle Plugin به‌صورت debug امضا می‌شود و در بخش **Artifacts** با نام زیر قابل دانلود است:

```text
RidingMode-V9-debug-apk
```

## روش اجرا در GitHub

1. یک repository جدید در GitHub بسازید.
2. محتویات این ZIP را داخل repository قرار دهید.
3. Commit و push کنید.
4. به تب **Actions** بروید.
5. workflow با نام **Build RidingMode APK** را اجرا کنید.
6. بعد از موفق شدن job، پایین صفحه اجرای workflow از بخش **Artifacts** فایل `RidingMode-V9-debug-apk` را دانلود کنید.

## ساخت دستی روی سیستم دارای Android SDK

```bash
gradle --no-daemon --stacktrace :app:assembleDebug
```

## نسخه پروژه

```text
applicationId: com.ridingmode.app
versionCode: 9
versionName: 1.0.9-v9-audited
compileSdk: 35
targetSdk: 35
minSdk: 26
```

## نکته نصب

قبل از نصب APK جدید، نسخه‌های قبلی با همین package name را uninstall کنید:

```text
com.ridingmode.app
```

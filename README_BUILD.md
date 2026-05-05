# RidingMode V9 audited source

This project is prepared as a standard Android application module.

Main package: `com.ridingmode.app`
Version: `1.0.9-v9-audited` / `versionCode 9`

## Local build

A local build requires Android SDK, Android build tools and Gradle. If Gradle is installed:

```bash
gradle --no-daemon --stacktrace :app:assembleDebug
```

The APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The Gradle Wrapper JAR is not bundled in this package. Android Studio or the GitHub Actions workflow can provide the build environment.

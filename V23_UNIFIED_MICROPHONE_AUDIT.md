# RidingMode V23 Unified Microphone Audit

## Goal
Unify microphone ownership and remove stale/dead voice-recognition code paths that could compete with the foreground service recognizer.

## Changes
- Removed all `MainActivity -> RidingForegroundService.setActivityVoiceActive(false)` compatibility calls.
- Removed the stale `activityVoiceActive` flag and compatibility hook from `RidingForegroundService`.
- Removed unused `ACTION_VOICE_COMMAND` and `ACTION_READ_NOTIFICATION` intent paths.
- Kept notification delivery on the direct in-process `deliverNotificationFromListener(...)` path.
- Removed Bluetooth SCO helper code and Bluetooth runtime permission request to avoid audio-route instability.
- Added the missing `java.util.ArrayList` import in `MainActivity`.
- Kept a single SpeechRecognizer owner: `RidingForegroundService`.
- Kept watchdog restart loop and recognizer recreation on busy/client errors.
- Updated versionCode to 23 and versionName to `1.0.23-v23-unified-microphone`.
- Updated GitHub Actions artifact to `RidingMode-V23-debug-apk`.

## Voice lifecycle
`MainActivity` only requests permissions and starts/stops `RidingForegroundService`. The service creates, starts, cancels, restarts, and destroys the only `SpeechRecognizer` instance in the app.

## Remaining runtime dependencies
Actual recognition still depends on Android/Google Speech Services, microphone permission, foreground service permission behavior, and battery optimization settings on the test device.

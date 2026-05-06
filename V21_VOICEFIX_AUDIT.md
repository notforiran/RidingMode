# RidingMode V21 Voicefix Audit

Main issue found in V20: voice recognition was split between Activity and ForegroundService. The Activity recognizer could fail silently while `activityVoiceActive` stayed true, which disabled the service recognizer. Result: Ride mode appeared active but did not react to spoken commands.

V21 fixes:
- Disabled Activity-level recognizer as the primary listener.
- Kept foreground service recognizer as the single voice source of truth.
- Made `setActivityVoiceActive()` compatibility-safe so it can no longer suppress service listening.
- Removed Activity pause/resume voice suppression.
- Added longer silence windows for command recognition.
- Added common misheard variants for ride/music commands.
- Bumped versionCode to 21 and versionName to `1.0.21-v21-voicefix`.

Recommended phone-side checks after install:
1. Uninstall the old app completely.
2. Install V21 APK.
3. Open app and grant Microphone permission.
4. Keep Google Speech Services / speech recognition enabled on the phone.
5. Press Engine ON and test: `play music`, `ride off`, `volume up`, `call ali`.
- Added a service voice watchdog that re-starts listening every 3.5 seconds if the recognizer silently stops.

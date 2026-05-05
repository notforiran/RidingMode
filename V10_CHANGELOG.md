# RidingMode V10 changelog

Fixed based on real APK testing feedback:

1. `ride off` reliability
   - Added common speech-recognition variants: `right off`, `ride of`, `riding off`, `motor off`, `engine off`, `stop riding`, etc.
   - Stop is now delayed until the TTS goodbye/off message finishes, instead of stopping the service immediately.

2. Notification reading while music plays
   - Music/media notifications are filtered by notification category, media session extras, ongoing status, and common media package names.
   - Repeated notification text is not read again for 2 minutes.
   - TTS now requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` and does not request full pause focus.
   - Speech recognizer is paused while TTS speaks, preventing the app from hearing itself.

3. Contact matching
   - Replaced raw `LIKE %name%` first-result behavior with scored matching.
   - Exact contact name wins over longer names such as `Amir Ali ...` when the command is `call ali`.
   - Ambiguous results now ask the user to choose: first/second/find more/cancel.
   - Added `cancel` and `find more` commands.

4. Source cleanup
   - Version bumped to `versionCode 10` / `1.0.10-v10-command-fixes`.
   - GitHub Actions artifact renamed to `RidingMode-V10-debug-apk`.
   - Workflow no longer uses the fragile `yes | sdkmanager` pipe.

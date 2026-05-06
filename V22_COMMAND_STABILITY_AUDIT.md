# RidingMode V22 Command Stability Audit

## Fixed
- Removed Activity-level SpeechRecognizer path entirely; foreground service is the only recognizer.
- Disabled automatic Bluetooth SCO routing because it can make music cut in/out on many phones.
- Removed spoken TTS confirmations from music and volume commands to prevent music duck/pause and self-recognition loops.
- Added command debounce for repeated media commands.
- Added self-echo filtering for phrases such as "playing music", "music paused", "next track", and "previous track".
- Delayed first listening window after Ride mode starts so engine sound is not recognized as a command.
- Expanded common speech-recognition variants for play/next/previous/volume/notification commands.

## Version
- versionCode: 22
- versionName: 1.0.22-v22-command-stability
- GitHub artifact: RidingMode-V22-debug-apk

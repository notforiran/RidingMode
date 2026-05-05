# Riding Mode V12 – Design Locked Source

## Approved design lock
- Main screen is locked to the final approved screenshot-style design.
- `main_screen_on.png` is used for Engine ON.
- `main_screen_off.png` is generated from the approved design with the neon accents switched to red and the engine text changed to OFF.
- The app runs fullscreen so the approved screen artwork is not visually duplicated by the system status bar.
- Transparent hotspots preserve real interactions over the approved visual design:
  - Engine center toggles Riding Mode.
  - Contacts opens the left drawer.
  - Commands opens the right drawer.

## Voice logic retained/upgraded
- V11 command upgrades remain in place: music, volume, contact selection, SIM selection, notification toggles, mute toggles, and in-call restrictions.
- Priority contacts are still stored in SharedPreferences and checked before phonebook results.
- During active calls, only `finish call`, `end call`, and `hang up` are processed.

## Build
- versionCode: 12
- versionName: 1.0.12-v12-design-locked
- GitHub Actions artifact name: RidingMode-V12-debug-apk

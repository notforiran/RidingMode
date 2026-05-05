# Riding Mode V11 – Neon UI + command/control upgrade

## UI
- Rebuilt the main screen to match the approved neon-style design direction.
- Engine button is now smaller with neon red/off and neon green/on states.
- Removed the old bottom commands box.
- Added a right-side sliding `Commands` drawer.
- Added a left-side sliding `Contacts` drawer for priority contacts.
- Added a more polished launcher icon.

## Voice + command handling
- Added command variants for `play song`, `next song`, `pre song`, `pre music`, `pre track`.
- Added volume commands: `volume up`, `volume down`, `volume max`, `increase to max volume`.
- Removed `back` as a music command trigger.
- Added multi-result selection phrases: `first one`, `second one`, plus `find more` and `cancel`.
- Improved contact ranking and prioritized app-defined contacts.
- Added SIM-selection flow: `sim 1`, `sim one`, `first sim`, `first`, `sim 2`, `second sim`, `second`, etc.
- During an active call, only `finish call`, `end call`, and `hang up` are honored.

## Notification + mute behavior
- Notification reading still ducks music instead of pausing it.
- Media notifications are filtered so the app should not read track names.
- Added notification-only mode toggles: `notif off`, `notification off`, `notif on`, `notification on`.
- Added emergency silent mode: `mute on`, `mute off`.
- In mute mode, music is paused and TTS output is suppressed.

## Engine sound note
- The startup sound is now hard-limited in code to about 1.8 seconds so it feels shorter.
- For the final double-throttle bike sound, replace `app/src/main/res/raw/engine_start.mp3` with a shorter custom asset.

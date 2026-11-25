# Jeep Tune

A small Android app (one screen, "Read Test") that copies the flash memory out of a 1998 Jeep JTEC engine
computer through a GearWrench/XTOOL Bluetooth OBD dongle (Chrysler SCI). It saves progress every 64 bytes and
can resume, then compares two full reads to give a verified copy.

**Read-only.** The app can only send line set-up, engine-speed and battery reads, and the flash-read request
(`26 HH MM LL`). An allow-list in code (`core/.../Frames.java`) throws on anything else, and tests check that
it refuses erase, reset, write-memory and actuator frames.

## While a read runs

- **Background read.** A foreground service (type `connectedDevice`) shows an ongoing notification
  ("Reading flash 42% — 110 / 256 KB · 12.6 V") with a **Stop** action (pause & save, resumable). Leaving the app,
  locking the screen or rotating does not stop the read. The service holds a partial wake lock, and the screen
  stays on while the app is open, only while a read runs. On Android 13+ the app asks for the notification permission.
- **Battery guard.** Battery is checked at least every 5 s and every 256 bytes (existing allowed frames only). Below 12.0 V
  the read pauses (progress saved, no flash requests sent, battery and engine speed still checked every 2 s) and carries on
  above 12.4 V. The screen and notification say "Paused — low battery". Engine speed above 0 still ends the read.
  Logic: `core/.../BatteryGuard.java`.
- **Export.** After "Read again & compare" gives a verified copy, **Export verified copy** opens the share sheet with
  the `.bin` and a `.sha256` text file (`sha256sum -c` line, part number placeholder, job id). Nothing is uploaded by the app.

## Dongle-busy signal for Jeep Dash

Jeep Tune owns the dongle while reading. It tells Jeep Dash (`com.strickland.jeepdash`) with an explicit broadcast:

- Action: `com.strickland.jeeptune.action.DONGLE_BUSY`, sent with `setPackage("com.strickland.jeepdash")`.
- Extra: boolean `busy`.
- `busy=true` when a read starts, then repeated every 30 s as a heartbeat while reading.
- `busy=false` when the read completes, fails or is stopped, and always from the service's `onDestroy`.
- Receiver side: register for the action (manifest receiver, exported) and treat the dongle as busy until `busy=false`
  or until about 90 s pass with no heartbeat (covers a killed Jeep Tune process).

## Get the app

Download `jeep-tune.apk` from the newest release: https://github.com/danielrstrickland-cell/jeep-tune/releases/latest
Every push to `main` builds and publishes a new release (`tune-<build>`).

## Build

GitHub Actions runs `gradle :core:runCoreTests :app:assembleDebug`. Core tests (no Android needed):
`gradle :core:runCoreTests`.

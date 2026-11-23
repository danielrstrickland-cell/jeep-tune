# Jeep Tune

A small Android app (one screen, "Read Test") that copies the flash memory out of a 1998 Jeep JTEC engine
computer through a GearWrench/XTOOL Bluetooth OBD dongle (Chrysler SCI). It saves progress every 64 bytes and
can resume, then compares two full reads to give a verified copy.

**Read-only.** The app can only send line set-up, engine-speed and battery reads, and the flash-read request
(`26 HH MM LL`). An allow-list in code (`core/.../Frames.java`) throws on anything else, and tests check that
it refuses erase, reset, write-memory and actuator frames.

## Get the app

Download `jeep-tune.apk` from the newest release: https://github.com/danielrstrickland-cell/jeep-tune/releases/latest
Every push to `main` builds and publishes a new release (`tune-<build>`).

## Build

GitHub Actions runs `gradle :core:runCoreTests :app:assembleDebug`. Core tests (no Android needed):
`gradle :core:runCoreTests`.

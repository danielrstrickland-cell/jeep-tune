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

## Tables (calibration viewer)

The **Tables** button on the main screen opens a **read-only** viewer for calibration tables in a `.bin` you already have
(a saved read, or any file via the file picker). It never talks to the engine computer, sends no dongle frames (the `Frames.java`
allow-list is untouched), and never writes or edits a `.bin`.

- Shows the file name, a part-number check against the definition (the part number is stored as BCD digits at flash 0x273F, also 0x2746; the viewer shows "Part 56041519 ✓" or what the file holds instead), and the SHA-256 (tap to copy).
- Chips: Spark A, Spark B, After-start spark, Fuel A, Fuel B, Charge temp retard, Coolant retard, Idle spark curves, Idle rpm, 3-4 shift.
  **Scalars** lists rev limit, speed constant and others with units (inline side panel, collapsible; so is **Changes**). A `?` means the definition is not confirmed yet.
- Views: **Grid** heat map (rpm rows, MAP columns, tap a cell for address, raw and scaled value; pinch to zoom, swipe to change table),
  **3D** surface and **Slice** (slider picks an rpm row, value vs MAP). Curves show as a line chart.
  Tables whose axes are not known yet show index numbers 0..n.
- **Compare with...** (default pick: the first verified read): differing cells are outlined (warm = raised, cool = lowered),
  **Changes** lists cells and bytes, including bytes outside any known table. **Ghost stock** draws the compare file under the 3D surface.
- **Tune log...** loads a Jeep Dash tune-log CSV (header lines start with `#`; columns named rpm and MAP, any case). **Hit map** colors
  cells by samples spent there. The replay bar plays, pauses and scrubs the log (1x / 4x / 16x) with a dot and a fading trail on the grid and 3D.
- **Live dot:** while the viewer is open it listens for the broadcast `com.strickland.jeepdash.action.LIVE` from Jeep Dash
  (float `rpm`, float `map_kpa`, long `t`) and shows a dot at the current cell.

### Tables: help (gestures, pen, tilt, renderer)

**Renderer setting.** The header button **Renderer: Native 3D / Web** switches drawing and remembers the choice. *Native* is the default:
OpenGL ES 3.0 (2.0 if 3.0 is missing) for the 3D surface and Canvas for the grid and slice, with no WebView. *Web* is the older plotly.js
renderer, kept as a fallback (no tilt, no pen card, no Reset view there).

**Numbers.** rpm and counts are whole numbers ("5300 rpm"), kPa whole, degrees 1 decimal, ms 2 decimals.

**3D surface (touch).**
- One finger drag: orbit. Two fingers: twist to roll, pinch to zoom, drag to pan.
- Double-tap: reset the camera (also the **Reset view** button). Tap: select a cell.
- Swipe in from the left or right screen edge: previous / next table. The grid and slice views use the same edge swipe; the grid also pinches and pans.
- The surface is lit and smooth shaded in the amber ramp. **Ghost stock** draws the compare file as a translucent surface; changed cells
  are tinted warm (raised) or cool (lowered) and outlined; **Hit map** colors by log samples. The replay/live dot glows and leaves a ~3 s trail.
- It redraws only when something changes and runs at the display rate (90 fps on the tablet) only while a replay plays, a live dot shows, you touch, or tilt is on.

**Pen (Lenovo Tab Pen / Pen Plus).**
- Hover over the grid, slice or 3D surface: the cell is highlighted and a card shows table, rpm / MAP, value with units, raw, address,
  the log's sample count (if a log is loaded) and stock vs this file (if comparing).
- Barrel button (primary stylus button) or the media keys play/pause; next / previous step the replay forward / back 1 s.
- Pen pressure is read but not used yet (reserved for a future editor).

**Tilt to orbit.** The **Tilt** button orbits the 3D camera as you tilt the tablet, relative to the pose when you switched it on
(game rotation vector sensor; gyroscope if that is missing; "no sensor" if neither). It switches itself off and greys out while the Jeep is
moving: a Jeep Dash LIVE broadcast in the last 5 s, or GPS speed over 5 mph. GPS is used only if location permission is already
granted in Android settings (the app never asks for it).

Definition: `app/src/main/assets/defs/56041519.json` (part 56041519AE). All decoding is in `:core` (`CalImage`, `CalTable`, `ImageDiff`,
`TuneCsv`, `Replay`) with tests on a synthetic image; drawing sits behind `TableRenderer`: `GlRenderer` (native, default; maths for camera, mesh, picking, tilt and the motion gate is in `:core`) or `PlotlyRenderer` (plotly.js bundled in
`app/src/main/assets/viewer/`, no network needed).

## Get the app

Download `jeep-tune.apk` from the newest release: https://github.com/danielrstrickland-cell/jeep-tune/releases/latest
Every push to `main` builds and publishes a new release (`tune-<build>`).

## Build

GitHub Actions runs `gradle :core:runCoreTests :app:assembleDebug`. Core tests (no Android needed):
`gradle :core:runCoreTests`.

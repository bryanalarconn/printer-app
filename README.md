# DPP-450 Bluetooth Test Harness

A minimal Android app for validating Bluetooth Classic (SPP/RFCOMM) connectivity
and ZPL print correctness on the IPC Mobile DPP-450 mobile thermal printer.

This is a test tool, not a production app. It exists to answer two questions:

1. How reliable is a raw RFCOMM socket connection to the DPP-450, and which
   connection strategy (hold open vs reconnect per print) behaves better?
2. Which ZPL commands does the DPP-450's ZPL emulation actually support, and
   which ones misbehave, compared against a Zebra ZD421 baseline?

No vendor SDK is used. The app talks to the printer over a plain serial-style
Bluetooth socket using the standard SPP UUID (`00001101-0000-1000-8000-00805F9B34FB`).

## Sister app

This app has a macOS sibling, `mac_printer_tool` (Python/PyQt6), which drives
the same test content over USB and Bluetooth from a Mac, against both the
DPP-450 and the Zebra ZD421 reference printer. The two are deliberately kept
identical in behavior, terminology, and, critically, payload bytes, so that
any difference between two printouts is attributable to the printer and never
to the tool.

- **ZPL test content is shared**: `app/src/main/assets/code_sets/` is a synced
  copy of the canonical folder `mac_printer_tool/code_sets/`. Never edit the
  assets copy by hand. Edit the canonical folder, then run
  `python3 tools/sync_code_sets.py` (in mac_printer_tool) and rebuild this app.
- **Utility commands are shared verbatim** (see `PrinterCommands.kt` here and
  `printer_conn.py` there): Resume `~PS`, Cancel jobs `~JA`, the Cut paper
  label, and the settings-restore block (`^PW832`; the ZD421 clamps it to its
  831-dot max, so identical bytes are correct on both printers).
- **UI wording is shared** via `code_sets/meta.json` (descriptions, button
  labels, section labels) plus matching hardcoded labels for the controls.

## Requirements

- Android Studio (the project uses its bundled JDK; no separate Java install needed)
- A physical Android device (the emulator has no Bluetooth), minimum Android 8.0 (API 26)
- A DPP-450 printer already paired in the phone's system Bluetooth settings

## Building and running

Open the project folder in Android Studio, let Gradle sync, and press Run with
your device selected. That builds, installs, and launches in one step.

From the command line (macOS, no system Java installed):

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` (not committed) must point at your Android SDK, e.g.:

```
sdk.dir=/Users/<you>/Library/Android/sdk
```

## Using the app

1. Grant the Bluetooth permission on first launch (Android 12+ asks at runtime).
2. Tap the printer in the paired-device list. Nothing connects automatically.
3. The test screen offers:
   - **Keep connection open / Reconnect on every print** switch, the two
     connection strategies under test.
   - **Keepalive ping every 15s** switch. Sends an invisible CR-LF so the
     printer's power-save timer never drops an idle link.
   - **Auto-restore defaults after print** switch. ZPL settings commands
     persist on the printer until changed or power cycled; when this is on,
     every print carries a settings-reset block. Turn it off when deliberately
     testing settings persistence (code sets 10, 17, 21-23, 26, 28, 31-33, 37).
   - **Media / Print mode dropdowns** feeding the restore block, so a chosen
     mode survives prints instead of being reset by them.
   - **Utility buttons** (same five as the Mac tool): Check status (`~HS`,
     updates the flags line), Resume (`~PS`), Cancel jobs (`~JA`), Cut paper,
     Reset settings.
   - **Two tabs**: "Test code sets" (the 49 sets, one button per section) and
     "ZPL console" (sends exactly what you type, with history; no safety
     checks, no auto-restore).
4. **Pre-send safety guard**: every code-set print first queries `~HS` and
   refuses if the printer *confirms* paper out, paused, or formats already
   queued. This exists because stacked jobs once fed through an entire roll
   when a paused printer was resumed. If `~HS` can't be read at all, the guard
   warns and sends anyway rather than refusing forever - "unreadable" and
   "confirmed bad" are different things, and the DPP-450 over Bluetooth was
   observed on hardware not always answering `~HS`. Recovery buttons and the
   console bypass the guard entirely so they still work on a faulted printer.
5. The log panel records every TX, RX, connection drop, auto-reconnect
   attempt, and guard refusal with millisecond timestamps.

## Project layout

```
app/src/main/assets/code_sets/       Synced copy of the canonical shared ZPL
                                     test content. DO NOT EDIT HERE.
app/src/main/java/com/bryanalarcon/printertest/
  BluetoothPrinterManager.kt   Connection engine: socket, reader loop,
                               keepalive, auto-reconnect with backoff,
                               serialized writes, ~HS query/guard.
  CodeSetLoader.kt             Parses assets/code_sets (same rules as the
                               Mac tool's code_sets.py, line for line).
  PrinterCommands.kt           The shared utility ZPL commands and the
                               settings-restore block builder.
  MainActivity.kt              Jetpack Compose UI: permission gate, device
                               picker, test screen, console, log panel.
```

All files carry detailed comments written for a developer coming from
Swift/iOS, explaining both the Kotlin language features and the Android APIs.

## Reliability design

- **Keepalive**: CR-LF written every 15 seconds while connected; the link
  never idles and a dead link turns into a fast IOException.
- **Auto-reconnect**: any drop that the user did not ask for triggers reconnect
  attempts with exponential backoff (1s, 2s, 4s, ... capped at 30s).
- **Send is self-healing**: printing while disconnected connects first; a
  failed first write reconnects and retries once. Mid-sequence failures are
  never retried, so labels cannot double-print.
- **One writer at a time**: every socket operation goes through a single mutex,
  so rapid button presses, the keepalive, and status queries cannot interleave.
- The connection manager is an application-wide singleton and the activity is
  locked to portrait, so screen recreation cannot kill the socket.

## Hardware findings (tested on a real DPP-450, July 2026)

| Finding | Detail |
|---|---|
| `~JP` is fatal | Hard-locks the DPP-450 in every form tested (embedded in a format, standalone, paced, followed by `~PS`). Only a power cycle recovers. Works normally on the Zebra ZD421 - that difference is itself comparison data. Use `~JA` for queue clearing. |
| Settings persist | `^PW`, `^JM`, `^PM`, `^LR`, `^MU`, `^BY`, darkness, etc. stay set across labels until changed or power cycled. The auto-restore block normalizes after every print. |
| Pause needs `~PS` | `^PP` pauses until a resume; the Resume button covers it. |
| Counters not exposed | The DPP-450's `~WC` configuration printout lists no label counters, so `~RO` (counter reset) has no verifiable effect on this hardware. |
| No-output sets | Code sets 25, 34, 35 correctly produce no printed output. Sets 38-46 are host queries that answer over the socket (RX lines in the log). |
| Idle disconnects | The printer drops idle Bluetooth links (power save). The keepalive ping prevents it. |
| Sensor mode: pending confirmation | Code sets 34/35 (gap vs black-mark sensing) previously got silently reverted by auto-restore before the printer could react - fixed: sending 34/35 now syncs the Media/Print mode selectors so the mode sticks. Separately, the DPP-450 user manual describes sensor selection as also gated by a physical memory switch ("Use Gap Sensor", reached via the printer's own Function Setting menu) and DIP switch SW2 - unlike a Zebra, where `^MN` alone is normally sufficient. Not yet confirmed on hardware whether the physical switch must also be set. |

## Reference material

The ZPL payloads come from IPC Mobile's internal QA document
`03-2024_Testing_CodeSets.pdf` ("Code Sets for Testing DPP-450 ZPL Emulation").
Sections within a code set must be run independently, which is why multi-part
sets expand into one button per section.

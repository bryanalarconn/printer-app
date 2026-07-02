# DPP-450 Bluetooth Test Harness

A minimal Android app for validating Bluetooth Classic (SPP/RFCOMM) connectivity
and ZPL print correctness on the IPC Mobile DPP-450 mobile thermal printer.

This is a test tool, not a production app. It exists to answer two questions:

1. How reliable is a raw RFCOMM socket connection to the DPP-450, and which
   connection strategy (hold open vs reconnect per print) behaves better?
2. Which ZPL commands does the DPP-450's ZPL emulation actually support, and
   which ones misbehave?

No vendor SDK is used. The app talks to the printer over a plain serial-style
Bluetooth socket using the standard SPP UUID (`00001101-0000-1000-8000-00805F9B34FB`).

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
     printer's power-save timer never drops an idle link. Turn it off to
     measure how quickly the printer kills idle connections.
   - **Auto-restore defaults after print** switch. ZPL settings commands
     persist on the printer until changed or power cycled, so one test's
     `^PW300` or `^JMB` corrupts every following print. When this is on, every
     print carries a settings-reset block. Turn it off when deliberately
     testing settings persistence (code sets 19-23, 28).
   - **Unpause** (`~PS`), **Clear queue** (`~JA`), and **Restore defaults**
     utility buttons for recovering the printer without touching it.
   - The 49 numbered code sets from IPC's QA document, one Print button per
     independently runnable section.
4. The log panel at the bottom records every TX, RX, connection drop,
   auto-reconnect attempt, and error with millisecond timestamps. Responses
   from host-status commands (`~HS`, `^HH`, `~HI`, ...) appear there as RX lines.

## Project layout

```
app/src/main/java/com/bryanalarcon/printertest/
  BluetoothPrinterManager.kt   Connection engine: socket, reader loop,
                               keepalive, auto-reconnect with backoff,
                               serialized writes. App-wide singleton.
  ZplTestScripts.kt            The 49 ZPL code sets transcribed from
                               03-2024_Testing_CodeSets.pdf, plus the
                               unpause / cancel-all / restore-defaults payloads.
  MainActivity.kt              Jetpack Compose UI: permission gate,
                               device picker, test screen, log panel.
```

All three files carry detailed comments written for a developer coming from
Swift/iOS, explaining both the Kotlin language features and the Android APIs.

## Reliability design

- **Keepalive**: CR-LF written every 15 seconds while connected. ZPL ignores
  control characters between label formats, so nothing prints, but the link
  never idles and a dead link turns into a fast IOException.
- **Auto-reconnect**: any drop that the user did not ask for triggers reconnect
  attempts with exponential backoff (1s, 2s, 4s, ... capped at 30s), until it
  succeeds or the user disconnects.
- **Send is self-healing**: printing while disconnected connects first; a
  failed first write reconnects and retries once. Mid-sequence failures are
  never retried, so labels cannot double-print.
- **One writer at a time**: every socket operation goes through a single mutex,
  so rapid button presses and the keepalive cannot interleave bytes.
- The connection manager is an application-wide singleton and the activity is
  locked to portrait, so screen recreation cannot kill the socket.

## Hardware findings (tested on a real DPP-450, July 2026)

These were established with this app and matter for any production integration:

| Finding | Detail |
|---|---|
| `~JP` is fatal | Hard-locks the printer in every form tested (embedded in a format, standalone, paced, followed by `~PS`). Only a power cycle recovers. Never send `~JP`; use `~JA` for queue clearing (verified working). |
| Settings persist | `^PW`, `^JM`, `^PM`, `^LR`, `^MU`, `^BY`, darkness, etc. stay set across labels until changed or power cycled. Either reset what you touch or send a normalization block (see `RESTORE_DEFAULTS_ZPL`). |
| Pause needs `~PS` | `^PP` pauses until a resume; IPC's test document never sends one. The Unpause button covers it. |
| Counters not exposed | The DPP-450's `~WC` configuration printout lists no label counters, so `~RO` (counter reset) has no verifiable effect on this hardware. |
| No-output sets | Code sets 25, 34, 35 correctly produce no printed output. Sets 38-46 are host queries that answer over the socket (visible as RX lines in the log). |
| Idle disconnects | The printer drops idle Bluetooth links (power save). The keepalive ping prevents it. |

## Reference material

The ZPL payloads come from IPC Mobile's internal QA document
`03-2024_Testing_CodeSets.pdf` ("Code Sets for Testing DPP-450 ZPL Emulation").
Sections within a code set must be run independently, which is why multi-part
sets expand into one button per section.

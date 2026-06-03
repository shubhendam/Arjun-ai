# Phase 1 — Audio Bridge & Input Source Priority

This document describes what was built in Phase 1 of Project Arjun-AI: a bidirectional audio bridge between a Galaxy Watch 4 (Wear OS), Bluetooth headsets, and an Android phone, with smart input-source selection and output routing. **No AI / LLM yet** — that's Phase 2.

## What this app does today

The watch is a **trigger surface**, not necessarily the audio source. The phone decides where audio actually comes from when a session starts.

1. User opens the Arjun-AI app on both phone and watch.
2. On the watch, the user taps **▶ Start**. The watch sends a `/arjun/trigger` message ("start") to the phone — it does NOT immediately open the audio channel.
3. The phone's `InputSourceManager` evaluates available mic sources in this priority order:
   1. **BT headset mic** (any paired+connected device exposing HFP / SCO) → phone starts SCO and records on the phone
   2. **Watch mic** (when no BT mic is available) → phone sends `/arjun/control` "stream" to the watch; the watch opens `/arjun/audio` and streams PCM
   3. **Phone mic** (only when triggered from the phone UI with no BT) → phone records from its own mic
4. User can also press **Start from phone** in the phone UI to trigger a session without using the watch.
5. While recording, the phone UI shows live status with the chosen source name (e.g. "Recording from BT_HEADSET_MIC").
6. User taps **⏹ Stop** on the watch (or **Stop** in the phone UI) — phone finalizes the WAV.
7. Per-source timestamped WAVs are saved (`session_..._btmic.wav` / `_watch.wav` / `_phonemic.wav`).
8. The phone UI lists all past recordings with three controls per row:
   - **▶ Play on phone** — straight playback through phone speaker / connected earbuds (OS handles routing)
   - **⌚ Play with smart routing** — runs through `OutputRouter`: earbuds (if connected) → watch (if reachable) → phone speaker
   - **🗑 Delete** — removes file + row
9. Top-right indicator shows watch reachability (green / gray / yellow); BT devices card shows connected earbuds/helmets with the active-audio one marked.

## Architecture

```
Watch (wear/)                                         Phone (app/)
─────────────                                         ────────────
MainActivity (UI: ▶/⏸/⏹)                              MainActivity (UI)
   │                                                     │
   │ press ▶                                             │
   ▼                                                     ▼
AudioStreamer.sendTrigger("start") ─┐                PhoneTriggerListener
                                    │                    │
                                    │ /arjun/trigger     │
                                    └────────────────────▶
                                                         ▼
                                              InputSourceManager.decide()
                                                         │
              ┌──────────────────┬─────────────────┬─────┴────────┐
              ▼                  ▼                 ▼              ▼
        BT_HEADSET_MIC      WATCH_MIC          PHONE_MIC       (idle)
              │                  │                 │
              │                  │ /arjun/control  │
              │                  │ "stream"        │
              │                  ▼                 │
              │           ControlListener (wear)   │
              │                  │                 │
              │                  ▼                 │
              │       AudioStreamer.startMicStreaming()
              │                  │                 │
              │                  │ /arjun/audio    │
              │                  ▼                 │
              │       PhoneAudioReceiverService    │
              │                  │                 │
              ▼                  ▼                 ▼
          AudioRecord (phone, mic = SCO or built-in / streamed channel)
                                  │
                                  ▼
                          16 kHz mono PCM → WAV file
```

Transport for control plane is **MessageClient** (small fire-and-forget messages, low latency, idempotent). Transport for audio (only when source is watch) is **ChannelClient** (streamed bytes). For BT mic and phone mic, no Data Layer traffic is involved — phone records natively.

## Files & where they live

### Phone module — `app/src/main/java/com/example/arjun_ai/`

| File | Purpose |
|---|---|
| `MainActivity.kt` | Compose UI: header, session card with source name and live byte counter, "Start from phone" / "Stop" buttons, BT devices card, recordings list, watch indicator. |
| `ArjunApplication.kt` | Empty `Application` subclass — placeholder for Phase 2 init (Gemma engine, VAD). |
| `InputSourceManager.kt` | **Phase 1 centerpiece.** Decides mic source per session. Owns BT-SCO setup/teardown, `AudioRecord` lifecycle, WAV writing. Logs the actual negotiated codec (wideband vs narrowband) for diagnostics. |
| `PhoneTriggerListener.kt` | `WearableListenerService` that catches `/arjun/trigger` messages from the watch and calls `InputSourceManager.startSession` / `stopSession`. |
| `PhoneAudioReceiverService.kt` | `WearableListenerService` that catches `/arjun/audio` channel opens (only used when source is WATCH_MIC). Reads PCM stream, writes timestamped WAV. |
| `AudioSink.kt` | Singleton holding `StateFlow`s: live session status + list of saved `Recording`s. Bridges receiver state and UI. |
| `OutputRouter.kt` | Pure decision logic: detects earbuds via `AudioManager.getDevices()`, checks watch reachability. Returns `EARBUDS_VIA_PHONE` / `WATCH` / `PHONE_SPEAKER`. |
| `WatchPlayer.kt` | Streams a WAV file to the watch over `/arjun/tts`. Sends a 16-byte header (magic "ARJN" + sample rate + channels + bit depth), then raw PCM. |
| `WatchConnection.kt` | Polls `connectedNodes` every 3 s for the top-right reachability dot. |
| `BluetoothDevices.kt` | Polls `AudioManager` + `BluetoothAdapter` for the Connected Devices card. Distinguishes "currently active audio sink" (green dot) from "bonded but idle" (gray dot). |

### Phone module — `app/src/main/`

| File | Purpose |
|---|---|
| `AndroidManifest.xml` | Declares `MainActivity`, `ArjunApplication`, the receiver service for `/arjun/audio`, the trigger listener service for `/arjun/trigger`. Permissions: `RECORD_AUDIO`, `BLUETOOTH_CONNECT`, `MODIFY_AUDIO_SETTINGS`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`. |
| `build.gradle.kts` | minSdk 28, compileSdk 36, deps include `play-services-wearable`, `kotlinx-coroutines-play-services`, `material-icons-extended`. |

### Watch module — `wear/src/main/java/com/example/arjun_ai/wear/`

| File | Purpose |
|---|---|
| `MainActivity.kt` | Wear Compose UI: Start sends `/arjun/trigger` "start"; Stop sends "stop"; Mute is local (only meaningful if watch is actually the source). Listens for `/arjun/control` broadcasts. |
| `AudioStreamer.kt` | Two responsibilities: `sendTrigger(cmd)` for control messages, and `startMicStreaming()` for when phone selected WATCH_MIC and asked us to stream. |
| `ControlListener.kt` | `WearableListenerService` catching `/arjun/control` from phone ("stream" / "stop"). Forwards as a local broadcast so `MainActivity` can react. |
| `PlaybackListener.kt` | `WearableListenerService` for `/arjun/tts`. Reads header + builds an `AudioTrack` at declared sample rate, plays the PCM through the watch speaker. |

### Watch module — `wear/src/main/`

| File | Purpose |
|---|---|
| `AndroidManifest.xml` | Declares `MainActivity`, mic permission, and both `<service>` blocks (`ControlListener` + `PlaybackListener`). |
| `build.gradle.kts` | minSdk 30, compileSdk 36. Same Wearable + coroutines + icons deps as phone, plus Wear Compose Material. |

## Key design decisions

These were chosen explicitly during Phase 1 and should not be revisited casually.

1. **Trigger plane vs. data plane.** `/arjun/trigger` (watch → phone) and `/arjun/control` (phone → watch) are small `MessageClient` payloads. `/arjun/audio` and `/arjun/tts` are streamed `ChannelClient` data. Keeping them separate means the watch can ask for a session without committing to being the audio source.
2. **Phone decides the mic source, not the watch.** The watch is a thin trigger. This lets the user wear earphones, press ▶ on the watch, and have the audio captured from the earphones — which is what users actually want. The watch is the trigger surface; the BT headset is the I/O.
3. **BT SCO over A2DP for mic.** A2DP has no return mic path. SCO (the call-audio profile) is the only way to capture from a BT headset's microphone. Wideband mSBC at 16 kHz is now common (verified working on Boat Airdopes Supreme and BTSB-007NB helmet). Trade-off: A2DP music output drops or also switches to SCO during recording; acceptable because we want full attention on the assistant during a session.
4. **`MediaRecorder.AudioSource.VOICE_RECOGNITION`, not `MIC`.** This source asks the audio framework for voice-tuned input, lower latency, and disables AEC tuned for speakerphone use that hurts close-mic capture.
5. **Tried and rejected: Bluetooth media-key capture.** Earlier attempt to use `MediaSession` for "single tap right earbud → start session" was abandoned because (a) the OS routes media buttons to whichever media app was most recently active, and (b) the earbud firmware itself maps taps to PLAY/NEXT/PREV before transmitting, so finer events ("which bud, single vs long tap") can't be observed. The explicit watch / phone button trigger is more reliable.
6. **Per-source filename suffix (`_btmic`, `_watch`, `_phonemic`).** Makes it obvious in the recording list which mic was used. Useful during Phase 1 verification.
7. **`applicationId = com.example.arjun_ai` on both modules.** Data Layer only pairs apps with identical applicationIds across watch and phone. Module **namespaces** differ; the build-output package is the same.
8. **`pathPrefix` per service, not a catch-all `/arjun`.** Each listener service has a narrow filter so audio doesn't trigger the trigger listener and vice versa.
9. **`WearableListenerService` for everything inbound.** Play Services auto-launches them on incoming events; they survive the host app being backgrounded. No manual binding required.
10. **Per-session WAV files in `filesDir/recordings/`.** App-private storage, no scoped-storage / `MediaStore` complexity. Files are visible only to this app.
11. **`systemBarsPadding()` on the phone layout.** Content respects status bar and gesture nav. Don't remove.

## Verified BT mic results

Both test devices negotiated **wideband mSBC (true 16 kHz)** with the phone, confirmed via the diagnostic logging in `InputSourceManager.logActualAudioFormat()`:

```
=== AUDIO FORMAT ===
  requested:    16000 Hz mono PCM16
  AudioRecord:  16000 Hz ch=1 fmt=2
  routed type:  7
  product:      Airdopes Supreme / BTSB-007NB
  device rates: [16000]
  BT codec:     WIDEBAND (mSBC, true 16 kHz)
====================
```

This is important for Phase 2: it means the audio we hand to Gemma is genuine 16 kHz speech, not 8 kHz upsampled. Gemma's audio understanding should work at full quality.

## How to run it (developer setup)

### One-time prep

1. Pair the Galaxy Watch 4 with the phone via Samsung's **Galaxy Wearable** app.
2. On the watch: Settings → About → Software → tap build number 7× to enable developer options. Then Settings → Developer options → ADB debugging ON and Debug over Wi-Fi ON. Note the IP.
3. From the laptop: `adb connect <watch-ip>:5555`. Watch shows in Android Studio's device dropdown as **SM-R…**.
4. Plug phone in via USB (USB debugging on).

### Build & install

1. Open `Arjun-ai` in Android Studio, sync Gradle.
2. Run the `app` module on the phone first.
3. Run the `wear` module on the watch (first launch prompts mic permission — Allow).
4. Grant "Nearby devices" permission on the phone app the first time (for BT device enumeration).

### Smoke test — three scenarios

**1. Watch trigger + BT earphones connected**

| Action | Expected |
|---|---|
| Connect earphones | Devices card shows them with green dot |
| Press ▶ on watch | Session card flips to "● Recording from BT_HEADSET_MIC" |
| Log shows | `TRIGGER=WATCH -> SOURCE=BT_HEADSET_MIC`, `SCO connected`, `BT codec: WIDEBAND...` |
| Talk for 10s, press ⏹ | New row `session_..._btmic.wav` appears; play it = your voice via earphone mic |

**2. Watch trigger + no BT mic**

| Action | Expected |
|---|---|
| Disconnect earphones | Card empties |
| Press ▶ on watch | Session card: "● Recording from WATCH_MIC" |
| Log shows | `decideSource: no BT mic -> WATCH_MIC`, `sent 'stream' to <Watch>`, `Channel opened from ...` |
| Talk, press ⏹ | New row `session_..._watch.wav` |

**3. Phone trigger + no BT mic**

| Action | Expected |
|---|---|
| Tap **Start from phone** | "● Recording from PHONE_MIC" |
| Log shows | `TRIGGER=PHONE_APP -> SOURCE=PHONE_MIC` |
| Tap **Stop** | `session_..._phonemic.wav` |

### Useful adb commands

Full diagnostic log for the phone app:
```
adb -d logcat --pid=$(adb -d shell pidof com.example.arjun_ai) -s InputSource:D PhoneTrigger:D ArjunAudioRx:D
```

Mute the noisy BT-device poll spam but keep everything else:
```
adb -d logcat --pid=<phone-pid> BtDevices:S *:V
```

Pull a recording for inspection on the laptop:
```
adb -d shell "run-as com.example.arjun_ai cat files/recordings/session_<timestamp>_btmic.wav" > out.wav
```

## Known caveats

- **SCO startup delay ~500ms.** The first half-second after pressing ▶ on the watch may not be captured. Tolerable for now; we'll buffer in Phase 2 when wake-word handles the entry timing.
- **A2DP music drops during SCO recording.** Bluetooth radio limitation — can't do both at the same time. Acceptable because a session is short and music isn't the priority during it.
- **Watch speaker is quiet.** Rotate the bezel during playback to raise media volume. Hardware constraint, not the app.
- **No retry on channel failure.** If the watch channel drops mid-stream, the current session ends and the user has to press Start again.
- **First watch playback has ~1 s latency.** Channel open + waking `PlaybackListener` service takes a moment. Subsequent plays are faster.

## What's next (Phase 2 entrypoints)

When picking up Phase 2 in Claude Code, the integration points are:

- **`InputSourceManager`** — feed the captured PCM into `VadSilero` (ported from POC) for turn-boundary detection, then send the buffered audio to the Gemma audio model (ported from POC `agentmode/AgentModelEngine.kt`). Skip Whisper; Gemma's audio understanding handles speech directly at 16 kHz.
- **`AudioSink`** — keep for debugging; the real session lifecycle moves into a `SessionViewModel` orchestrated like the POC's `AgentModeViewModel`.
- **`WatchPlayer.streamWavToWatch()`** — already takes a WAV file. TTS output from Phase 2 plugs in unchanged.
- **`OutputRouter.decide()`** — already correct; call before every TTS playback.
- **`ArjunApplication.onCreate()`** — Gemma model preload happens here, mirroring `GemmaApplication`.
- **Tools** — `AgentModeTools.kt` + `ChatModeTools.kt` + `ContactsHelper.kt` ported in, fused into one mode.

The POC reference is at `C:\Users\shash\OneDrive\Desktop\Project-Arjun\reference-app\poc_android-call\poc_android` — see `Project_scope.md` for the full porting checklist.

Phase 3 will add wake-word ("Hey Arjun") gating after the explicit trigger, so accidental button presses don't engage the LLM.
# Phase 1 — Watch ↔ Phone Audio Bridge

This document describes what was built in Phase 1 of Project Arjun-AI: a bidirectional audio bridge between a Galaxy Watch 4 (Wear OS) and an Android phone, with smart output routing. **No AI / LLM yet** — that's Phase 2.

## What this app does today

1. User opens the Arjun-AI app on **both** the phone and the watch.
2. On the watch, the user taps **▶ Start**. The watch starts capturing mic audio at 16 kHz mono PCM and streams it over the Wearable Data Layer to the phone.
3. The phone receives the stream, shows live byte progress, and writes a uniquely-named `.wav` file per session into the app's private storage.
4. User taps **🎤 Mute** to pause the stream (channel stays open), tap again to resume.
5. User taps **⏹ Stop** to end the session — phone finalizes the WAV.
6. The phone UI lists all past recordings with three controls per row:
    - **▶ Play on phone** — straight playback through phone speaker / connected earbuds (OS handles routing)
    - **⌚ Play with smart routing** — runs through `OutputRouter`: earbuds (if connected) → watch (if reachable) → phone speaker
    - **🗑 Delete** — removes the file and the row
7. A small dot at the top-right of the phone screen shows watch reachability (green / gray / yellow), polled every 3 seconds.

## Architecture

```
Watch (wear/)                                          Phone (app/)
─────────────                                          ────────────
MainActivity (UI)                                      MainActivity (UI)
   │                                                      │
   ▼                                                      ▼
AudioStreamer ──── /arjun/audio ───▶ PhoneAudioReceiverService
   │            (Data Layer Channel)            │
   ▼                                            ▼
AudioRecord (mic)                          Writes session_*.wav
                                                  │
PlaybackListener ◀──── /arjun/tts ─────  WatchPlayer  ◀──── OutputRouter
   │                                            │              ▲
   ▼                                            ▼              │
AudioTrack (speaker)                       MediaPlayer    decide()
                                          (phone audio)
```

Transport is Google's **Wearable Data Layer API** (`ChannelClient`), not raw BLE/GATT. The Data Layer rides on top of whatever pairing the OS-level Wear OS / Galaxy Wearable apps already established, so we don't touch the Bluetooth radio directly. Failure modes (out of range, watch off, etc.) surface as channel-open errors and are handled by the router.

## Files & where they live

### Phone module — `app/src/main/java/com/example/arjun_ai/`

| File | Purpose |
|---|---|
| `MainActivity.kt` | Compose UI: header, live status card, recordings list, watch indicator. Starts `WatchConnection` polling on launch. |
| `ArjunApplication.kt` | Empty `Application` subclass — placeholder for Phase 2 init (Gemma engine, Whisper, VAD). |
| `AudioSink.kt` | Singleton holding two `StateFlow`s: live session status + list of saved `Recording`s. Bridges the receiver service and the UI. |
| `PhoneAudioReceiverService.kt` | `WearableListenerService` auto-launched by Play Services when watch opens `/arjun/audio`. Reads PCM, writes timestamped WAV, calls `AudioSink.onFinished()`. |
| `OutputRouter.kt` | Pure decision logic: detects earbuds via `AudioManager.getDevices()`, checks watch reachability via `NodeClient.connectedNodes`. Returns `EARBUDS_VIA_PHONE` / `WATCH` / `PHONE_SPEAKER`. |
| `WatchPlayer.kt` | Streams a WAV file to the watch over `/arjun/tts`. Parses WAV header, sends a 16-byte protocol header (magic "ARJN" + sample rate + channels + bit depth), then raw PCM. |
| `WatchConnection.kt` | Singleton that polls `connectedNodes` every 3 s and exposes a `StateFlow<Status>` for the UI dot. |

### Phone module — `app/src/main/`

| File | Purpose |
|---|---|
| `AndroidManifest.xml` | Declares `MainActivity`, `ArjunApplication`, and the receiver service with `<intent-filter>` matching `wear://…/arjun/audio`. |
| `../../build.gradle.kts` | minSdk 28, compileSdk 36, deps include `play-services-wearable`, `kotlinx-coroutines-play-services`, `material-icons-extended`. |

### Watch module — `wear/src/main/java/com/example/arjun_ai/wear/`

| File | Purpose |
|---|---|
| `MainActivity.kt` | Wear Compose UI with three buttons (Start/Mute/Stop). Holds an `AudioStreamer` instance, surfaces status text from it. |
| `AudioStreamer.kt` | Opens `/arjun/audio` channel to the connected phone node, runs an `AudioRecord` loop, writes PCM bytes to the channel output stream. Honors mute via `AtomicBoolean`. |
| `PlaybackListener.kt` | `WearableListenerService` that fires when phone opens `/arjun/tts`. Reads the 16-byte header, builds an `AudioTrack` at the declared sample rate, streams the body to it. |

### Watch module — `wear/src/main/`

| File | Purpose |
|---|---|
| `AndroidManifest.xml` | Declares `MainActivity`, mic permission, and both `<service>` blocks (only `PlaybackListener` — the streamer is started from the activity). |
| `../../build.gradle.kts` | minSdk 30, compileSdk 36. Same Wearable + coroutines + icons deps as phone, plus Wear Compose Material. |

## Key design decisions

These were chosen explicitly during Phase 1 and should not be revisited casually.

1. **Wearable Data Layer over raw BLE** — the POC used a custom L2CAP-style BLE socket because its smart glasses firmware required it. Watches don't expose anything similar; Data Layer is what every production Wear OS audio app uses. We get auto-reconnect, doze survival, and zero radio code.
2. **`applicationId = com.example.arjun_ai` on both modules** — Data Layer only pairs apps with identical applicationIds across watch and phone. Module **namespaces** differ (`com.example.arjun_ai` vs `com.example.arjun_ai.wear`), but the build-output package is the same.
3. **16 kHz mono input, configurable output sample rate** — input is fixed at 16 kHz because that's what Whisper expects in Phase 2. Output is declared per-stream in the 16-byte header (currently 16 kHz when we replay recordings; will be 24 kHz when Phase-2 TTS plugs in).
4. **Routing priority: earbuds → watch → phone** — when earbuds are present, the user wants them. Watch beats phone speaker because the watch is on their wrist. Phone speaker is the last resort.
5. **`pathPrefix="/arjun/audio"` not `/arjun`** — narrows the manifest filter so the receiver service doesn't also fire on `/arjun/tts`. The wear-side `PlaybackListener` similarly filters to `/arjun/tts` only.
6. **`WearableListenerService` instead of binding manually** — Play Services auto-launches these on channel events; they survive the host app being backgrounded. The streamer (watch → phone) doesn't need this because the watch UI is the trigger.
7. **Per-session WAV files in `filesDir/recordings/`** — not external storage, no `MANAGE_EXTERNAL_STORAGE`, no `MediaStore` complexity. Files are visible only to this app, which is what we want for now.
8. **systemBarsPadding() on the phone layout** — content respects status bar and gesture nav. Don't remove this.

## How to run it (developer setup)

### One-time prep

1. Pair the Galaxy Watch 4 with the phone via Samsung's **Galaxy Wearable** app (standard pairing the user already did).
2. On the watch: Settings → About → Software → tap build number 7× to enable developer options. Then Settings → Developer options → **ADB debugging ON** and **Debug over Wi-Fi ON**. Note the IP.
3. From the laptop: `adb connect <watch-ip>:5555`. Galaxy Watch 4 should appear in Android Studio's device dropdown as **SM-R…**.
4. Plug phone in via USB (USB debugging on).

### Build & install

1. Open the `Arjun-ai` project in Android Studio.
2. Sync Gradle. If `Unresolved reference` errors appear on `libs.androidx.*` aliases, the project's `gradle/libs.versions.toml` is missing entries — check the entries used in `app/build.gradle.kts` exist there.
3. **Run the `app` module on the phone first.** Wait for the "Waiting for watch…" screen.
4. **Run the `wear` module on the watch.** First launch will prompt for mic permission — Allow.
5. Both apps share `applicationId com.example.arjun_ai` — they auto-discover via Data Layer.

### Smoke test

| Action | Expected result |
|---|---|
| Open phone app | Top-right dot turns green within ~3 s, watch name shows |
| Tap ▶ on watch | Watch shows "Streaming…", phone live card shows byte count climbing |
| Tap 🎤 on watch | Watch says "Muted", phone bytes stop climbing |
| Tap 🎤 again | Resumes |
| Tap ⏹ on watch | Phone returns to "Waiting for watch…", new row appears in Recordings list |
| Tap ▶ next to a recording | Plays on phone speaker (or earbuds if connected) |
| Tap ⌚ next to a recording | Plays through the watch speaker (with no earbuds connected) |
| Tap ⌚ with earbuds connected | Plays through earbuds, status line says "Routing to EARBUDS_VIA_PHONE" |
| Power watch off | Indicator dot turns gray within ~3 s |

### Where files end up

Recordings are saved at:
```
/data/data/com.example.arjun_ai/files/recordings/session_<yyyy-MM-dd_HH-mm-ss>.wav
```

To pull one for inspection on a laptop:
```
adb -d shell "run-as com.example.arjun_ai cat files/recordings/session_<timestamp>.wav" > out.wav
```

## Known caveats

- **First watch playback has ~1 s latency** — opening the channel and waking the `PlaybackListener` service takes a moment. Repeat plays are faster.
- **Watch speaker is quiet** — physically tiny. Rotate the bezel during playback to raise media volume. This is hardware, not the app.
- **App must be open on the watch at least once** for Data Layer discovery to work. After that, the receiver service on the phone can be triggered without the wear UI being foreground — but the watch user still needs the UI to press Start.
- **Watch goes to sleep mid-playback** — long playbacks may stutter if the watch screen turns off. We'll add a wake lock in Phase 2 when TTS responses get longer.
- **No retry on channel failure** — if the channel drops mid-stream, the current session ends and the user has to press Start again.

## What's next (Phase 2 entrypoints)

When picking up Phase 2, the integration points are:

- `PhoneAudioReceiverService.onChannelOpened()` — instead of writing the PCM straight to disk, feed it into `VadSilero` → `WhisperEngine` (both ported from the POC).
- `AudioSink` — keep it for debugging, but the real session lifecycle moves into a new `SessionViewModel` orchestrated like the POC's `ChatViewModel`.
- `WatchPlayer.streamWavToWatch()` — already takes a WAV file. TTS output goes through this unchanged.
- `OutputRouter.decide()` — already correct; just call it before every TTS playback.
- `ArjunApplication.onCreate()` — model preload happens here, mirroring `GemmaApplication`.

The POC reference for all of this is at `C:\Users\shash\OneDrive\Desktop\Project-Arjun\reference-app\poc_android-call\poc_android` — see `Project_scope.md` for the porting checklist.
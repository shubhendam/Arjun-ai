# Project Arjun-AI — Scope & Roadmap

## Vision

A fully on-device personal AI assistant where the **Galaxy Watch 4 (Wear OS)** acts as the audio I/O interface and an **Android phone** does all the heavy lifting (STT → LLM → tools → TTS). No cloud, no API calls — everything runs locally on the phone.

The user presses a button on the watch, speaks, and the phone hears them, thinks, executes actions (calls, messages, queries, vision tasks), and speaks the response back through the watch. The watch is a thin remote; the phone is the brain.

## Reference / POC location

The original proof-of-concept application — which already implements the full STT → LLM → tools → TTS pipeline using **smart glasses over BLE** instead of a watch — lives at:

```
C:\Users\shash\OneDrive\Desktop\Project-Arjun\reference-app\poc_android-call\poc_android
```

This is the **authoritative reference for everything AI-side**: Whisper STT, Silero VAD, Gemma LLM hosting via LiteRT-LM, function-calling with `FunctionGemmaEngine`, agent-mode multi-step tool use, FastVLM vision, contacts/calling tools, etc. Whenever Arjun-AI needs an AI subsystem ported, copy the implementation from there. Path lookups inside that folder follow the structure laid out in `agentmode_README.md` and the file tree from the POC discussion.

The new Arjun-AI app (this project) lives at:

```
C:\Users\shash\OneDrive\Desktop\Project-Arjun\Arjun-ai
```

with two Gradle modules:
- `app/` → phone application (`com.example.arjun_ai`)
- `wear/` → Wear OS application (same applicationId, namespace `com.example.arjun_ai.wear`)

## Final architecture (target state)

```
┌───────────────────────────────────┐         ┌──────────────────────────────────────┐
│       Galaxy Watch 4 (Wear)       │         │            Android Phone             │
│                                   │  Data   │                                      │
│  ┌─────────────┐                  │ Layer   │  ┌────────────────────────────────┐  │
│  │ Mic capture │── PCM 16 kHz ────┼─────────┼─▶│ VAD (Silero) → STT (Whisper)   │  │
│  └─────────────┘                  │ channel │  │       ↓                        │  │
│                                   │ /arjun/ │  │   LLM (Gemma 3-4B via LiteRT)  │  │
│                                   │  audio  │  │       ↓                        │  │
│  ┌─────────────┐                  │         │  │   Tool calls (calling,         │  │
│  │ AudioTrack  │◀── PCM ──────────┼─────────┼──│   messaging, agent, vision)    │  │
│  │ playback    │                  │ /arjun/ │  │       ↓                        │  │
│  └─────────────┘                  │  tts    │  │      TTS                       │  │
│                                   │         │  └────────────────────────────────┘  │
│  Three buttons: Start/Mute/Stop   │         │   Routing: earbuds→watch→phone spk   │
└───────────────────────────────────┘         └──────────────────────────────────────┘
```

The user only ever interacts with the watch. The phone screen exists for debugging, configuration, and session history.

## Phases

### Phase 1 — Watch ↔ Phone audio bridge ✅ (in progress / mostly done)

**Goal:** Prove that audio can flow bidirectionally between watch and phone, reliably, with sensible output routing. No AI yet.

**Done:**
- Wear OS app with Start / Mute / Stop buttons
- Mic captured at 16 kHz mono PCM on the watch
- Streamed over Wearable Data Layer `ChannelClient` on path `/arjun/audio`
- Phone `WearableListenerService` receives the stream, writes a unique-named WAV per session
- Phone UI: live status, recordings list with per-row play / smart-route / delete
- Smart output router: earbuds (if connected) → watch (if reachable) → phone speaker
- Reverse channel `/arjun/tts` for sending audio from phone back to watch, played via `AudioTrack`
- Top-right watch connection indicator (polls reachability every 3 s)

**Pending in this phase (nice-to-have, but not blocking Phase 2):**
- Phone-side foreground service so the bridge keeps working when the app is backgrounded
- Watch wake-lock during playback so screen-off doesn't stutter
- Opus encoding to cut bandwidth (currently raw PCM; works fine but lossy on weaker BT links)

### Phase 2 — Port the AI pipeline (no UI rebuild, just brains)

**Goal:** Take the existing POC's AI stack and make it run end-to-end driven by the watch instead of glasses. After this phase, pressing ▶ on the watch and speaking gets a real spoken AI response back through the watch.

Steps (port each from the POC at the path above):

1. **VAD + Recorder** — port `VadSilero.kt`, `VadConfig.kt`, `Recorder.kt`, `WaveUtil.kt`. Replace the POC's BLE-glasses byte source with the Data Layer stream we already have.
2. **Whisper STT** — port `WhisperEngine.kt`, `WhisperUtil.kt`, the JNI/C++ `whisper.h` + `talkandexecute.*`, the `whisper_tiny_en_14.tflite` and `filters_vocab_en.bin` assets, and the CMake setup.
3. **Gemma LLM** — port `ModelEngine.kt`, `GemmaApplication.kt` (rename to `ArjunApplication.kt`), the LiteRT-LM dep, the `litertlm` model asset. Keep `ChatViewModel.kt` mostly as-is.
4. **Function-calling Gemma** — port `FunctionGemmaEngine.kt`, `Actions.kt`, the `custom_functionGemma_q8_ekv1024.litertlm` asset.
5. **Tools** — port `ChatModeTools.kt` (calling/contacts), `MobileActionsTools.kt`, `AgentModeTools.kt`. Wire `ContactsHelper.kt`.
6. **TTS** — use Android's built-in `TextToSpeech` to start (POC pattern). Output bytes go through the existing `WatchPlayer` we already built.
7. **Agent mode (vision)** — port `FastVlmEngine.kt`, `AgentModeViewModel.kt`. Camera frames captured from the phone since the watch has no useful camera.
8. **Single fused mode** — unlike the POC's three modes (chat / mobile-actions / agent), Arjun-AI will have **one mode** that has agent's multi-step reasoning plus chat's calling/messaging tools. The phone UI exposes this single mode; the watch just streams audio in and out.

By the end of Phase 2: watch button → speak → spoken AI response. Same behavior as the POC but with the watch as the I/O surface.

### Phase 3 — Production polish & persistent intelligence

**Goal:** Make Arjun feel like a real daily-driver assistant, not a tech demo.

- **Persistent sessions** — conversation history survives app restart, watch reboot, phone reboot
- **Long-term memory** — facts about the user (preferences, contacts, frequently-mentioned people/places) stored in a local SQLite DB and surfaced as context for the LLM
- **Background availability** — assistant is reachable from the watch at any time, even with phone in pocket, app not in foreground
- **Wake-word on watch** (optional/exploratory) — replace the Start button with "Hey Arjun" using a small on-watch VAD/keyword model
- **Multi-turn tool execution** — long-running actions (e.g. "remind me when Mike gets home") backed by persistent jobs
- **Settings UI** — choose voice, language, which tools are enabled, model selection, etc.
- **Robustness** — graceful degradation when watch disconnects mid-session, audio buffering, retry logic

## Non-goals (explicit)

- No cloud LLM calls
- No iOS support
- No support for non-Wear-OS watches (Galaxy Wearable / Tizen path is dead since Watch 4)
- Not a general-purpose dictation app

## Current status snapshot (as of this file)

| Component | Status |
|---|---|
| Watch ↔ Phone audio bridge | ✅ Working |
| Recording list & playback UI | ✅ Working |
| Reverse audio (phone → watch) | ✅ Working |
| Smart output routing | ✅ Working |
| Connection indicator | ✅ Working |
| VAD / Whisper / Gemma / Tools | ❌ Phase 2 |
| TTS routed to watch | ❌ Phase 2 (transport ready, TTS engine missing) |
| Persistent memory | ❌ Phase 3 |
# Project Arjun-AI — Scope & Roadmap

## Vision

A fully on-device personal AI assistant where the **Galaxy Watch 4 (Wear OS)** acts as the trigger surface, **Bluetooth earphones/helmets** act as the primary audio I/O, and an **Android phone** does all the heavy lifting (LLM + tools + TTS). No cloud, no API calls — everything runs locally on the phone.

The user presses a button on the watch (or in the phone app), speaks — through whatever Bluetooth headset they're wearing, falling back to the watch or phone mic — and the phone hears them, thinks, executes actions, and speaks the response back to the same headset (or watch / phone speaker). The watch is a thin trigger + status surface; the phone is the brain; the BT headset is the I/O interface.

## Reference / POC location

The original proof-of-concept application — which already implements the full STT → LLM → tools → TTS pipeline using **smart glasses over BLE** instead of a watch + BT earphones — lives at:

```
C:\Users\shash\OneDrive\Desktop\Project-Arjun\reference-app\poc_android-call\poc_android
```

This is the **authoritative reference for everything AI-side**: Gemma LLM hosting via LiteRT-LM, Whisper STT, Silero VAD, function-calling with `FunctionGemmaEngine`, agent-mode multi-step tool use, FastVLM vision, contacts/calling tools, etc. Whenever Arjun-AI needs an AI subsystem ported, copy the implementation from there.

The new Arjun-AI app (this project) lives at:

```
C:\Users\shash\OneDrive\Desktop\Project-Arjun\Arjun-ai
```

with two Gradle modules:
- `app/` → phone application (`com.example.arjun_ai`)
- `wear/` → Wear OS application (same applicationId, namespace `com.example.arjun_ai.wear`)

## Final architecture (target state)

```
┌──────────────────┐       ┌─────────────────────────────────┐       ┌────────────────────┐
│   BT Earphones   │       │         Android Phone           │       │  Galaxy Watch 4    │
│   (mic + spkr)   │       │                                 │       │                    │
│                  │  SCO  │  ┌───────────────────────────┐  │ Data  │ ┌────────────────┐ │
│  Mic ─────────── │───────┼─▶│ InputSourceManager        │  │ Layer │ │ ▶/⏸/⏹ buttons  │ │
│                  │ HFP   │  │  (BT > Watch > Phone)     │◀─┼───────┼─│ trigger        │ │
│  Speaker ◀────── │───────┼──│           ↓               │  │/arjun/│ │                │ │
│                  │ A2DP  │  │ Wake-word listener (later)│  │trigger│ │ Mic (fallback) │ │
└──────────────────┘       │  │           ↓               │  │       │ │   /arjun/audio │ │
                           │  │ Gemma 3n / 4 (audio in)   │  │       │ │                │ │
                           │  │           ↓               │  │       │ │ Spkr (fallback)│ │
                           │  │ Tool calls + Agent loop   │  │ /arjun│ │   /arjun/tts   │ │
                           │  │           ↓               │  │ /tts  │ │                │ │
                           │  │ TTS audio                 │  │       │ └────────────────┘ │
                           │  └───────────────────────────┘  │       └────────────────────┘
                           │           ↑                     │
                           │           │ output routing      │
                           │  ┌────────┴────────────────┐    │
                           │  │ OutputRouter            │    │
                           │  │ (earbuds → watch → phn) │    │
                           │  └─────────────────────────┘    │
                           └─────────────────────────────────┘
```

The user only interacts with the watch buttons (or earphone implicit-trigger later); the phone screen exists for debugging, configuration, and session history.

## Phases

### Phase 1 — Audio bridge & input source priority ✅ DONE

**Goal:** Prove that audio can flow bidirectionally between watch + phone + BT earphones, reliably, with sensible input source priority and output routing. No AI yet.

**Done:**
- Wear OS app with Start / Mute / Stop buttons that send `/arjun/trigger` messages to the phone
- Phone-side `InputSourceManager` decides mic source per session:
    1. **Bluetooth headset mic** (via SCO/HFP) — verified working with Boat Airdopes Supreme and BTSB-007NB helmet, both negotiating wideband (mSBC) 16 kHz
    2. **Watch mic** — fallback when no BT mic, streamed over `/arjun/audio` Data Layer channel
    3. **Phone mic** — fallback when triggered from phone UI with no BT
- Audio captured at 16 kHz mono PCM, saved as timestamped per-source WAVs (`session_..._btmic.wav` / `_watch.wav` / `_phonemic.wav`)
- Phone UI: live recording status with source name, per-row play / smart-route / delete, BT devices card with active-audio detection, watch connection indicator
- Smart output router: earbuds (if connected) → watch (if reachable) → phone speaker
- Reverse channel `/arjun/tts` already in place for sending audio from phone back to watch
- Diagnostic logging exposes the actual SCO codec being negotiated (wideband vs narrowband)

**Phase 1 architecture summary:**
- Watch is purely a **trigger surface** when BT headset is present — phone does the recording
- Watch becomes the **audio source** only when no BT headset is connected
- Audio output routing operates independently of input source

### Phase 2 — Port the Gemma agent loop from POC

**Goal:** Plug the existing POC's AI stack into the audio bridge built in Phase 1. After this phase, pressing ▶ on the watch (or in the phone app) and speaking gets a real spoken AI response back through the BT headset / watch / phone.

**Approach:** **Use Gemma's native audio understanding directly** (no Whisper STT step) since the wideband SCO mic gives us true 16 kHz audio that Gemma 3n / Gemma 4 audio variants accept directly. This skips the Whisper port from the POC entirely — simpler pipeline, fewer moving parts.

Steps (port each from the POC at `C:\Users\shash\OneDrive\Desktop\Project-Arjun\reference-app\poc_android-call\poc_android`):

1. **Gemma audio model** — port `AgentModelEngine.kt` and the LiteRT-LM setup from the POC's `agentmode/`. Use the audio-capable Gemma model variant. The model receives 16 kHz mono PCM directly from `InputSourceManager`.
2. **VAD for turn boundaries** — port `VadSilero.kt` and `VadConfig.kt` from POC. VAD ends each user turn so we know when to send the buffered audio to Gemma.
3. **Agent mode tools** — port `AgentModeTools.kt` and the multi-step tool-calling logic from `AgentModeViewModel.kt`.
4. **Chat-mode tools fused in** — port `ChatModeTools.kt` and `ContactsHelper.kt` so calling/messaging tools are available in the same single mode.
5. **Function-calling Gemma** — port `FunctionGemmaEngine.kt` and `Actions.kt` for structured tool dispatch.
6. **TTS** — use Android's built-in `TextToSpeech` initially (POC pattern). TTS bytes go through the existing `WatchPlayer` and `OutputRouter` we already built — no work needed on the output side.
7. **Single fused mode** — unlike the POC's three separate modes (chat / mobile-actions / agent), Arjun-AI has **one mode**: agent's multi-step reasoning + chat's calling/messaging tools, all available in every session.

By the end of Phase 2: ▶ on watch → BT mic captures speech → Gemma reasons + calls tools → TTS speaks back through the BT headset. Same behavior as the POC's agent mode but with the watch + BT headset as the I/O surface.

### Phase 3 — Wake word + ambient assistant

**Goal:** Replace the explicit button press with a natural "Hey Arjun" trigger.

Steps:

1. **Wake-word detector** — small on-device keyword spotter (Porcupine, openWakeWord, or a tiny custom model) running continuously on the BT mic stream when the user has explicitly armed the assistant via the watch.
2. **5-second listening window** — after the trigger button is pressed (watch / phone), open a short window where the wake word is the gate: if heard, the audio that follows goes to Gemma; if not, the session aborts silently.
3. **False-trigger handling** — accidental button presses must not engage the LLM. The wake word is the second factor.
4. **Persistent sessions** — conversation history survives app restart, watch reboot, phone reboot.
5. **Long-term memory** — facts about the user (preferences, contacts, frequently-mentioned people/places) stored locally and surfaced as context for the LLM.
6. **Background availability** — assistant is reachable from the watch at any time, even with phone in pocket, app not in foreground.
7. **Settings UI** — voice selection, language, which tools are enabled, model selection, etc.
8. **Robustness** — graceful degradation when BT headset disconnects mid-session, audio buffering, retry logic.

## Non-goals (explicit)

- No cloud LLM calls
- No iOS support
- No support for non-Wear-OS watches (Galaxy Wearable / Tizen path is dead since Watch 4)
- Not a general-purpose dictation app

## Current status snapshot

| Component | Status |
|---|---|
| Watch trigger (▶/⏹) over `/arjun/trigger` | ✅ Working |
| Phone-side input source priority (BT → Watch → Phone) | ✅ Working |
| BT mic recording via SCO (wideband mSBC verified) | ✅ Working |
| Watch-mic streaming over `/arjun/audio` | ✅ Working |
| Phone-mic recording | ✅ Working |
| Per-session timestamped WAV files | ✅ Working |
| Recording playback list with smart routing | ✅ Working |
| Reverse audio (phone → watch) | ✅ Working |
| Smart output routing (earbuds → watch → phone) | ✅ Working |
| Connection indicator + BT devices card | ✅ Working |
| **Gemma audio model** | ❌ Phase 2 |
| **VAD + agent tool calls** | ❌ Phase 2 |
| **TTS routed via OutputRouter** | ❌ Phase 2 (transport ready, TTS engine missing) |
| **Wake-word + persistent memory** | ❌ Phase 3 |
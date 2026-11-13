# JARVIS — privacy-first Android assistant

A native Android assistant (Kotlin + Jetpack Compose) that talks to you in English, Hindi or
Hinglish, and actually operates the phone through a strict, locally validated command schema.

It ships with two clearly separated modes:

| Mode | Network | Voice | Device actions |
| --- | --- | --- | --- |
| **Gemini voice** | Audio streams to Google while a session is open | Gemini Live generated speech (`gemini-3.8-live`) | Same local schema, still policy-checked |
| **Local privacy** | None — no socket is ever opened | On-device recogniser (Android 12+) + local TTS | Offline parser, offline actions only |

Local privacy mode is the default on first launch. Gemini voice requires an explicit disclosure
and consent before it can be enabled.

## What is in this repository

```
app/                      Android application (single Gradle module, package-per-layer)
  src/main/java/com/jarvis/assistant/
    core/                 action schema, results, preferences model, DI module
    security/             JARVIS PRIVACY CORE, action parser, schema, Keystore crypto, rate limiter
    ai/                   Gemini Live websocket client, protocol, token client, offline parser, prompts
    voice/                AudioRecord/AudioTrack pipeline, resampler, session state machine, TTS
    device/               Android controllers (apps, settings, audio, brightness, alarms, contacts, notifications)
    automation/           WorkManager routines, boot recovery, foreground service
    memory/               encrypted memories and tasks
    database/             Room entities, DAOs, history and audit repositories
    permissions/          permission registry, rationale, dashboard
    assistant/            RoleManager + VoiceInteractionService/Session integration
    home/ features/ onboarding/ navigation/ ui/   Compose UI
  src/test/               81 JVM unit tests
  src/androidTest/        instrumented tests (compile verified; need a device to execute)
backend/                  Node + TypeScript token broker that holds the Gemini API key
docs/                     build, backend, permissions, compatibility, privacy, test report
artifacts/                built debug APK and signed release AAB
```

The requested `core/ ai/ voice/ security/ privacy/ database/ permissions/ features/ home/ device/
automation/ memory/ settings/ assistant/ backend/ tests/` separation is implemented as packages
inside one Gradle module, plus a separate `backend/` service and two test source sets. A single
module was chosen deliberately: with eleven library modules the annotation processors (Hilt + Room)
rebuild from scratch on any change, which is a real cost on small CI machines, and the package
boundaries already enforce the layering.

## Quick start

```bash
# Android
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk       # platform 35 + build-tools 35.0.0
./gradlew :app:assembleDebug                   # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest                # 81 JVM tests

# Backend
cd backend && npm install && npm test && npm run build
GEMINI_API_KEY=... JARVIS_SESSION_SECRET=$(openssl rand -hex 32) npm start
```

Full instructions: [docs/BUILD.md](docs/BUILD.md) and [docs/BACKEND.md](docs/BACKEND.md).

### No backend? Use your own Gemini key

You do not have to run the token broker. Open **Voice & Gemini → Your own Gemini API key**, tap
**Get a Gemini API key**, which opens `aistudio.google.com/apikey`, then paste the key and save it.
JARVIS encrypts it with the Android Keystore, stores it only in its private DataStore, and uses it
to open the Live socket directly (`?key=…`). The key is never logged, never echoed back to the
screen, and never sent to a JARVIS server. A permanent key on the device is weaker than a
30-minute ephemeral token, so the backend path stays the recommended one.

## Built artifacts

| File | What it is |
| --- | --- |
| `artifacts/jarvis-1.0.0-debug.apk` | Debug APK (`com.jarvis.assistant.debug`), installable with `adb install` |
| `artifacts/jarvis-1.0.0-release.aab` | Signed release bundle, upload with `bundletool` or Play Console |

The release bundle is signed with the generated upload key at `signing/jarvis-upload.jks`
(credentials in `signing/keystore.properties`, both git-ignored). Replace it with your own Play
upload key before publishing — see [docs/BUILD.md](docs/BUILD.md#signing).

## Where the trust boundaries are

1. **No API key shipped in the app.** Android asks the backend for a short-lived Gemini token, and
   the backend holds `GEMINI_API_KEY`. A user can instead paste their own key, which is encrypted
   with the Keystore on that device. Either way the repository and the APK contain no key: a scan
   of the built DEX returns 0 key-shaped strings.
2. **The model cannot touch Android APIs.** Gemini may only call the functions in
   `security/ActionSchema.kt`. Replies are re-parsed and re-validated; a `risk_level` sent by the
   model is ignored and recomputed locally.
3. **One door to the device.** Every path — voice, typed command, routine, UI button — goes through
   `device/ActionExecutor`, which always runs the privacy core first.
4. **Privacy core is testable.** `security/JarvisPrivacyCore.kt` has no Android dependencies, so its
   rules are covered by ordinary JVM tests.

Read [docs/PRIVACY.md](docs/PRIVACY.md) for the exact guarantees and the cloud disclosure, and
[docs/TEST_REPORT.md](docs/TEST_REPORT.md) for what was actually verified — including everything
that could **not** be verified in this environment.

## Honest capability summary

Working and unit-tested: the action schema and parser, the privacy firewall, secret detection,
rate limiting, the offline command parser, the Live protocol frames, the PCM resampler, the backend
token broker.

Implemented against Android APIs but **not executed here** (no device or emulator, no Gemini
credentials): voice conversation, audio playback and barge-in, app launching, alarms, reminders,
notification reading, media control, routines, assistant role. Those are marked `NOT RUN` in the
test report rather than claimed as passing.

Android does not allow an ordinary app to toggle Wi-Fi/Bluetooth/mobile data, send SMS silently,
place calls silently, read other apps' private data, install packages, or record the microphone in
the background without a foreground service. JARVIS does not pretend otherwise: it opens the
relevant system screen or refuses the request with a reason.

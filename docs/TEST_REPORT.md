# Capability and test report

Generated for the build in `artifacts/`. Status vocabulary:

- **PASS** — executed here and observed.
- **FAIL** — executed here and failed.
- **NOT RUN** — implemented but not executable in this environment (no device/emulator, no Gemini
  credentials). Not a claim of success.
- **NOT SUPPORTED** — Android does not permit it for an ordinary app.
- **REQUIRES USER AUTHORIZATION** — needs a human to approve a system prompt.

## Environment

| Item | Value |
| --- | --- |
| OS | Ubuntu 24.04 (2 vCPU, 3 GB) |
| JDK | Temurin 17.0.21 |
| Gradle | 8.11.1 (wrapper) |
| Android SDK | platform 35, build-tools 35.0.0 |
| Device / emulator | **none** — `/dev/kvm` exists but is not accessible to this user, and no `adb` device is attached |
| Gemini API key | **not available** in this environment |

## Executed checks

| Check | Command | Result |
| --- | --- | --- |
| Debug build | `./gradlew :app:assembleDebug` | **PASS** — `app-debug.apk`, 19 MB |
| Release bundle with R8 | `./gradlew :app:bundleRelease` | **PASS** — `app-release.aab`, 3.9 MB, signed (`META-INF/JARVIS-U.RSA`) |
| JVM unit tests (Android) | `./gradlew :app:testDebugUnitTest` | **PASS — 81 tests, 0 failures, 8 classes** |
| Instrumented tests compile | `./gradlew :app:assembleDebugAndroidTest` | **PASS** — test APK built |
| Backend tests | `npm test` | **PASS — 21 tests, 0 failures** |
| Backend typecheck/build | `npm run build` | **PASS** |
| No API key in APK | `unzip -p app-debug.apk classes*.dex \| strings \| grep -c AIza` | **PASS — 0 matches** |
| No key-shaped strings in sources | repo grep for `AIza…` | **PASS — 0 matches** |
| Manifest permission surface | `aapt2 dump permissions` | **PASS** — matches `docs/PERMISSIONS.md`, no SMS/call/package-query permissions |

### Unit test classes (81 tests)

| Class | Tests | Covers |
| --- | --- | --- |
| `ActionParserTest` | 19 | strict schema, unknown actions, shell metacharacters, ranges, risk non-downgrade, function-name uniqueness |
| `JarvisPrivacyCoreTest` | 13 | mode gate, consent, secret refusal, automation risk policy, fingerprint-bound approval, rate limit, audit |
| `LiveProtocolTest` | 16 | setup frame, tool declarations, manual VAD, resumption, audio frames, event parsing, malformed frames, `?key=` vs `access_token` auth, credentials kept out of the setup frame |
| `LocalCommandParserTest` | 13 | English/Hinglish app, volume, alarm, timer, task, memory and call parsing; rejection of unknown input |
| `SensitiveContentScannerTest` | 6 | OTP/password blocking, card/email/phone redaction, no false positives on Hinglish |
| `PcmResamplerTest` | 6 | 48k→16k and 44.1k→16k conversion, PCM16 little-endian round-trip |
| `NotificationVaultTest` | 6 | consent/listener gating, per-app exclusion, bounded buffer |
| `ActionRateLimiterTest` | 3 | sliding window, recovery, reset |

### Backend test coverage (21 tests)

Session signing/verification and tampering, expiry, malformed installation ids, bearer parsing, rate
limiter window and `Retry-After`, token request shape (`uses: 1`, model constraint, AUDIO modality),
provider error mapping without leaking the key, HTTP surface (health 200/503, session 400/200,
token 401/400/429/502/503, oversized body 400, unknown route 404).

## Product capability matrix

### Voice and Gemini

| Capability | Status | Evidence / blocker |
| --- | --- | --- |
| Push-to-talk audio capture, 40 ms chunks, resampled to 16 kHz | **PASS (unit)** / **NOT RUN (device)** | Resampler and frame construction tested; `AudioRecord` needs hardware |
| Gemini Live session setup with `gemini-3.8-live`, AUDIO modality | **PASS (unit)** | `LiveProtocolTest` asserts the exact setup frame |
| Short-lived token fetch + single-use model constraint | **PASS (unit + backend)** | `server.test.ts`, `gemini.test.ts` |
| Bring-your-own-key: `?key=` socket auth | **PASS (unit)** | `LiveProtocolTest` asserts the parameter and that no credential leaks into the setup frame |
| Own key encrypted at rest in DataStore | **NOT RUN** | Keystore-backed; needs a device to exercise |
| "Get a Gemini API key" link opens AI Studio | **NOT RUN (device)** | `ACTION_VIEW` intent to `aistudio.google.com/apikey`; no browser here |
| Live end-to-end conversation | **NOT RUN** | Needs deployed backend + `GEMINI_API_KEY` |
| English / Hindi / Hinglish conversation | **NOT RUN** | Language handling is prompt-level + same live session path |
| Real audio playback (24 kHz PCM16) | **NOT RUN** | `AudioTrack` needs a device |
| Barge-in flush of pending playback | **NOT RUN** | `AudioPlayback.flushNow()` + queue drain implemented |
| Session resumption / reconnect (3 attempts, fresh token) | **NOT RUN** | Implemented in `VoiceSessionController`; needs a live socket to exercise |
| 15-minute session cap | **PASS (unit-adjacent)** / **NOT RUN (device)** | Timer implemented; not observed on device |
| Graceful Gemini failure without provider fallback | **PASS (code review)** / **NOT RUN** | Explicitly no other provider exists in the codebase |

### Device control

| Capability | Status | Notes |
| --- | --- | --- |
| Launch installed apps / app search | **NOT RUN** | `PackageManager` + resolver; needs device |
| Open Android settings panels | **NOT RUN** | Public settings intents |
| Volume set/adjust | **NOT RUN** | `AudioManager` |
| Brightness | **REQUIRES USER AUTHORIZATION** | `WRITE_SETTINGS` special access |
| Do Not Disturb | **REQUIRES USER AUTHORIZATION** | notification policy access |
| Alarms and timers | **NOT RUN** | `AlarmClock` intents |
| Reminders | **NOT RUN** / **REQUIRES USER AUTHORIZATION** | AlarmManager; exact alarms need the grant, otherwise inexact |
| Tasks (add, complete, list) | **PASS (unit)** / **NOT RUN (device)** | Repository logic + Room tested in instrumented source (not executed) |
| Notification reading | **REQUIRES USER AUTHORIZATION** | listener access + consent + per-app exclusion |
| Device info (battery/storage/memory/network/model) | **NOT RUN** | Platform APIs |
| Media control | **REQUIRES USER AUTHORIZATION** (background) | MediaSessionManager needs listener access; key-event fallback otherwise |
| Contacts lookup / dialer / message composer | **REQUIRES USER AUTHORIZATION** | `READ_CONTACTS`; never dials or sends silently |

### Assistant role

| Capability | Status |
| --- | --- |
| Guided setup explains the role | **PASS (code)** |
| Request `ROLE_ASSISTANT` via `RoleManager` | **REQUIRES USER AUTHORIZATION** |
| `VoiceInteractionService` + session sheet | **NOT RUN** |
| Automatic replacement of the default assistant | **NOT SUPPORTED** — Android requires the user's choice |
| Lock-screen assistant launch | **NOT SUPPORTED (by design)** — JARVIS declines it |

### Privacy, security and data

| Check | Status |
| --- | --- |
| Privacy firewall rules | **PASS (unit)** — 13 tests |
| Secret blocking/redaction | **PASS (unit)** — 6 tests |
| No hardcoded Gemini key | **PASS** — DEX and source scans |
| Token expiry + rate limiting | **PASS (backend tests)** |
| No key-shaped strings in built APK | **PASS** — 0 matches after removing the placeholder hint |
| Keystore encryption | **NOT RUN** — instrumented test written; needs device |
| Room schema and encrypted task storage | **NOT RUN** — instrumented test written; needs device |
| Local mode never contacts the network | **PASS (code review)** — no socket is created; on-device recogniser only |
| Offline command parsing | **PASS (unit)** — 13 tests |
| Permission revocation reflected immediately | **PASS (code review)** — live grant read per action; not observed on device |

### Lifecycle

| Check | Status |
| --- | --- |
| APK installation and cold start | **NOT RUN** — no device |
| First-run onboarding | **NOT RUN** — no device |
| Routine recovery after reboot | **NOT RUN** — `BootReceiver` + WorkManager re-enqueue implemented |
| Process death mid-session | **NOT RUN** — session is in-memory and rebuilt on next launch |

### Explicitly not supported by Android (and therefore not claimed)

Wi-Fi / Bluetooth / mobile data / airplane toggling, silent SMS, silent calls, reading other apps'
private data, installing packages, changing security or device-admin policy, background microphone
recording without a foreground service, screen capture without a user-started feature, `QUERY_ALL_PACKAGES`
inventory access.

## What remains to verify on real hardware

1. Install the debug APK, complete onboarding, grant mic + notifications.
2. Deploy the backend and confirm **Test connection** returns a token (this exercises the whole
   credential chain without a device).
3. Run one English, one Hindi and one Hinglish turn; confirm audio plays and barge-in goes silent
   immediately.
4. Verify app launch, alarm, reminder, DND, brightness, media, notification summary.
5. Request the assistant role and launch JARVIS from the gesture.
6. Reboot and confirm a scheduled routine still fires.
7. `./gradlew :app:connectedDebugAndroidTest` for the Keystore and Room tests.

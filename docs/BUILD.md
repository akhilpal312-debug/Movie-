# Building JARVIS

## Requirements

| Tool | Version used here | Notes |
| --- | --- | --- |
| JDK | Temurin 17 (17.0.21) | AGP 8.7 requires 17+ |
| Android SDK | platform 35, build-tools 35.0.0, platform-tools | `compileSdk`/`targetSdk` 35 |
| Gradle | 8.11.1 (wrapper included) | no global install needed |
| Node.js | 20+ (26 used here) | backend only |
| Kotlin | 2.0.21 | pinned in `gradle/libs.versions.toml` |

`minSdk` is 26. Features that need newer Android are gated at runtime: RoleManager assistant role
needs 29+, on-device speech recognition needs 31+, exact alarms need 31+.

## Configure the SDK

Create `local.properties` in the project root (git-ignored):

```properties
sdk.dir=/absolute/path/to/android-sdk
```

or export `ANDROID_HOME` and let Gradle pick it up.

## Build commands

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # JVM unit tests (79)
./gradlew :app:assembleDebugAndroidTest   # compiles instrumented tests
./gradlew :app:bundleRelease          # signed release AAB (needs signing config)
./gradlew lint                        # Android Lint
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/bundle/release/app-release.aab`

## Signing

Release signing is read from Gradle properties or environment variables, never from the repository:

```bash
export JARVIS_UPLOAD_KEYSTORE=/path/to/upload.jks
export JARVIS_UPLOAD_STORE_PASSWORD=...
export JARVIS_UPLOAD_KEY_ALIAS=upload
export JARVIS_UPLOAD_KEY_PASSWORD=...
./gradlew :app:bundleRelease
```

Equivalent `-Pjarvis.storeFile=... -Pjarvis.storePassword=... -Pjarvis.keyAlias=... -Pjarvis.keyPassword=...`
flags also work.

This repository contains a **generated upload key** used to produce the delivered AAB:

```
signing/jarvis-upload.jks        (git-ignored)
signing/keystore.properties      (git-ignored, mode 600)
```

Replace both with your own Play Console upload key before publishing. If you lose the key, the app
can never be updated under the same package name.

## Package names

| Build | applicationId |
| --- | --- |
| debug | `com.jarvis.assistant.debug` |
| release | `com.jarvis.assistant` |

Both can be installed side by side.

## Running without the backend

If you do not want to deploy the token broker, you can paste your own Gemini API key in
**Voice & Gemini → Your own Gemini API key** (there is a button there that opens
`aistudio.google.com/apikey`). The key is encrypted with the Android Keystore and used to connect
directly to the Live API. This is weaker than ephemeral tokens — see `docs/PRIVACY.md` — but it makes
voice work on a fresh install with no server.

## Backend address

The debug build defaults to `http://10.0.2.2:8787` (host loopback from an emulator). On a physical
device, set the backend URL in **Voice & Gemini → Token backend**. Android 9+ blocks cleartext HTTP;
use an HTTPS endpoint (recommended, see `docs/BACKEND.md`) or a Network Security Config for local
development.

## No secrets in the build

`GEMINI_API_KEY` is never read by Gradle and never compiled into the APK. The only credentials the
app handles are the short-lived session token and Gemini ephemeral token returned by the backend at
runtime.

# Privacy architecture

## The one cloud boundary

Gemini Live is a cloud API. **In Gemini voice mode, microphone audio you deliberately send while a
session is open is processed on Google's servers**, and the spoken reply is generated there. JARVIS
says this before enabling the mode and records consent with a timestamp. Local Privacy mode never
opens a network connection.

Nothing else leaves the device. The app never attaches files, photos, documents, messages,
notifications, contacts, call history, clipboard, location, installed-app inventory or database
contents to a Gemini request. The system prompt reinforces this, and the local layer enforces it
independently.

## The privacy core (`security/JarvisPrivacyCore.kt`)

Every action — from Gemini, from the offline parser, from a routine or from a UI button — passes
through `authorize()` before an Android API is touched. Checks run in this order:

1. **Mode gate** — a cloud-originated action is refused outright while in Local mode.
2. **Consent** — notification reading, contact lookups and secrets each need an explicit JARVIS-level
   consent in addition to the Android grant.
3. **Secret detection** — one-time codes, passwords, PINs, card and ID numbers are blocked (and
   redacted on the way out). See `SensitiveContentScanner`.
4. **Automation policy** — unattended runs may only perform LOW-risk work; MEDIUM only if the user
   enabled it; HIGH never.
5. **Permission gate** — live Android grant state, re-read each time.
6. **Risk gate** — HIGH always needs approval, bound to a SHA-256 fingerprint of the exact action, so
   approving one action cannot run a different one.
7. **Rate limit** — sliding window (default 20/min) against runaway loops.

Every decision is written to a local audit table and shown in the Privacy screen.

## The ten rules, and how each is enforced

| Rule | Enforcement |
| --- | --- |
| No device content uploaded | No action carries device content; outgoing text passes the secret scanner; the system prompt forbids requesting it |
| Clipboard never read | The app contains no clipboard API usage at all |
| No screenshots without a user-started feature | No screen-capture API is used; `FLAG_SECURE`-style capture does not exist in the codebase |
| No hidden microphone recording | Audio starts only inside a press/release pair; `AudioCapture.stop()` always releases the recorder; no background mic service exists |
| No hidden monitoring | Only an opt-in notification listener, visible in the Permissions screen and in system settings |
| Contacts stay local | `ContactsController` queries only the name you asked about, returns it to the executor, and is never called with a bulk request |
| No raw voice recordings | Audio is streamed in memory and never written to disk; there is no recorder file anywhere |
| No automatic location sharing | Location is never read; the only location action is opening the settings panel |
| User policy wins | JARVIS consents and the automation risk policy are checked before Android grants |
| Permanent Stop control | A visible Stop button in the home hero, the assistant sheet, and `endSession()` which closes the socket and clears buffers |

## Secrets and credentials

### Two ways to authenticate Gemini

1. **Backend broker (recommended, default).** The phone never holds a long-lived credential; it
   receives a single-use ephemeral token that expires in 30 minutes and is bound to one session.
2. **Your own key (opt-in).** *Voice & Gemini → Your own Gemini API key* lets the user paste a
   personal key instead of running the broker. That key is encrypted with the Android Keystore,
   stored only in the app's private DataStore, decrypted only for the moment a socket is opened,
   never displayed again after saving, never logged, and never sent anywhere except Google's Live
   API endpoint (`wss://generativelanguage.googleapis.com/...?key=…`). It is not included in the
   setup frame, so a crash log of that frame cannot reveal it.

The trade-off is stated in the UI: a permanent key on the device is weaker than a 30-minute
ephemeral token, because it can be reused if the device is compromised. The broker remains the
stronger option and is what the app uses until the user explicitly switches.

### Repository and binary checks

- No Gemini key in the app, Gradle files or repo. Verified by scanning the built DEX for `AIza`
  (0 matches) and scanning sources for key-shaped strings (0 matches).
- The backend holds `GEMINI_API_KEY` and issues single-use ephemeral tokens (`uses: 1`, 30-minute
  expiry) plus HMAC-signed 15-minute app sessions.
- The app's installation identifier is a random UUID, not `ANDROID_ID` or a hardware serial.
- Logs: the broker logs method/path/status only. The app never logs transcripts or audio.

## Local data at rest

- Memories, task titles and routine steps are AES-256-GCM encrypted with a key that never leaves the
  Android Keystore.
- Notification contents live in a bounded in-memory buffer only, cleared when a session ends and
  filterable per app.
- History and audit tables hold action metadata and short non-sensitive summaries; drafted message
  bodies are deliberately not logged.
- `allowBackup=false` and extraction rules exclude everything, so private data is not copied into
  cloud backups or device transfers.
- The user can edit or delete any memory, clear the audit log, clear history, and end a session with
  buffers cleared.

## Failure behaviour

If Gemini is unreachable, the app says which step failed (token, socket, setup, quota), retries at
most three times with a fresh token and session resumption, then stops and points at Local Privacy
mode. It never silently switches to another provider, and never falls back from Local mode to the
cloud.

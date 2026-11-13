# Device compatibility

## Build targets

| Setting | Value |
| --- | --- |
| `minSdk` | 26 (Android 8.0) |
| `targetSdk` / `compileSdk` | 35 (Android 15) |
| Kotlin | 2.0.21 |
| ABI | universal (no native libraries) |

No native code is shipped, so every ABI works.

## Feature availability by Android version

| Feature | Requires | Behaviour on older versions |
| --- | --- | --- |
| Core app, Compose UI, Room, alarms, timers, device info, volume, tasks | 26 | Works |
| Exact reminders | 31 for `canScheduleExactAlarms` | Below 31 exact alarms are always permitted |
| Assistant role (`ROLE_ASSISTANT`) | 29 | On 26–28 the button opens the system assistant settings instead |
| Notification reading | 21 (listener) + user grant | Works; needs the separate access screen |
| Per-app notification exclusion | 26 | Works |
| DND switching | 23 + policy access | Works with access |
| Brightness control | 23 + `WRITE_SETTINGS` | Works with access |
| Media transport control | 21 + notification access | Without access JARVIS falls back to a media key event and reports if it cannot verify |
| **Local mode voice input** | **31** (on-device recogniser) | Below 31 or without an on-device recogniser, local voice input is refused with an explanation; typed commands and local TTS still work |
| Gemini voice mode | Any version + network + backend | Identical on all versions |

## OEM notes

- **Assistant gesture** placement differs (long-press home, power-button, corner swipe). Android's
  own role screen decides; JARVIS only requests the role.
- **Battery optimisation** is aggressive on several OEM skins. Granting the exemption improves
  routine reliability; WorkManager still defers work when the device is idle.
- **Autostart** lists (MIUI/EMUI/ColorOS) are vendor-specific and cannot be controlled by an app.
- The **clock app** must handle `AlarmClock.ACTION_SET_ALARM` / `ACTION_SET_TIMER`; virtually all
  devices do, and JARVIS reports "no clock app" if not.
- The **media session** may not expose transport controls; JARVIS reports that instead of pretending.

## Audio notes

- Capture prefers 16 kHz mono PCM16 (`VOICE_COMMUNICATION`), falling back to 44.1/48 kHz and
  resampling to 16 kHz. Chunks are 40 ms.
- Playback is 24 kHz mono PCM16 with `USAGE_ASSISTANT`, flushed immediately on barge-in.
- A Bluetooth headset in a call-style route may reduce capture quality; this is a device limitation,
  not something the app can override.

## Known limitations (not bugs)

- Wi-Fi, Bluetooth, mobile data, airplane mode and security settings cannot be toggled by an
  ordinary app. JARVIS opens the relevant panel and says so.
- Notifications cannot be read before the user grants the special access.
- On-device speech recognition depends on the device's speech service; some devices ship none.
- Background microphone use is not offered at all; the mic opens only for a foreground,
  user-initiated turn.

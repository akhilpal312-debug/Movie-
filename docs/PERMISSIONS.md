# Permissions

JARVIS asks for the minimum it needs, only when a feature needs it, and keeps a dashboard where
each grant can be inspected and revoked. Android owns every grant: the app can open the right system
screen, but never bypasses a confirmation.

## Runtime permissions

| Permission | Asked when | Used for | If denied |
| --- | --- | --- | --- |
| `RECORD_AUDIO` | First mic press, or onboarding | Push-to-talk audio for Gemini voice / on-device STT | Voice input unavailable; typed commands still work |
| `POST_NOTIFICATIONS` (33+) | Onboarding or first reminder | Ongoing listening indicator, reminders, routine progress | No notifications; everything else works |
| `READ_CONTACTS` | First call/message request, or dashboard | Resolving a name you asked about, at that moment | Call/message commands are refused with a reason |

## Special access (separate system screens)

| Access | Why JARVIS needs it | What it does **not** grant | If denied |
| --- | --- | --- | --- |
| `WRITE_SETTINGS` | Set screen brightness from a command | Not files, not other apps' settings | Brightness refused; the Display settings panel still opens |
| `ACCESS_NOTIFICATION_POLICY` | Turn Do Not Disturb on/off | No notification content access | DND commands refused |
| Notification listener | Read recent notifications, control media | Not other apps' data, not network access | Notification summaries and background media control unavailable |
| `SCHEDULE_EXACT_ALARM` (31+) | Deliver reminders at the exact minute | Not background execution exemption | Reminder is scheduled inexactly; the app says so |
| `ROLE_ASSISTANT` | Be the system assistant (gesture/long-press) | Not a replacement for Google Assistant until the user confirms | App still works when opened normally |
| Battery optimisation exemption | Keep daily routines reliable | No extra data access | Routines may run late |

`QUERY_ALL_PACKAGES` is **not** requested. Launcher apps are visible through a `<queries>` intent for
`MAIN`/`LAUNCHER`, which is what the app search needs.

## JARVIS-level consents (additional to Android)

These are stored in DataStore and enforced by the privacy core, independent of the Android grant:

- **Gemini voice consent** — required before any cloud audio; recorded with a timestamp.
- **Notification reading consent** — required even if listener access is granted.
- **Per-app notification exclusion** — any app can be switched off; its notifications are dropped
  before they reach the in-memory buffer.
- **Contact lookup consent** — required for call/message actions.
- **Medium-risk automation** — off by default; unattended routines may then change settings.
- **Action rate limit** — 1–60 actions per minute, default 20.

Revoking an Android permission takes effect on the next action because `AndroidPermissionGate` reads
the live grant state every time; nothing is cached.

## What is deliberately not requested

`SEND_SMS`, `CALL_PHONE`, `READ_SMS`, `READ_CALL_LOG`, `SYSTEM_ALERT_WINDOW`, `READ_EXTERNAL_STORAGE`,
`MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `BIND_ACCESSIBILITY_SERVICE`, `PACKAGE_USAGE_STATS`,
`REQUEST_INSTALL_PACKAGES`. JARVIS neither needs nor wants them: calls and messages are handed to
your own dialer/SMS app with `ACTION_DIAL` / `ACTION_SENDTO`, so nothing is sent or dialled silently.

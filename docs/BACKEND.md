# JARVIS token broker

A small Node + TypeScript service whose only job is to keep the Gemini API key off the phone.

```
Android app ──POST /api/session──────────────▶ broker ──▶ HMAC session token (15 min)
Android app ──POST /api/gemini/live-token────▶ broker ──▶ Gemini auth_tokens (single use, 30 min)
                                                            │
Android app ──WSS BidiGenerateContent─────────────▶ Gemini Live API (ephemeral token)
```

No audio, device content or transcript passes through the broker. It never logs request bodies.

## Endpoints

### `GET /health`

`200` when a Gemini key and a stable session secret are configured, `503` otherwise.
Reports the model and token TTL; never the key.

### `POST /api/session`

```json
{ "installationId": "uuid-v4", "client": "jarvis-android" }
```

→ `{ "sessionToken": "<payload>.<hmac>", "expiresInSeconds": 900 }`

`installationId` is a random identifier the app generates on first launch — not a hardware ID, not
`ANDROID_ID`. It is validated against `^[a-z0-9][a-z0-9-]{15,63}$` and signed into the token, so a
forged or malformed value is rejected.

### `POST /api/gemini/live-token`

Headers: `Authorization: Bearer <sessionToken>`

```json
{ "model": "gemini-3.8-live", "purpose": "live-voice" }
```

→ `{ "token": "authtokens/...", "model": "gemini-3.8-live", "expiresAt": "2026-11-13T…Z" }`

The broker calls Gemini's `v1alpha/auth_tokens` provisioning endpoint with:

```json
{
  "uses": 1,
  "expireTime": "<now + TTL>",
  "newSessionExpireTime": "<now + 2 min>",
  "liveConnectConstraints": {
    "model": "models/gemini-3.8-live",
    "config": { "responseModalities": ["AUDIO"] }
  }
}
```

`uses: 1` means the token starts exactly one Live session. The Android client passes it to the
websocket as `?access_token=…`, exactly as the Live API expects from an ephemeral token.

Errors: `401` invalid/expired session, `400` malformed body or unsupported model, `429` rate limit
(with `Retry-After`), `502` provider failure, `503` Gemini not configured. Provider error bodies are
never echoed, because they can contain account details.

## Configuration

| Variable | Required | Meaning |
| --- | --- | --- |
| `GEMINI_API_KEY` | yes | Server-side key. Never shipped to clients. |
| `JARVIS_SESSION_SECRET` | yes in production | ≥32 chars, used to sign session tokens |
| `PORT` | no (8787) | Listen port |
| `HOST` | no (0.0.0.0) | Bind address; prefer 127.0.0.1 behind a proxy |
| `JARVIS_GEMINI_MODEL` | no | Default model, `gemini-3.8-live` |
| `JARVIS_TOKEN_TTL_SECONDS` | no (1800) | Ephemeral token lifetime |
| `JARVIS_SESSION_TTL_SECONDS` | no (900) | App session lifetime |
| `JARVIS_RATE_LIMIT` | no (12) | Token mints per installation per minute |
| `GEMINI_BASE_URL` | no | Override for a proxy or staging endpoint |

If `JARVIS_SESSION_SECRET` is missing, a random per-process secret is generated and a warning is
printed: everything keeps working, but existing sessions become invalid after a restart.

## Run locally

```bash
cd backend
npm install
npm test            # 21 tests: session signing, rate limiting, provider mapping, HTTP surface
JARVIS_SESSION_SECRET=$(openssl rand -hex 32) \
GEMINI_API_KEY=your-key \
npm start
```

## Deploy

Any Node 20+ host works. A minimal systemd unit:

```ini
[Unit]
Description=JARVIS token broker
After=network-online.target

[Service]
WorkingDirectory=/srv/jarvis/backend
ExecStart=/usr/bin/node dist/src/server.js
EnvironmentFile=/etc/jarvis/env
Restart=always
User=jarvis
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict

[Install]
WantedBy=multi-user.target
```

`/etc/jarvis/env` (mode 600, owned by `jarvis`):

```
GEMINI_API_KEY=...
JARVIS_SESSION_SECRET=...        # openssl rand -hex 32
HOST=127.0.0.1
PORT=8787
```

Put TLS in front (Caddy/nginx) so the app talks HTTPS:

```
jarvis.example.com {
    reverse_proxy 127.0.0.1:8787
}
```

Then set `https://jarvis.example.com` in the app under **Voice & Gemini → Token backend**.

Container alternative: `node:22-alpine`, copy `dist/` and `package.json`, run
`node dist/src/server.js` as a non-root user.

## Operational rules

- Never log `Authorization` headers, tokens or request bodies. The server only logs
  `METHOD /path status`.
- Rotate `GEMINI_API_KEY` in the host environment; the app needs no update.
- The broker is deliberately dumb: no accounts, no user database, no audio. A compromise limits
  abuse but exposes no conversation data.
- Watch `429`s and `provider status 403` — they mean quota exhaustion or a revoked key.
- `liveConnectConstraints` pins the model server-side, so a modified client cannot request an
  arbitrary model with your key.

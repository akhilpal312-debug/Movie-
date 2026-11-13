# JARVIS token broker

Short-lived Gemini Live token broker for the JARVIS Android app. Keeps `GEMINI_API_KEY` server-side
and issues single-use, 30-minute tokens plus short-lived app sessions.

- Full API and deployment guide: [`../docs/BACKEND.md`](../docs/BACKEND.md)
- Config template: [`.env.example`](.env.example)

```bash
npm install
npm test        # 21 tests
npm run build
JARVIS_SESSION_SECRET=$(openssl rand -hex 32) GEMINI_API_KEY=... npm start
```

Never put a Gemini key in the Android project, a Gradle property, or a committed file.

import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { assertModelAllowed, isConfigured, loadConfig, usesGeneratedSessionSecret, type Config } from "./config.js";
import { GeminiTokenError, mintLiveToken, type MintedToken } from "./gemini.js";
import { RateLimiter } from "./rateLimit.js";
import { bearerToken, issueSession, isValidInstallationId, verifySession } from "./session.js";

const MAX_BODY_BYTES = 8 * 1024;

export interface ServerDeps {
  readonly mint?: (input: { apiKey: string; model: string; ttlSeconds: number }) => Promise<MintedToken>;
  readonly now?: () => number;
  readonly log?: (line: string) => void;
}

interface JsonBody {
  [key: string]: unknown;
}

function sendJson(response: ServerResponse, status: number, body: unknown, headers: Record<string, string> = {}): void {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(payload),
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    ...headers,
  });
  response.end(payload);
}

async function readJson(request: IncomingMessage): Promise<JsonBody | null> {
  let size = 0;
  const chunks: Buffer[] = [];
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    size += buffer.byteLength;
    if (size > MAX_BODY_BYTES) return null;
    chunks.push(buffer);
  }
  if (size === 0) return {};
  try {
    const parsed = JSON.parse(Buffer.concat(chunks).toString("utf8")) as unknown;
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? (parsed as JsonBody) : null;
  } catch {
    return null;
  }
}

/**
 * The JARVIS token broker.
 *
 * Two endpoints only:
 *   POST /api/session              → short-lived app session token
 *   POST /api/gemini/live-token    → short-lived Gemini Live token, rate limited
 *
 * The Gemini API key exists only in this process's environment. Request bodies are
 * never logged, and no audio ever passes through this server.
 */
export function createJarvisServer(config: Config, deps: ServerDeps = {}): Server {
  const now = deps.now ?? Date.now;
  const log = deps.log ?? ((line: string) => console.log(line));
  const limiter = new RateLimiter(config.requestsPerMinute, now);
  const mint = deps.mint ?? ((input: { apiKey: string; model: string; ttlSeconds: number }) =>
    mintLiveToken({ ...input, baseUrl: config.geminiBaseUrl }));

  return createServer(async (request, response) => {
    const method = request.method ?? "GET";
    const url = new URL(request.url ?? "/", "http://localhost");
    const path = url.pathname;

    // Log the shape of the request only. Never the body, never a token.
    const finish = (status: number) => log(`${method} ${path} ${status}`);

    if (method === "GET" && path === "/health") {
      const healthy = isConfigured(config) && Boolean(config.geminiApiKey);
      sendJson(response, healthy ? 200 : 503, {
        status: healthy ? "ok" : "misconfigured",
        model: config.defaultModel,
        tokenTtlSeconds: config.liveTokenTtlSeconds,
        sessions: "hmac",
        generatedSessionSecret: usesGeneratedSessionSecret(),
      });
      return finish(healthy ? 200 : 503);
    }

    if (method === "POST" && path === "/api/session") {
      const body = await readJson(request);
      if (!body) {
        sendJson(response, 400, { error: "Malformed request body" });
        return finish(400);
      }
      const installationId = body.installationId;
      if (!isValidInstallationId(installationId)) {
        sendJson(response, 400, { error: "A valid installationId is required" });
        return finish(400);
      }
      if (!limiter.tryConsume(`session:${installationId}`)) {
        const retryAfter = String(limiter.retryAfterSeconds(`session:${installationId}`));
        sendJson(response, 429, { error: "Too many sessions" }, { "retry-after": retryAfter });
        return finish(429);
      }
      const session = issueSession(installationId, config.sessionSecret, config.sessionTtlSeconds, now());
      sendJson(response, 200, {
        sessionToken: session.token,
        expiresInSeconds: session.expiresInSeconds,
      });
      return finish(200);
    }

    if (method === "POST" && path === "/api/gemini/live-token") {
      const claims = verifySession(bearerToken(request.headers.authorization), config.sessionSecret, now());
      if (!claims) {
        sendJson(response, 401, { error: "A valid app session is required" });
        return finish(401);
      }

      if (!config.geminiApiKey) {
        sendJson(response, 503, { error: "Gemini is not configured on this server" });
        return finish(503);
      }

      const body = await readJson(request);
      if (!body) {
        sendJson(response, 400, { error: "Malformed request body" });
        return finish(400);
      }

      const requestedModel = typeof body.model === "string" && body.model.trim()
        ? body.model.trim().replace(/^models\//, "")
        : config.defaultModel;

      if (!assertModelAllowed(config, requestedModel)) {
        sendJson(response, 400, { error: "Unsupported model" });
        return finish(400);
      }

      if (!limiter.tryConsume(`token:${claims.sub}`)) {
        const retryAfter = String(limiter.retryAfterSeconds(`token:${claims.sub}`));
        sendJson(response, 429, { error: "Rate limit reached for this device" }, { "retry-after": retryAfter });
        return finish(429);
      }

      try {
        const token = await mint({
          apiKey: config.geminiApiKey,
          model: requestedModel,
          ttlSeconds: config.liveTokenTtlSeconds,
        });
        sendJson(response, 200, {
          token: token.token,
          model: token.model,
          expiresAt: token.expiresAt,
        });
        return finish(200);
      } catch (error) {
        // Only our own broker errors are safe to return. An unexpected exception
        // could carry provider internals, so it is replaced with a generic message.
        const status = error instanceof GeminiTokenError ? error.status : 502;
        const message = error instanceof GeminiTokenError
          ? error.message
          : "Token provisioning failed";
        sendJson(response, status, { error: message });
        return finish(status);
      }
    }

    sendJson(response, 404, { error: "Not found" });
    return finish(404);
  });
}

// Only start listening when executed directly (tests import createJarvisServer).
const isDirectRun = process.argv[1] && import.meta.url === `file://${process.argv[1]}`;
if (isDirectRun) {
  const config = loadConfig();
  const server = createJarvisServer(config);
  server.listen(config.port, config.host, () => {
    console.log(`JARVIS token broker listening on http://${config.host}:${config.port}`);
    console.log(`Model: ${config.defaultModel} · token TTL ${config.liveTokenTtlSeconds}s`);
    if (!config.geminiApiKey) {
      console.error("GEMINI_API_KEY is missing: /api/gemini/live-token will answer 503.");
    }
    if (usesGeneratedSessionSecret()) {
      console.warn(
        "JARVIS_SESSION_SECRET is not set (min 32 chars). A random secret was generated for this process; " +
          "sessions will not survive a restart.",
      );
    }
  });
}

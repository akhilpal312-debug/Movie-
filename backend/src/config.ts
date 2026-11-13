import { randomBytes } from "node:crypto";

export interface Config {
  readonly port: number;
  readonly host: string;
  /** Server-side Gemini API key. Never returned to a client, never logged. */
  readonly geminiApiKey: string | undefined;
  /** Secret used to sign app session tokens. */
  readonly sessionSecret: string;
  readonly geminiBaseUrl: string;
  readonly defaultModel: string;
  readonly allowedModelPattern: RegExp;
  readonly liveTokenTtlSeconds: number;
  readonly sessionTtlSeconds: number;
  readonly requestsPerMinute: number;
  readonly publicBaseUrl: string;
}

const DEFAULT_MODEL = "gemini-3.8-live";
const MODEL_PATTERN = /^(?:models\/)?[a-z0-9][a-z0-9.\-]{2,63}$/i;

function intFrom(name: string, fallback: number, env: NodeJS.ProcessEnv): number {
  const raw = env[name];
  if (!raw) return fallback;
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const sessionSecret = env.JARVIS_SESSION_SECRET?.trim();

  return {
    port: intFrom("PORT", 8787, env),
    host: env.HOST?.trim() || "0.0.0.0",
    geminiApiKey: env.GEMINI_API_KEY?.trim() || undefined,
    // A random per-process secret keeps local development working without a
    // committed default. Production must set JARVIS_SESSION_SECRET so sessions
    // survive restarts and cannot be forged.
    sessionSecret: sessionSecret && sessionSecret.length >= 32
      ? sessionSecret
      : randomBytes(32).toString("hex"),
    geminiBaseUrl: env.GEMINI_BASE_URL?.trim() || "https://generativelanguage.googleapis.com",
    defaultModel: env.JARVIS_GEMINI_MODEL?.trim() || DEFAULT_MODEL,
    allowedModelPattern: MODEL_PATTERN,
    liveTokenTtlSeconds: intFrom("JARVIS_TOKEN_TTL_SECONDS", 1800, env),
    sessionTtlSeconds: intFrom("JARVIS_SESSION_TTL_SECONDS", 900, env),
    requestsPerMinute: intFrom("JARVIS_RATE_LIMIT", 12, env),
    publicBaseUrl: env.JARVIS_PUBLIC_BASE_URL?.trim() || "",
  };
}

/** True when the process can actually mint tokens. */
export function isConfigured(config: Config): boolean {
  return Boolean(config.geminiApiKey) && config.sessionSecret.length >= 32;
}

export function usesGeneratedSessionSecret(env: NodeJS.ProcessEnv = process.env): boolean {
  const secret = env.JARVIS_SESSION_SECRET?.trim();
  return !secret || secret.length < 32;
}

export function assertModelAllowed(config: Config, model: string): boolean {
  return config.allowedModelPattern.test(model);
}

export { DEFAULT_MODEL, MODEL_PATTERN };

import { createHmac, timingSafeEqual } from "node:crypto";

export interface SessionClaims {
  /** Installation identifier created by the app on first launch. Not a hardware id. */
  readonly sub: string;
  readonly iat: number;
  readonly exp: number;
}

const INSTALLATION_ID = /^[a-z0-9][a-z0-9-]{15,63}$/;

export function isValidInstallationId(value: unknown): value is string {
  return typeof value === "string" && INSTALLATION_ID.test(value);
}

function sign(payload: string, secret: string): string {
  return createHmac("sha256", secret).update(payload).digest("base64url");
}

export function issueSession(
  installationId: string,
  secret: string,
  ttlSeconds: number,
  nowMillis: number = Date.now(),
): { token: string; expiresInSeconds: number } {
  if (!isValidInstallationId(installationId)) {
    throw new Error("Invalid installation id");
  }
  const iat = Math.floor(nowMillis / 1000);
  const claims: SessionClaims = { sub: installationId, iat, exp: iat + ttlSeconds };
  const payload = Buffer.from(JSON.stringify(claims), "utf8").toString("base64url");
  const token = `${payload}.${sign(payload, secret)}`;
  return { token, expiresInSeconds: ttlSeconds };
}

/** Returns the claims only when the signature, format and expiry all check out. */
export function verifySession(
  token: string | undefined,
  secret: string,
  nowMillis: number = Date.now(),
): SessionClaims | null {
  if (!token) return null;
  const parts = token.split(".");
  if (parts.length !== 2) return null;
  const [payload, signature] = parts;
  if (!payload || !signature) return null;

  const expected = sign(payload, secret);
  const provided = Buffer.from(signature);
  const computed = Buffer.from(expected);
  if (provided.length !== computed.length) return null;
  if (!timingSafeEqual(provided, computed)) return null;

  let claims: SessionClaims;
  try {
    claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as SessionClaims;
  } catch {
    return null;
  }
  if (!isValidInstallationId(claims.sub)) return null;
  if (typeof claims.exp !== "number" || typeof claims.iat !== "number") return null;
  if (claims.exp * 1000 <= nowMillis) return null;
  return claims;
}

export function bearerToken(header: string | undefined): string | undefined {
  if (!header) return undefined;
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match?.[1]?.trim() || undefined;
}

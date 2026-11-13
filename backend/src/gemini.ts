/**
 * Gemini ephemeral token broker.
 *
 * Google's `auth_tokens` provisioning endpoint issues a short-lived credential that a
 * client may use in place of an API key for a single Live session. That is what keeps
 * the permanent key off the phone.
 *
 * Docs: https://ai.google.dev/gemini-api/docs/ephemeral-tokens
 */

export interface MintRequest {
  readonly apiKey: string;
  readonly model: string;
  readonly ttlSeconds: number;
  readonly baseUrl?: string;
  readonly fetchImpl?: typeof fetch;
  readonly now?: () => number;
}

export interface MintedToken {
  readonly token: string;
  readonly model: string;
  readonly expiresAt: string;
}

export class GeminiTokenError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly providerStatus?: number,
  ) {
    super(message);
    this.name = "GeminiTokenError";
  }
}

export async function mintLiveToken(request: MintRequest): Promise<MintedToken> {
  const doFetch = request.fetchImpl ?? fetch;
  const now = request.now ?? Date.now;
  const baseUrl = (request.baseUrl ?? "https://generativelanguage.googleapis.com").replace(/\/$/, "");

  const expireTime = new Date(now() + request.ttlSeconds * 1000).toISOString();
  // The client must open the socket soon after the token is issued.
  const newSessionExpireTime = new Date(now() + 2 * 60 * 1000).toISOString();

  const body = {
    uses: 1,
    expireTime,
    newSessionExpireTime,
    liveConnectConstraints: {
      model: request.model.startsWith("models/") ? request.model : `models/${request.model}`,
      config: {
        responseModalities: ["AUDIO"],
      },
    },
  };

  const response = await doFetch(
    `${baseUrl}/v1alpha/auth_tokens?key=${encodeURIComponent(request.apiKey)}`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    },
  );

  if (!response.ok) {
    // Never echo the provider body: it can contain account details.
    throw new GeminiTokenError(
      response.status === 401 || response.status === 403
        ? "The configured Gemini API key was rejected."
        : response.status === 429
          ? "Gemini quota is exhausted for this key."
          : `Gemini token provisioning failed (provider status ${response.status}).`,
      502,
      response.status,
    );
  }

  const payload = (await response.json()) as { name?: string };
  const token = payload.name?.trim();
  if (!token) {
    throw new GeminiTokenError("Gemini returned no ephemeral token.", 502);
  }

  return { token, model: request.model.replace(/^models\//, ""), expiresAt: expireTime };
}

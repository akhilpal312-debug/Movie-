import assert from "node:assert/strict";
import test from "node:test";
import { GeminiTokenError, mintLiveToken } from "../src/gemini.js";

const NOW = 1_700_000_000_000;

function fakeFetch(status: number, payload: unknown): { fetch: typeof fetch; calls: Array<{ url: string; body: unknown }> } {
  const calls: Array<{ url: string; body: unknown }> = [];
  const impl = (async (input: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(input), body: JSON.parse(String(init?.body ?? "{}")) });
    return new Response(JSON.stringify(payload), {
      status,
      headers: { "content-type": "application/json" },
    });
  }) as typeof fetch;
  return { fetch: impl, calls };
}

test("requests a single-use token constrained to the live model", async () => {
  const { fetch: impl, calls } = fakeFetch(200, { name: "authtokens/abc123" });
  const result = await mintLiveToken({
    apiKey: "server-side-key",
    model: "gemini-3.8-live",
    ttlSeconds: 1800,
    fetchImpl: impl,
    now: () => NOW,
  });

  assert.equal(result.token, "authtokens/abc123");
  assert.equal(result.model, "gemini-3.8-live");
  assert.equal(calls.length, 1);
  assert.match(calls[0]!.url, /\/v1alpha\/auth_tokens\?key=server-side-key$/);

  const body = calls[0]!.body as Record<string, unknown>;
  assert.equal(body.uses, 1);
  assert.equal(body.expireTime, new Date(NOW + 1_800_000).toISOString());
  const constraints = body.liveConnectConstraints as Record<string, unknown>;
  assert.equal(constraints.model, "models/gemini-3.8-live");
  assert.deepEqual((constraints.config as Record<string, unknown>).responseModalities, ["AUDIO"]);
});

test("maps a rejected key to a non-leaking error", async () => {
  const { fetch: impl } = fakeFetch(403, { error: { message: "API key not valid. key=abc" } });
  await assert.rejects(
    () => mintLiveToken({ apiKey: "bad", model: "gemini-3.8-live", ttlSeconds: 60, fetchImpl: impl }),
    (error: unknown) => {
      assert.ok(error instanceof GeminiTokenError);
      assert.equal(error.providerStatus, 403);
      assert.match(error.message, /rejected/i);
      assert.doesNotMatch(error.message, /abc/);
      return true;
    },
  );
});

test("rejects a success response without a token", async () => {
  const { fetch: impl } = fakeFetch(200, {});
  await assert.rejects(
    () => mintLiveToken({ apiKey: "k", model: "gemini-3.8-live", ttlSeconds: 60, fetchImpl: impl }),
    (error: unknown) => error instanceof GeminiTokenError,
  );
});

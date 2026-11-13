import assert from "node:assert/strict";
import test from "node:test";
import type { AddressInfo } from "node:net";
import { loadConfig, type Config } from "../src/config.js";
import { createJarvisServer } from "../src/server.js";

const INSTALL = "11111111-2222-3333-4444-555555555555";

function testConfig(overrides: Partial<Config> = {}): Config {
  return {
    ...loadConfig({} as NodeJS.ProcessEnv),
    geminiApiKey: "server-side-key",
    sessionSecret: "s".repeat(48),
    requestsPerMinute: 50,
    ...overrides,
  };
}

async function withServer(
  config: Config,
  options: { mintFails?: boolean } = {},
  run: (baseUrl: string) => Promise<void>,
): Promise<void> {
  const minted: Array<{ model: string; ttlSeconds: number }> = [];
  const server = createJarvisServer(config, {
    log: () => {},
    mint: async (input) => {
      if (options.mintFails) throw new Error("provider exploded");
      minted.push({ model: input.model, ttlSeconds: input.ttlSeconds });
      return { token: "authtokens/test", model: input.model, expiresAt: new Date(Date.now() + 60_000).toISOString() };
    },
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  try {
    await run(`http://127.0.0.1:${port}`);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
}

async function createSession(baseUrl: string): Promise<string> {
  const response = await fetch(`${baseUrl}/api/session`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ installationId: INSTALL, client: "jarvis-android" }),
  });
  assert.equal(response.status, 200);
  const body = (await response.json()) as { sessionToken: string; expiresInSeconds: number };
  assert.ok(body.sessionToken.includes("."));
  assert.equal(body.expiresInSeconds, 900);
  return body.sessionToken;
}

test("health reports configuration without leaking the key", async () => {
  await withServer(testConfig(), {}, async (baseUrl) => {
    const response = await fetch(`${baseUrl}/health`);
    assert.equal(response.status, 200);
    const text = await response.text();
    assert.equal(text.includes("server-side-key"), false);
    const body = JSON.parse(text) as { status: string; model: string };
    assert.equal(body.status, "ok");
    assert.equal(body.model, "gemini-3.8-live");
  });
});

test("health is degraded when Gemini is not configured", async () => {
  await withServer(testConfig({ geminiApiKey: undefined }), {}, async (baseUrl) => {
    const response = await fetch(`${baseUrl}/health`);
    assert.equal(response.status, 503);
  });
});

test("rejects a session request without a valid installation id", async () => {
  await withServer(testConfig(), {}, async (baseUrl) => {
    const response = await fetch(`${baseUrl}/api/session`, {
      method: "POST",
      body: JSON.stringify({ installationId: "x" }),
    });
    assert.equal(response.status, 400);
  });
});

test("mints a token for an authenticated session", async () => {
  await withServer(testConfig(), {}, async (baseUrl) => {
    const session = await createSession(baseUrl);
    const response = await fetch(`${baseUrl}/api/gemini/live-token`, {
      method: "POST",
      headers: { authorization: `Bearer ${session}`, "content-type": "application/json" },
      body: JSON.stringify({ model: "gemini-3.8-live", purpose: "live-voice" }),
    });
    assert.equal(response.status, 200);
    const body = (await response.json()) as { token: string; model: string; expiresAt: string };
    assert.equal(body.token, "authtokens/test");
    assert.equal(body.model, "gemini-3.8-live");
    assert.ok(Date.parse(body.expiresAt) > Date.now());
  });
});

test("refuses token requests without a session", async () => {
  await withServer(testConfig(), {}, async (baseUrl) => {
    const response = await fetch(`${baseUrl}/api/gemini/live-token`, { method: "POST", body: "{}" });
    assert.equal(response.status, 401);

    const forged = await fetch(`${baseUrl}/api/gemini/live-token`, {
      method: "POST",
      headers: { authorization: "Bearer not.a.real.token" },
      body: "{}",
    });
    assert.equal(forged.status, 401);
  });
});

test("refuses an unsupported model", async () => {
  await withServer(testConfig(), {}, async (baseUrl) => {
    const session = await createSession(baseUrl);
    for (const model of ["../../etc/passwd", "gemini-3.8-live; rm -rf /", "a"]) {
      const response = await fetch(`${baseUrl}/api/gemini/live-token`, {
        method: "POST",
        headers: { authorization: `Bearer ${session}` },
        body: JSON.stringify({ model }),
      });
      assert.equal(response.status, 400, `model should be rejected: ${model}`);
    }
  });
});

test("rate limits repeated token minting per installation", async () => {
  await withServer(testConfig({ requestsPerMinute: 3 }), {}, async (baseUrl) => {
    const session = await createSession(baseUrl);
    const statuses: number[] = [];
    for (let index = 0; index < 5; index++) {
      const response = await fetch(`${baseUrl}/api/gemini/live-token`, {
        method: "POST",
        headers: { authorization: `Bearer ${session}` },
        body: JSON.stringify({}),
      });
      statuses.push(response.status);
    }
    assert.deepEqual(statuses.slice(0, 3), [200, 200, 200]);
    assert.equal(statuses[3], 429);
  });
});

test("returns 503 when Gemini is unconfigured", async () => {
  await withServer(testConfig({ geminiApiKey: undefined }), {}, async (baseUrl) => {
    const session = await createSession(baseUrl);
    const response = await fetch(`${baseUrl}/api/gemini/live-token`, {
      method: "POST",
      headers: { authorization: `Bearer ${session}` },
      body: "{}",
    });
    assert.equal(response.status, 503);
  });
});

test("maps provider failures to a 502 without leaking internals", async () => {
  await withServer(testConfig(), { mintFails: true }, async (baseUrl) => {
    const session = await createSession(baseUrl);
    const response = await fetch(`${baseUrl}/api/gemini/live-token`, {
      method: "POST",
      headers: { authorization: `Bearer ${session}` },
      body: "{}",
    });
    assert.equal(response.status, 502);
    const text = await response.text();
    assert.equal(text.includes("provider exploded"), false);
  });
});

test("rejects oversized bodies and unknown routes", async () => {
  await withServer(testConfig(), {}, async (baseUrl) => {
    const oversized = await fetch(`${baseUrl}/api/session`, {
      method: "POST",
      body: JSON.stringify({ installationId: INSTALL, padding: "x".repeat(9000) }),
    });
    assert.equal(oversized.status, 400);

    const missing = await fetch(`${baseUrl}/nope`);
    assert.equal(missing.status, 404);
  });
});

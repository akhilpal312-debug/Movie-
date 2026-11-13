import assert from "node:assert/strict";
import test from "node:test";
import { bearerToken, issueSession, isValidInstallationId, verifySession } from "../src/session.js";

const SECRET = "0123456789abcdef0123456789abcdef";
const INSTALL = "11111111-2222-3333-4444-555555555555";

test("issues a session that verifies with the same secret", () => {
  const { token, expiresInSeconds } = issueSession(INSTALL, SECRET, 900, 1_700_000_000_000);
  assert.equal(expiresInSeconds, 900);

  const claims = verifySession(token, SECRET, 1_700_000_010_000);
  assert.ok(claims);
  assert.equal(claims.sub, INSTALL);
  assert.equal(claims.exp * 1000, 1_700_000_000_000 + 900_000);
});

test("rejects a tampered payload", () => {
  const { token } = issueSession(INSTALL, SECRET, 900);
  const [payload, signature] = token.split(".");
  const forgedPayload = Buffer.from(
    JSON.stringify({ sub: "99999999-2222-3333-4444-555555555555", iat: 1, exp: 9_999_999_999 }),
  ).toString("base64url");

  assert.equal(verifySession(`${forgedPayload}.${signature}`, SECRET), null);
  assert.equal(verifySession(`${payload}.deadbeef`, SECRET), null);
});

test("rejects a token signed with a different secret", () => {
  const { token } = issueSession(INSTALL, SECRET, 900);
  assert.equal(verifySession(token, "ffffffffffffffffffffffffffffffff"), null);
});

test("rejects an expired session", () => {
  const { token } = issueSession(INSTALL, SECRET, 60, 1_700_000_000_000);
  assert.ok(verifySession(token, SECRET, 1_700_000_030_000));
  assert.equal(verifySession(token, SECRET, 1_700_000_120_000), null);
});

test("rejects malformed installation ids", () => {
  assert.equal(isValidInstallationId("short"), false);
  assert.equal(isValidInstallationId("../../../etc/passwd"), false);
  assert.equal(isValidInstallationId(42), false);
  assert.equal(isValidInstallationId(INSTALL), true);
  assert.throws(() => issueSession("nope", SECRET, 900));
});

test("parses bearer headers strictly", () => {
  assert.equal(bearerToken("Bearer abc.def"), "abc.def");
  assert.equal(bearerToken("bearer   spaced  "), "spaced");
  assert.equal(bearerToken("Basic abc"), undefined);
  assert.equal(bearerToken(undefined), undefined);
});

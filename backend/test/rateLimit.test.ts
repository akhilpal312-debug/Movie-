import assert from "node:assert/strict";
import test from "node:test";
import { RateLimiter } from "../src/rateLimit.js";

test("allows up to the limit then refuses", () => {
  let now = 1_000_000;
  const limiter = new RateLimiter(3, () => now);
  assert.equal(limiter.tryConsume("device"), true);
  assert.equal(limiter.tryConsume("device"), true);
  assert.equal(limiter.tryConsume("device"), true);
  assert.equal(limiter.tryConsume("device"), false);
  assert.equal(limiter.tryConsume("other"), true);
});

test("reports a sane retry-after and recovers after the window", () => {
  let now = 1_000_000;
  const limiter = new RateLimiter(1, () => now);
  assert.equal(limiter.tryConsume("device"), true);
  assert.equal(limiter.tryConsume("device"), false);
  const retryAfter = limiter.retryAfterSeconds("device");
  assert.ok(retryAfter >= 1 && retryAfter <= 60);

  now += 61_000;
  assert.equal(limiter.tryConsume("device"), true);
});

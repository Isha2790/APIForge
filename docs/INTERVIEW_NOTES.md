# Interview notes for APIForge

You must be able to explain every part of this project in your own words. Read the code behind each answer first.
Use real numbers from your own runs (`loadtest/README.md`), not numbers from this document.

## 60-second pitch
"APIForge is a small API gateway platform. A company registers its API, issues keys to customers, and sets limits and
quotas. Requests go through stateless gateway instances that authenticate the key, enforce a distributed token-bucket
rate limit in Redis, apply quotas, and proxy to the customer's API. Usage flows through Redis Streams into Postgres roll-ups
for the dashboard. I focused on correctness under concurrency and on failure behaviour."

## Questions you should expect (and where the answer lives)

**Why Lua for rate limiting? What breaks without it?**
Two gateways doing GET, compute, SET can both read the same token count and both allow the request. A Lua script runs
atomically inside Redis, so check-and-decrement is one step. `gateway/src/main/resources/rate_limit.lua`, test: `RedisScriptsTest`.

**Why does the script call `TIME` instead of using the gateway's clock?**
Instances can have different clocks; a clock ahead would refill buckets early. Redis is the single clock. (Earlier code passed the client time; a 30 s skew refilled a drained bucket.)

**Token bucket vs fixed window?**
A fixed window allows a double burst at the boundary (limit at 0:59 and again at 1:00). A token bucket allows a bounded burst (capacity) and a smooth refill. Capacity here equals requests per minute.

**What happens if Redis goes down?**
Configurable: `fail-closed` (default) returns 503 so limits are never silently dropped; `fail-open` keeps serving. Redis commands time out after 500 ms so a slow Redis cannot hang requests. Cached keys also live in Redis, so with Redis down validation falls back to management.

**How is the validation cache kept correct after a revoke?**
Management evicts the exact cache entry on revoke/rotate/edit/delete (the cache key contains the same SHA-256 stored in the database). Every entry also has a TTL (60 s, shortened to the rotation grace or key expiry), so a failed eviction has bounded impact. See `KeyCache`, `InternalController`.

**Why cache unknown keys ("negative caching")? **
Otherwise someone spraying random keys sends every request to the management database. Unknown keys are cached for 10 s, and callers who keep sending invalid keys are throttled per IP.

**How does key rotation avoid breaking customers?**
Rotation stores the old hash with an expiry. `findValid` accepts the current hash, or the previous hash until it expires. The TTL management tells the gateway to cache never outlives that deadline.

**How are keys stored?**
Only a SHA-256 hash. Keys have 256 bits of randomness, so a fast hash is appropriate (unlike passwords, which use BCrypt). The plaintext is returned once at creation.

**How do you prevent one tenant reading another's data?**
Every organization or API endpoint resolves the caller's membership first (`Access`). Non-members get 404 rather than 403 so ids reveal nothing. Tested in `ManagementIntegrationTest`.

**What is SSRF and what did you do?**
A user-supplied upstream URL could point at `http://169.254.169.254` (cloud metadata) or an internal service. Registration resolves the host and rejects private, loopback, link-local, CGNAT and ULA addresses; the gateway repeats it at request time. Limitation: DNS can still change between the check and the connection (rebinding). Pinning the resolved IP is the next step.

**Why Redis Streams for analytics, and what are the delivery guarantees?**
The request path does one non-blocking `XADD`; a consumer group reads batches, aggregates in memory, upserts hourly rows in one transaction, then acknowledges. At-least-once: a crash between commit and ack can double count one batch. Exactly-once would need idempotent writes keyed by stream id.

**How does password reset work, and how did you keep it safe?**
The emailed token is 256 random bits and only its SHA-256 is stored, like API keys, so a database leak does not hand out working links. It is single use and expires after 30 minutes; spending it is one atomic `UPDATE ... WHERE used_at IS NULL AND expires_at > now()`, so two clicks cannot both succeed. The request endpoint answers `202` with the same text whether or not the address exists (no account enumeration), and sends in the background so the response time does not give it away either. Per-address limit of 3 emails an hour (Redis) stops inbox flooding; a new request voids older links; a successful reset clears the login lockout and sends a "your password changed" email. Known gap: stateless JWTs issued before the reset keep working until they expire; fixing it needs a `password_changed_at` check in the JWT filter.

**What would you change to scale to much higher traffic?**
Shard rate-limit keys (Redis Cluster; the scripts touch only their own keys, quotas use two keys that should share a hash tag), use a local in-memory cache in front of Redis for key lookups, batch usage events in the gateway, partition `usage_hourly`, and use Prometheus histograms for percentile latency.

## Things to be honest about
* You used AI assistance; be ready to modify any part live.
* Limitations are listed in the README. Naming them yourself is a strength.
* Do not claim throughput numbers you have not measured.

## Resume bullets (use only after you have run everything and measured)
* Built a multi-tenant API gateway platform (Java 21, Spring Boot, Spring Cloud Gateway, Redis, PostgreSQL, Next.js) with API-key auth, role-based access and tenant isolation.
* Implemented distributed token-bucket rate limiting and daily/monthly quotas as atomic Redis Lua scripts shared by multiple gateway instances; verified with concurrent tests.
* Designed a Redis-backed key-validation cache with exact invalidation on revoke/rotate, and an async analytics pipeline (Redis Streams consumer group to Postgres hourly roll-ups).
* Added Prometheus/Grafana monitoring, Testcontainers integration tests and a GitHub Actions pipeline; load-tested with k6 at [your measured RPS] with p95 [your measured] ms.

# Review fixes

Applied to the original ChatGPT-generated MVP.

## Fixed bugs
| # | Problem | Fix |
|---|---|---|
| 1 | Frontend did not compile: `await` inside a non-async `setKeys` callback (`page.tsx`) | Await first, then `setKeys` |
| 2 | Jars were not executable: parent POM is not `spring-boot-starter-parent`, so `repackage` never ran | `repackage` execution added in parent `pluginManagement` |
| 3 | All `@PathVariable` endpoints would fail: Spring 6.1 needs `-parameters` | `<parameters>true</parameters>` in compiler config |
| 4 | CORS never applied: bean was named `cors`, Spring Security looks for `corsConfigurationSource` | Bean renamed |
| 5 | Hibernate `ddl-auto: validate` would likely reject `CHAR(64)` mapped to `String` | `key_hash` is now `VARCHAR(64)` (V1 edited in place: run `docker compose down -v` if you ran the old one) |
| 6 | `package-lock.json` missing, so `npm ci` in CI failed | Lock file added; Dockerfile uses `npm ci` |
| 7 | `lint` script was just `tsc` | Renamed to `typecheck` (CI and README updated) |
| 8 | Gateway `switchIfEmpty(401)` fired after every successful request | Validation returns `Optional`; no `switchIfEmpty` on the `Mono<Void>` |
| 9 | Rate-limit Lua used each gateway's own clock | Uses Redis `TIME` (and `HSET` instead of deprecated `HMSET`) |
| 10 | No timeouts: slow Redis/management hung requests | Redis command timeout 500 ms, management call 2 s |
| 11 | Any pipeline error became 503, including upstream errors | Only validation/limiter errors map to 503; upstream errors use Gateway's normal handling |

## Also added
- `FAILURE_POLICY=fail-closed|fail-open` for Redis outages; `Retry-After` header on 429.
- Management: stateless sessions, 401 (not 403) for unauthenticated requests, JWT filter registered only inside the security chain.
- Real tests: `RateLimitScriptTest` (500 concurrent calls, real Redis) and `TenantIsolationTest` (full app on real Postgres).
- `scripts/smoke.sh` now checks the 429 limit and key revocation.

## Not changed (still open)
Quotas, analytics, Redis Streams, key rotation, member invites/roles, a response cache for key validation, metrics, AWS deployment, real SSRF protection beyond the demo allowlist, and restricting `/internal/**` to the internal network. Spring Boot 3.3.x is kept because bumping it could not be verified here.

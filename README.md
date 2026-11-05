# APIForge
 
**A multi-tenant API management platform with distributed rate limiting.**
Companies register an API, issue keys to their customers, set limits and quotas, and watch the traffic, in the style of Kong or AWS API Gateway.
 
[![CI](https://github.com/Isha2790/APIForge/actions/workflows/ci.yml/badge.svg)](https://github.com/Isha2790/APIForge/actions/workflows/ci.yml)
 
**Stack:** Java 21 · Spring Boot · Spring Cloud Gateway · PostgreSQL · Redis (Lua + Streams) · Next.js · Docker · GitHub Actions · Prometheus · Grafana · k6
 
<!-- Add after deploying: **Live demo:** https://app.yourdomain.com -->
 
---
 
## Highlights
 
- **Distributed rate limiting that holds across instances.** The token bucket runs as an atomic Redis Lua script, with time taken from Redis so instance clocks cannot skew it.
- **Tested under load.** At **1,500 requests/sec** for 60 seconds on a laptop, two gateways gave **p95 ≈ 21 ms with 0% errors**. One gateway at the same rate gave p95 ≈ 240 ms. See [Load test results](#load-test-results).
- **The database stays off the request path.** The gateway validates keys from a Redis cache. Usage events go through Redis Streams and are rolled up into Postgres by a background consumer. Postgres sat at about 1-2% CPU under load.
- **Security built in.** Hashed API keys shown once, key rotation with a grace period, scoped keys (methods and path prefix), tenant isolation, login lockout, SSRF protection and role-based access.
- 
## Architecture
 
![Architecture Diagram Screenshot](Architecture.png)
 
- **Control plane** (`management/`): accounts, organizations, roles, invitations, API registration, key lifecycle, analytics.
- **Data plane** (`gateway/`): stateless, so any number of instances can run side by side and share state through Redis.
- **PostgreSQL** is the source of truth. **Redis** holds caches, rate-limit buckets, quota counters and the usage event stream.
## Features
 
| Area | What it does |
|---|---|
| Organizations | Roles OWNER / ADMIN / MEMBER, email-bound invitations (single-use, 7-day token), change role, remove member |
| API registration | Create, list, edit and delete APIs, each with a default rate limit |
| API keys | Hashed (SHA-256) and shown once; per-key limit, daily and monthly quota, allowed methods, path prefix and expiry; rotation with a grace period; instant revocation |
| Rate limiting | Atomic token bucket in Lua, consistent across all gateway instances |
| Quotas | Daily and monthly counters with atomic check-and-consume; refused requests consume nothing |
| Analytics | Summary, hourly time series and per-API breakdown in the dashboard |
| Caching | Key validation cached in Redis (hits and misses), exact eviction on revoke, rotate and edit, TTL as a safety net, keeps working during a management outage |
| Async analytics | Gateways publish to a capped Redis Stream; a consumer group batches events into hourly Postgres roll-ups and acknowledges after commit |
| Reliability | Redis and management timeouts, one retry, configurable `fail-open` / `fail-closed` policy |
| Security | JWT + BCrypt, login lockout, per-IP throttling of invalid keys, tenant isolation (non-members get 404), SSRF checks at registration and request time, path-traversal rejection, `/internal` protected by a shared secret and a loopback-only port |
| Email | Password reset (single-use 30-minute link) and invitation emails; Mailpit in development, SMTP in production |
| Operations | Docker Compose with two gateways and health checks, GitHub Actions CI, Prometheus metrics, provisioned Grafana dashboard, k6 load test |
 
## Quick start
 
Requirements: Docker with Compose, and Git. (Bash is needed for the helper scripts: Git Bash or WSL on Windows.)
 
```bash
git clone https://github.com/Isha2790/APIForge.git
cd APIForge
bash scripts/gen-env.sh        # creates .env with random secrets
docker compose up --build -d
```
 
| Service | URL |
|---|---|
| Dashboard | http://localhost:3000 |
| Management API | http://localhost:8081 |
| Gateway 1 / Gateway 2 | http://localhost:8080 / http://localhost:8082 |
| Dev inbox (Mailpit) | http://localhost:8025 |
 
**Try it:**
 
1. Open the dashboard, register, and create an organization.
2. Register an API with the upstream `http://demo-api:5678`.
3. Create a key (shown once), then call it through the gateway:
```bash
curl -H "X-API-Key: af_..." http://localhost:8080/proxy/<apiId>/
```
 
Optional monitoring: `docker compose --profile observability up -d`, then Prometheus at http://localhost:9090 and Grafana at http://localhost:3001 (user `admin`, password `GRAFANA_PASSWORD` from `.env`).
 
## Testing
 
```bash
mvn -B verify                       # unit tests + Testcontainers integration tests (needs Docker)
bash scripts/smoke.sh               # end-to-end against the running stack
bash scripts/distributed-check.sh   # proves the limit holds across two gateway instances
```
 
`smoke.sh` covers authentication, proxying, the 429 limit across both gateways, daily quota, key scopes (403), rotation, revocation (401), team roles, analytics and the password-reset email. CI (GitHub Actions) builds both services and the frontend and runs the test suite on every push.
 
## Load test results
 
Run with [k6](https://k6.io), constant arrival rate for 60 seconds, against the demo upstream. Everything ran on **one laptop with k6 on the same machine** (16 logical CPUs available to Docker), so treat the numbers as indicative, not as production capacity.
 
| Gateways | Target rate | Achieved | p95 latency | Errors | Dropped iterations |
|---|---|---|---|---|---|
| 2 | 1,000 req/s | ~1,000 req/s | 8.8 ms | 0% | – |
| 2 | 1,500 req/s | 1,494 req/s | 21 ms | 0% | 364 |
| 1 | 1,500 req/s | 1,445 req/s | 240 ms | 0% | 3,261 |
 
CPU during the 1,500 req/s runs: each of the two gateways used about 2.2 cores, a single gateway about 3.4 cores, Redis about 53-80% of one core (it is single-threaded, so it is the next ceiling), and Postgres about 1-2%.
 
**Reading it:** with one gateway the CPU saturates and requests queue up, so p95 rises about 11x. Adding a second gateway brings it back down. That is the horizontal-scaling case the stateless design is built for. To reproduce:
 
```bash
k6 run -e API=<apiId> -e KEY=<apiKey> -e RATE=1500 loadtest/k6.js
# one gateway only:
k6 run -e API=<apiId> -e KEY=<apiKey> -e RATE=1500 -e GW2=http://localhost:8080 loadtest/k6.js
```
 
More detail in [loadtest/README.md](loadtest/README.md).
 
## API reference
 
Management service (JWT unless noted):
 
| Method | Path | Purpose |
|---|---|---|
| POST | `/auth/register`, `/auth/login` | Create account / log in (1-hour JWT, no auth) |
| POST | `/auth/forgot-password`, `/auth/reset-password` | Password reset by emailed single-use link (no auth) |
| GET, POST | `/organizations` | List mine / create |
| GET | `/organizations/{org}/members` | Members and roles |
| PATCH, DELETE | `/organizations/{org}/members/{user}` | Change role / remove (OWNER) |
| POST, GET | `/organizations/{org}/invitations` | Invite / list pending |
| DELETE | `/organizations/{org}/invitations/{id}` | Revoke invitation |
| POST | `/invitations/accept` | Join with a token |
| GET, POST | `/organizations/{org}/apis` | List / register an API |
| GET, PUT, DELETE | `/organizations/{org}/apis/{api}` | Read / edit / delete |
| GET, POST | `/apis/{api}/keys` | List / create keys |
| PATCH | `/apis/{api}/keys/{key}` | Change limits or scope |
| POST | `/apis/{api}/keys/{key}/rotate` | New secret; old one valid for a grace period |
| DELETE | `/apis/{api}/keys/{key}` | Revoke |
| GET | `/apis/{api}/keys/{key}/usage` | Quota used today / this month |
| GET | `/organizations/{org}/analytics/summary`, `/timeseries`, `/by-api` | Traffic analytics |
 
Gateway: `ANY /proxy/{apiId}/**` with header `X-API-Key`.
 
| Status | Meaning |
|---|---|
| 401 | Invalid, revoked or expired key |
| 403 | Method or path outside the key's scope |
| 429 | Rate limit (`Retry-After`) or quota (`X-Quota-Exceeded: daily\|monthly`) |
| 503 | Validation or Redis unavailable (fail-closed) |
| 502 / 504 | Upstream problems |
 
Successful responses include `X-RateLimit-Limit`, `X-RateLimit-Remaining` and `X-Quota-*-Remaining`.
 
## Design decisions
 
- **Lua for the limiter.** A read-modify-write across gateway instances must be atomic. One script call is one atomic step, and `TIME` comes from Redis.
- **Validation cache in Redis, not in memory.** Every gateway instance shares it and management can evict exactly the right entry, so revocation is immediate. If an eviction ever fails, the TTL bounds staleness.
- **Stream plus worker for analytics.** The request path only does one fire-and-forget `XADD`. Postgres writes are batched, so analytics never slow down or break traffic. Trade-off: delivery is at-least-once, so a crash between commit and acknowledgement can double-count a batch.
- **Fail-closed by default.** If Redis is down the gateway returns 503 rather than silently removing limits. Set `FAILURE_POLICY=fail-open` for availability-first setups.
- **Two services, not five.** Control and data planes scale and fail differently, which is a real reason to split them.
## Known limitations
 
- Email is best effort: it is sent in the background with no retry queue.
- Password reset does not invalidate JWTs already issued (they expire after 1 hour); there is no email verification at registration yet.
- SSRF checks run at registration and at request time, but DNS is resolved again when connecting, so a hostile DNS server can still race the check. Redirects are not followed.
- `X-Forwarded-For` is ignored for per-IP throttling. Behind a load balancer, all callers would share its address unless trusted-proxy handling is added.
- Analytics are hourly roll-ups with average and max latency (use the Prometheus histogram for percentiles).
- The dashboard keeps the JWT in memory only, so a refresh signs you out.
- `ALLOWED_PRIVATE_HOSTS` / `ALLOW_PRIVATE_HOSTS` and the `mailpit` and `demo-api` services in Compose are for local development only. Remove them for production (see [docs/DEPLOY_AWS.md](docs/DEPLOY_AWS.md)).
## Repository layout
 
```
management/      control plane (Spring Boot, JPA, Flyway, Redis Streams consumer)
gateway/         data plane (Spring Cloud Gateway, reactive Redis, Lua scripts)
frontend/        Next.js dashboard
observability/   Prometheus config and provisioned Grafana dashboard
scripts/         gen-env.sh, smoke.sh, distributed-check.sh
loadtest/        k6 script and results
docs/            deployment guide, interview notes
```
 

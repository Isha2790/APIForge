# What changed in this version

## v2.2: email (password reset and invitation emails)

* **New:** `POST /auth/forgot-password` and `POST /auth/reset-password`, Flyway `V3__password_reset.sql`, `Mailer` (SMTP, background delivery), `ResetRequestLimiter`, the "Forgot password?" form and the `/reset-password` page.
* **Changed (small):** `OrganizationController.invite` now also emails the invitee; the invitation text in `TeamPanel.tsx` says so. Nothing else in existing endpoints or tables was touched.
* **Ops:** Mailpit container (inbox on http://localhost:8025), `SMTP_*`, `PUBLIC_URL`, `MAIL_FROM` in Compose and `.env.example`, `spring-boot-starter-mail` in `management/pom.xml`.
* **Tests:** `PasswordResetIntegrationTest` (8 cases) and a Mailpit step in `scripts/smoke.sh`. Neither has been run by me: no Docker or Maven Central access where this was written.

## v2.1 and earlier

Builds on the reviewed MVP (see FIXES.md for the earlier bug fixes).

* **Management service rewritten in readable, per-concern classes.** Existing endpoint paths were kept (`/auth/*`, `/organizations`, `/organizations/{id}/apis`, `/apis/{id}/keys`, `/internal/validate/{id}`), so the old smoke flow still applies.
* **New:** roles and invitations, API edit/delete, per-key limits/quotas/scopes/expiry, key rotation with grace period, quota usage endpoint, analytics endpoints, Redis Streams worker, cache eviction, login lockout, real SSRF validation (replaces the single hard-coded demo host), Flyway `V2__features.sql`.
* **Gateway rewritten:** Redis key cache (positive + negative), method/path scope, quotas, request-time SSRF check, usage events, metrics, per-IP invalid-key throttling, retry on validation.
* **Ops:** compose with two gateways, health checks, required secrets (`scripts/gen-env.sh`), management port bound to loopback, actuator on separate unpublished ports, Prometheus + provisioned Grafana dashboard, k6 script, CI with image builds.
* **Frontend:** tabs for APIs and keys, team, analytics.
* **Removed:** the demo-only port/host allowlist in the API controller; default secrets in compose.

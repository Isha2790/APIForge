#!/usr/bin/env bash
# End-to-end check against a running `docker compose up`.
# Covers: auth, org, API, key, proxying, rate limit (429), daily quota, method/path scope (403),
# rotation (grace + cut-off), revocation (401), team roles, analytics via Redis Streams,
# password reset by email (reads the message from the Mailpit dev inbox).
set -euo pipefail
BASE=${BASE:-http://localhost:8081}
GATEWAY=${GATEWAY:-http://localhost:8080}
GATEWAY2=${GATEWAY2:-http://localhost:8082}
STAMP=$(date +%s)
json() { python3 -c "import json,sys;print(json.load(sys.stdin)['$1'])"; }
api() { # api METHOD PATH [json body] -> response body, fails on HTTP error
  local method=$1 path=$2; shift 2
  if [ $# -gt 0 ]; then curl -fsS -X "$method" "$BASE$path" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$1"
  else curl -fsS -X "$method" "$BASE$path" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json'; fi
}
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
check() { if [ "$2" != "$3" ]; then echo "FAIL: $1 (expected $2, got $3)"; exit 1; fi; echo "ok: $1"; }

TOKEN=$(curl -fsS -X POST "$BASE/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"owner-$STAMP@example.test\",\"password\":\"StrongPassword123!\"}" | json token)
ORG=$(api POST /organizations '{"name":"Demo Team"}' | json id)
API=$(api POST "/organizations/$ORG/apis" '{"name":"Echo","upstreamUrl":"http://demo-api:5678","requestsPerMinute":5}' | json id)

# 1. rate limit: 8 requests through BOTH gateways, limit is 5/min for the whole key
KEY_JSON=$(api POST "/apis/$API/keys")
KEY=$(echo "$KEY_JSON" | json key); KEY_ID=$(echo "$KEY_JSON" | json id)
CODES=""
for i in $(seq 1 8); do G=$GATEWAY; [ $((i % 2)) = 0 ] && G=$GATEWAY2; CODES="$CODES $(status -H "X-API-Key: $KEY" "$G/proxy/$API/")"; done
echo "rate limit over two gateways:$CODES"
OKS=$(echo "$CODES" | tr ' ' '\n' | grep -c '^200$' || true)
[ "$OKS" -ge 5 ] && [ "$OKS" -le 6 ] || { echo "FAIL: expected 5-6 successes (limit 5), got $OKS"; exit 1; }
echo "ok: limit enforced across both gateway instances"

# 2. daily quota of 2 (separate key with a high rate limit)
QKEY=$(api POST "/apis/$API/keys" '{"name":"quota","requestsPerMinute":1000,"dailyQuota":2}' | json key)
check "quota: request 1" 200 "$(status -H "X-API-Key: $QKEY" "$GATEWAY/proxy/$API/")"
check "quota: request 2" 200 "$(status -H "X-API-Key: $QKEY" "$GATEWAY/proxy/$API/")"
check "quota: request 3 refused" 429 "$(status -H "X-API-Key: $QKEY" "$GATEWAY/proxy/$API/")"

# 3. scope: GET only, /v1 only
SKEY=$(api POST "/apis/$API/keys" '{"name":"scoped","requestsPerMinute":1000,"allowedMethods":"GET","pathPrefix":"/v1"}' | json key)
check "scope: GET /v1/items" 200 "$(status -H "X-API-Key: $SKEY" "$GATEWAY/proxy/$API/v1/items")"
check "scope: POST refused" 403 "$(status -X POST -H "X-API-Key: $SKEY" "$GATEWAY/proxy/$API/v1/items")"
check "scope: other path refused" 403 "$(status -H "X-API-Key: $SKEY" "$GATEWAY/proxy/$API/admin")"

# 4. rotation: old key survives the grace period, then is cut off
RKEY_JSON=$(api POST "/apis/$API/keys" '{"name":"rotating","requestsPerMinute":1000}')
RKEY=$(echo "$RKEY_JSON" | json key); RKEY_ID=$(echo "$RKEY_JSON" | json id)
NEWKEY=$(api POST "/apis/$API/keys/$RKEY_ID/rotate" '{"graceMinutes":5}' | json key)
check "rotation: old key still works during grace" 200 "$(status -H "X-API-Key: $RKEY" "$GATEWAY/proxy/$API/")"
check "rotation: new key works" 200 "$(status -H "X-API-Key: $NEWKEY" "$GATEWAY/proxy/$API/")"
FINALKEY=$(api POST "/apis/$API/keys/$RKEY_ID/rotate" '{"graceMinutes":0}' | json key)
check "rotation: previous key cut off immediately" 401 "$(status -H "X-API-Key: $NEWKEY" "$GATEWAY/proxy/$API/")"
check "rotation: newest key works" 200 "$(status -H "X-API-Key: $FINALKEY" "$GATEWAY/proxy/$API/")"

# 5. revocation takes effect immediately (cache is evicted, not waited out)
api DELETE "/apis/$API/keys/$KEY_ID" >/dev/null
check "revoked key refused" 401 "$(status -H "X-API-Key: $KEY" "$GATEWAY/proxy/$API/")"
check "unknown key refused" 401 "$(status -H "X-API-Key: af_not_a_real_key" "$GATEWAY/proxy/$API/")"

# 6. roles: a second user joins via invitation as read-only MEMBER
MEMBER_TOKEN=$(curl -fsS -X POST "$BASE/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"member-$STAMP@example.test\",\"password\":\"StrongPassword123!\"}" | json token)
INVITE=$(api POST "/organizations/$ORG/invitations" "{\"email\":\"member-$STAMP@example.test\",\"role\":\"MEMBER\"}" | json token)
check "invitation accepted" 200 "$(status -X POST "$BASE/invitations/accept" -H "Authorization: Bearer $MEMBER_TOKEN" -H 'Content-Type: application/json' -d "{\"token\":\"$INVITE\"}")"
check "member can read APIs" 200 "$(status "$BASE/organizations/$ORG/apis" -H "Authorization: Bearer $MEMBER_TOKEN")"
check "member cannot create keys" 403 "$(status -X POST "$BASE/apis/$API/keys" -H "Authorization: Bearer $MEMBER_TOKEN")"

# 7. analytics: events flow gateway -> Redis Stream -> worker -> Postgres (give the worker a moment)
sleep 4
SUMMARY=$(api GET "/organizations/$ORG/analytics/summary?hours=1")
echo "analytics summary: $SUMMARY"
REQ=$(echo "$SUMMARY" | json requests); RL=$(echo "$SUMMARY" | json rateLimited); QE=$(echo "$SUMMARY" | json quotaExceeded); SD=$(echo "$SUMMARY" | json scopeDenied)
[ "$REQ" -ge 17 ] && [ "$RL" -ge 1 ] && [ "$QE" -ge 1 ] && [ "$SD" -ge 2 ] || { echo "FAIL: analytics do not reflect the traffic"; exit 1; }
echo "ok: analytics reflect accepted and rejected requests"
# 8. password reset by email (the link is read from the Mailpit dev inbox; skipped if Mailpit is not reachable)
MAILPIT=${MAILPIT:-http://localhost:8025}
if curl -fsS "$MAILPIT/api/v1/messages" >/dev/null 2>&1; then
  RESET_EMAIL="owner-$STAMP@example.test"
  check "forgot-password answers 202" 202 "$(status -X POST "$BASE/auth/forgot-password" -H 'Content-Type: application/json' -d "{\"email\":\"$RESET_EMAIL\"}")"
  check "forgot-password answers 202 for an unknown address too" 202 "$(status -X POST "$BASE/auth/forgot-password" -H 'Content-Type: application/json' -d "{\"email\":\"nobody-$STAMP@example.test\"}")"
  RESET_TOKEN=$(python3 - "$MAILPIT" "$RESET_EMAIL" <<'PY'
import json, re, sys, time, urllib.request
base, to = sys.argv[1], sys.argv[2]
for _ in range(10):                      # the app sends mail in the background
    for m in json.load(urllib.request.urlopen(base + "/api/v1/messages"))["messages"]:
        if m["Subject"] == "Reset your APIForge password" and any(t["Address"] == to for t in m["To"]):
            text = json.load(urllib.request.urlopen(base + "/api/v1/message/" + m["ID"]))["Text"]
            print(re.search(r"token=([A-Za-z0-9_-]+)", text).group(1))
            sys.exit(0)
    time.sleep(1)
PY
)
  [ -n "$RESET_TOKEN" ] || { echo "FAIL: no reset email found in Mailpit"; exit 1; }
  reset() { status -X POST "$BASE/auth/reset-password" -H 'Content-Type: application/json' -d "{\"token\":\"$RESET_TOKEN\",\"newPassword\":\"BrandNewPassword456!\"}"; }
  check "reset with the emailed token" 200 "$(reset)"
  check "the same token is refused a second time" 400 "$(reset)"
  check "login with the new password" 200 "$(status -X POST "$BASE/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$RESET_EMAIL\",\"password\":\"BrandNewPassword456!\"}")"
  check "login with the old password refused" 401 "$(status -X POST "$BASE/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$RESET_EMAIL\",\"password\":\"StrongPassword123!\"}")"
else
  echo "skip: Mailpit not reachable at $MAILPIT (password reset email not checked)"
fi

echo "Smoke test passed."

#!/usr/bin/env bash
# Proves the limit is shared across gateway instances: 100 concurrent requests split over two gateways
# against a key limited to 20/min must yield ~20 successes in total (not ~40).
set -euo pipefail
BASE=${BASE:-http://localhost:8081}; export G1=${G1:-http://localhost:8080} G2=${G2:-http://localhost:8082}
json() { python3 -c "import json,sys;print(json.load(sys.stdin)['$1'])"; }
STAMP=$(date +%s)
TOKEN=$(curl -fsS -X POST "$BASE/auth/register" -H 'Content-Type: application/json' -d "{\"email\":\"dist-$STAMP@example.test\",\"password\":\"StrongPassword123!\"}" | json token)
H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
ORG=$(curl -fsS -X POST "$BASE/organizations" "${H[@]}" -d '{"name":"Dist Test"}' | json id)
API=$(curl -fsS -X POST "$BASE/organizations/$ORG/apis" "${H[@]}" -d '{"name":"Echo","upstreamUrl":"http://demo-api:5678","requestsPerMinute":20}' | json id)
export key=$(curl -fsS -X POST "$BASE/apis/$API/keys" "${H[@]}" | json key); export api=$API
echo "100 concurrent requests over two gateways, limit 20/min:"
seq 1 100 | xargs -P 25 -I{} sh -c 'if [ $(({} % 2)) = 0 ]; then g=$G1; else g=$G2; fi; curl -s -o /dev/null -w "%{http_code}\n" -H "X-API-Key: $key" "$g/proxy/$api/"' | sort | uniq -c
echo "Expect about 20 x 200 (a couple more from continuous refill) and the rest 429."

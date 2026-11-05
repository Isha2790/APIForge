# Load test results

Fill this in from your own runs; do not publish numbers you have not measured.

| Date | Machine | Gateways | RATE (req/s) | Key limit | p95 latency | 200 count | 429 count | Notes |
|---|---|---|---|---|---|---|---|---|
| | | 2 | | | | | | |

How: `docker compose up --build -d`, create an API and a key with `requestsPerMinute` set very high, then
`k6 run -e API=<apiId> -e KEY=<apiKey> -e RATE=200 loadtest/k6.js`. Watch the Grafana dashboard while it runs.
Run a second test with a low limit (for example 60/min) to see the 429 path under load.

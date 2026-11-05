// Usage:  k6 run -e API=<apiId> -e KEY=<apiKey> loadtest/k6.js
// Optional: -e GW=http://localhost:8080  -e GW2=http://localhost:8082  -e RATE=200
// Create the key with a high limit first (e.g. requestsPerMinute 100000) to measure throughput/latency;
// use a low limit to measure how the gateway behaves while rejecting.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

http.setResponseCallback(http.expectedStatuses(200, 429));   // 429 is a correct answer, not a failure

const ok = new Counter('apiforge_200');
const limited = new Counter('apiforge_429');
const gateways = [__ENV.GW || 'http://localhost:8080', __ENV.GW2 || 'http://localhost:8082'];

export const options = {
  scenarios: {
    steady: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 200), timeUnit: '1s', duration: '60s', preAllocatedVUs: 50, maxVUs: 400 },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<150'],
  },
};

export default function () {
  const gw = gateways[__ITER % gateways.length];
  const res = http.get(`${gw}/proxy/${__ENV.API}/`, { headers: { 'X-API-Key': __ENV.KEY } });
  check(res, { 'status is 200 or 429': (r) => r.status === 200 || r.status === 429 });
  if (res.status === 200) ok.add(1); else if (res.status === 429) limited.add(1);
}

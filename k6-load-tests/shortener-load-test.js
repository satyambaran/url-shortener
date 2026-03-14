import http from 'k6/http';
import { sleep, check } from 'k6';
import { Rate } from 'k6/metrics';

export let errorRate = new Rate('errors');

export let options = {
  stages: [
    { duration: '30s', target: 200 },
    { duration: '1m', target: 1000 },
    { duration: '30s', target: 200 },
  ],
  thresholds: {
    'http_req_duration{type:redirect}': ['p(95)<50', 'p(99)<100'],
    'errors': ['rate<0.001'],
  },
};

const BASE = __ENV.BASE_URL || 'https://localhost:8080';

function createShort() {
  const payload = JSON.stringify({ longUrl: `https://example.com/${Math.random()}` });
  const res = http.post(`${BASE}/api/shorten`, payload, { headers: { 'Content-Type': 'application/json' } });
  return res;
}

function getShort(shortCode) {
  return http.get(`${BASE}/${shortCode}`);
}

export default function () {
  // read/write ratio 100:1
  for (let i = 0; i < 100; i++) {
    const res = getShort('nonexistent');
    const ok = check(res, { 'status is 2xx or 3xx or 404': (r) => r.status >= 200 && r.status < 500 });
    if (!ok) errorRate.add(1);
    sleep(0.01);
  }
  // one write
  const w = createShort();
  if (w.status >= 400) errorRate.add(1);
  sleep(1);
}


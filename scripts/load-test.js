import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: { ingest: { executor: 'constant-arrival-rate', rate: 200, timeUnit: '1s', duration: '60s', preAllocatedVUs: 30, maxVUs: 200 } },
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<500'] },
};

export default function () {
  const id = `${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post('http://localhost:8080/api/events', JSON.stringify({ eventId: id, type: 'load.test', data: { id } }), {
    headers: { 'Content-Type': 'application/json', 'X-App-Id': 'demo-order-service', 'X-Api-Key': 'order-key' },
  });
  check(response, { accepted: (r) => r.status === 200 });
}

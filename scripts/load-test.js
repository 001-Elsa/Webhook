import http from 'k6/http';
import { check } from 'k6';

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: { ingest: { executor: 'constant-arrival-rate', rate: Number(__ENV.EVENTRELAY_RATE || 200),
    timeUnit: '1s', duration: __ENV.EVENTRELAY_DURATION || '60s', preAllocatedVUs: 30, maxVUs: 300 } },
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<500', 'p(99)<1000'] },
};

export default function () {
  const id = `${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post('http://localhost:8080/api/events', JSON.stringify({ eventId: id, type: 'load.test', data: { id } }), {
    headers: { 'Content-Type': 'application/json', 'X-App-Id': __ENV.EVENTRELAY_APP_ID,
      'X-Api-Key': __ENV.EVENTRELAY_API_KEY },
  });
  check(response, { accepted: (r) => r.status === 200 });
}

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';

const profile = __ENV.EVENTRELAY_PROFILE || 'e2e';
const eventType = __ENV.EVENTRELAY_EVENT_TYPE || 'performance.e2e';
const submitLatency = new Trend('eventrelay_submit_latency', true);
const e2eLatency = new Trend('eventrelay_e2e_latency', true);
const submitted = new Counter('eventrelay_event_submits');
const submitFailure = new Rate('eventrelay_submit_failure');
const terminalFailure = new Rate('eventrelay_terminal_failure');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.EVENTRELAY_RATE || 50),
      timeUnit: '1s',
      duration: __ENV.EVENTRELAY_DURATION || '60s',
      preAllocatedVUs: Number(__ENV.EVENTRELAY_PREALLOCATED_VUS || 50),
      maxVUs: Number(__ENV.EVENTRELAY_MAX_VUS || 500),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
    eventrelay_terminal_failure: ['rate<0.01'],
  },
};

const producerHeaders = {
  'Content-Type': 'application/json',
  'X-App-Id': __ENV.EVENTRELAY_APP_ID,
  'X-Api-Key': __ENV.EVENTRELAY_API_KEY,
};
const adminHeaders = {
  'X-App-Id': __ENV.EVENTRELAY_ADMIN_APP_ID,
  'X-Api-Key': __ENV.EVENTRELAY_ADMIN_API_KEY,
};

export default function () {
  const eventId = `perf-${profile}-${__VU}-${__ITER}-${Date.now()}`;
  const started = Date.now();
  const response = http.post('http://localhost:8080/api/events',
    JSON.stringify({ eventId, type: eventType, data: { id: eventId, profile } }),
    { headers: producerHeaders, tags: { chain: profile } });
  check(response, { accepted: (r) => r.status === 200 });
  submitted.add(1);
  submitLatency.add(response.timings.duration);
  submitFailure.add(response.status !== 200);
  if (profile !== 'e2e' || response.status !== 200) return;

  const deadline = Date.now() + Number(__ENV.EVENTRELAY_E2E_TIMEOUT_MS || 30000);
  while (Date.now() < deadline) {
    const state = http.get(`http://localhost:8080/api/events/${eventId}/status`,
      { headers: adminHeaders, tags: { chain: 'e2e-status' } });
    if (state.status === 200) {
      const status = state.json('status');
      if (['COMPLETED', 'DEAD', 'PARTIALLY_FAILED'].includes(status)) {
        e2eLatency.add(Date.now() - started);
        terminalFailure.add(status !== 'COMPLETED');
        return;
      }
    }
    sleep(0.1);
  }
  terminalFailure.add(true);
  e2eLatency.add(Date.now() - started);
}

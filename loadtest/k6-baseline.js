/*
 * Phase A14 — k6 baseline for the top-5 backend endpoints.
 *
 * Prerequisites (local/demo stack):
 *   1. docker compose up -d            (backend + PostgreSQL 17, see ../../docker-compose.yml)
 *   2. seed the staff user: docker compose exec postgres psql -U steg -d stegdb -f /seed.sql
 *      (mount ./seed.sql into the container or copy it in first)
 *   3. k6 run loadtest/k6-baseline.js   (or: BASE_URL=http://host:8080 k6 run ...)
 *
 * Design notes:
 * - Auth scenario stays under the login rate limit (10/min/IP): 2 VUs logging in
 *   roughly every ~30s each, well below the gate, so 429s would be a real finding.
 * - The API scenario exercises the four hottest read paths plus public reference
 *   data; none of them is @RateLimited, so any 429 is a real finding.
 * - Upload and AI endpoints are intentionally excluded from the steady-state loop
 *   (they are rate-limited by design and expensive by nature); login covers auth.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    auth: {
      executor: 'constant-vus',
      vus: 2,
      duration: '60s',
      exec: 'loginFlow',
    },
    api: {
      executor: 'constant-vus',
      vus: 10,
      duration: '60s',
      exec: 'apiFlow',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_EMAIL = 'load.admin@steg.tn';
const ADMIN_PASSWORD = 'Loadtest#123';

function login(email, password) {
  const res = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(res, { 'login 200': (r) => r.status === 200 });
  return res.status === 200 ? res.json('accessToken') : null;
}

export function setup() {
  const adminToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);
  if (!adminToken) throw new Error('setup: admin login failed — is the stack up and seeded?');

  // One candidate per k6 run (unique email), with a DRAFT application so the
  // candidate-scoped reads return real rows instead of empty pages.
  const stamp = Date.now();
  const email = `load.cand.${stamp}@test.tn`;
  let res = http.post(
    `${BASE}/api/auth/register`,
    JSON.stringify({
      email,
      password: 'Loadtest#123',
      firstName: 'Load',
      lastName: 'Cand',
      phone: '+216 71 000 000',
    }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(res, { 'register 201': (r) => r.status === 201 });

  const candToken = login(email, 'Loadtest#123');

  // Candidate profile is required before any application (422 otherwise):
  // resolve the demo university seeded by seed.sql, then create the profile.
  const unis = http.get(`${BASE}/api/universities`);
  check(unis, { 'universities 200': (r) => r.status === 200 });
  const uniId = unis.json()[0].id;
  res = http.post(
    `${BASE}/api/candidates`,
    JSON.stringify({
      firstName: 'Load',
      lastName: 'Cand',
      email,
      phone: '+216 71 000 000',
      universityId: uniId,
      nationalId: '99000001',
    }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${candToken}` } },
  );
  check(res, { 'profile 201': (r) => r.status === 201 });

  res = http.post(
    `${BASE}/api/applications`,
    JSON.stringify({
      desiredStartDate: '2026-07-01',
      desiredEndDate: '2026-08-31',
      proposedTheme: 'k6 baseline application',
      submittedOnline: true,
    }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${candToken}` } },
  );
  check(res, { 'draft 201': (r) => r.status === 201 });

  return { adminToken, candToken, email };
}

export function loginFlow(data) {
  // Steady re-login well under the 10/min/IP gate.
  const token = login(data.email, 'Loadtest#123');
  check(token, { 're-login yields token': (t) => t !== null });
  sleep(25 + Math.random() * 10);
}

const API_ROUTES = [
  (t) => ['GET /api/applications', `${BASE}/api/applications`, t.adminToken],
  (t) => ['GET /api/internships', `${BASE}/api/internships`, t.adminToken],
  (t) => ['GET /api/audit', `${BASE}/api/audit?page=0&size=20`, t.adminToken],
  (t) => ['GET /api/notifications', `${BASE}/api/notifications?page=0&size=20`, t.candToken],
  (t) => ['GET /api/universities', `${BASE}/api/universities`, null],
];

export function apiFlow(data) {
  const pick = API_ROUTES[__ITER % API_ROUTES.length](data);
  const headers = {};
  if (pick[2]) headers.Authorization = `Bearer ${pick[2]}`;
  const res = http.get(pick[1], { headers, tags: { endpoint: pick[0] } });
  check(res, { [`${pick[0]} 2xx`]: (r) => r.status >= 200 && r.status < 300 });
  sleep(0.2 + Math.random() * 0.5);
}

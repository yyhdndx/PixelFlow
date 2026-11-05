import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const config = JSON.parse(open(__ENV.LOAD_MANIFEST));
if (config.base !== 'http://127.0.0.1:18096' || config.database !== 'pf_load_test') {
  throw new Error('Only the owned loopback load-test service is allowed');
}
const users = new SharedArray('load users', () => config.users);
const names = ['feed', 'following', 'detail', 'comments', 'search', 'notifications', 'comment_write', 'likes', 'favorites', 'login'];
const latency = {}, failures = {}, totals = {};
for (const name of names) {
  latency[name] = new Trend(`latency_${name}`, true);
  failures[name] = new Rate(`failures_${name}`);
  totals[name] = new Counter(`requests_${name}`);
}
const errors = new Counter('error_codes');
const statuses = new Counter('status_codes');
const businessErrors = new Rate('business_errors');
export const options = {
  scenarios: __ENV.MODE === 'login'
    ? { login: { executor: 'shared-iterations', vus: 1, iterations: 10, maxDuration: '2m' } }
    : { business: { executor: 'constant-vus', vus: Number(__ENV.VUS || 100), duration: __ENV.DURATION || '300s', gracefulStop: '30s' } },
  maxRedirects: 0,
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: { business_errors: ['rate<0.01'], http_req_duration: ['p(95)<=800'] },
};
const allowed = /^(?:\/feed\/|\/api\/feed\/following|\/api\/posts\/[1-9][0-9]*(?:\/comments)?|\/api\/search\/posts|\/api\/users\/me\/notifications|\/api\/interactions\/(?:likes|favorites)|\/api\/auth\/login)$/;
const cursors = {};
function request(name, method, path, body, user) {
  if (!allowed.test(path.split('?')[0])) throw new Error('Disallowed request path');
  const response = http.request(method, config.base + path, body === null ? null : JSON.stringify(body), {
    headers: { Authorization: `Bearer ${user.token}`, 'Content-Type': 'application/json' },
    tags: { name }, timeout: '10s', redirects: 0,
  });
  let parsed = null;
  try { parsed = response.json(); } catch (_) { /* Count malformed JSON as a business failure. */ }
  let valid = response.status === 200 && parsed !== null;
  if (['feed', 'following', 'search'].includes(name)) valid = valid && parsed.status_code === 0 && Array.isArray(parsed.post_list) && parsed.post_list.length > 0;
  if (name === 'detail') valid = valid && typeof parsed.post_id === 'string' && parsed.status === 'PUBLISHED';
  if (name === 'comments') valid = valid && Array.isArray(parsed.comment_list) && parsed.comment_list.length > 0;
  if (name === 'notifications') valid = valid && Array.isArray(parsed.notification_list) && parsed.notification_list.length > 0;
  if (name === 'comment_write') valid = valid && typeof parsed.comment_id === 'string';
  if (name === 'likes' || name === 'favorites') valid = valid && parsed.active === body.active;
  if (name === 'login') valid = valid && typeof parsed.accessToken === 'string';
  check(response, { 'HTTP and business response valid': () => valid });
  latency[name].add(response.timings.duration);
  failures[name].add(!valid); totals[name].add(1); businessErrors.add(!valid);
  statuses.add(1, { endpoint: name, status: String(response.status) });
  if (!valid) errors.add(1, { endpoint: name, code: String(parsed && parsed.code || (response.status === 0 ? 'TRANSPORT' : 'INVALID_RESPONSE')), status: String(response.status) });
  return parsed;
}
export default function () {
  const user = users[(__VU - 1) % users.length];
  if (__ENV.MODE === 'login') { request('login', 'POST', '/api/auth/login', { username: user.username, password: config.password }, user); return; }
  const index = __ITER % 100;
  const write = __ENV.MODE === 'mixed' && index >= 90;
  const post = String(Math.floor(__ITER / 6) % 10 < 9 ? 1 + (__VU % 10) : 11 + ((__ITER * 17 + __VU * 13) % 1990));
  if (write) {
    const action = index % 3;
    if (action === 0) request('comment_write', 'POST', `/api/posts/${post}/comments`, { content: `load ${__VU} ${__ITER}` }, user);
    else {
      const kind = action === 1 ? 'likes' : 'favorites';
      request(kind, 'PUT', `/api/interactions/${kind}`, { targetId: post, active: Math.floor(__ITER / 100) % 2 === 0 }, user);
    }
    return;
  }
  const name = names[index % 6];
  const page = index % 12 >= 6 && cursors[name] ? `&cursor=${encodeURIComponent(cursors[name])}` : '';
  const paths = {
    feed: `/feed/?count=20&accept_video_clip=true${page}`,
    following: `/api/feed/following?count=20${page}`,
    detail: `/api/posts/${post}`,
    comments: `/api/posts/${post}/comments?count=10`,
    search: `/api/search/posts?q=${encodeURIComponent('压测')}&count=20${page}`,
    notifications: '/api/users/me/notifications?type=LIKE&count=20',
  };
  const result = request(name, 'GET', paths[name], null, user);
  if (result && Object.prototype.hasOwnProperty.call(result, 'next_cursor')) cursors[name] = result.next_cursor;
}
export function handleSummary(data) {
  return { [__ENV.SUMMARY_FILE]: JSON.stringify(data, null, 2) };
}

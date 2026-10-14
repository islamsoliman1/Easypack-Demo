import assert from 'node:assert/strict';

// Run only against the isolated local demo; never against a shared deployment.
const base = process.env.DEMO_URL ?? 'http://localhost:18090';
assert.ok(['localhost', '127.0.0.1'].includes(new URL(base).hostname));
const cookies = new Map();
let token;
let csrf;
async function request(path, { method='GET', body, authenticated=true, csrfEnabled=true, origin }={}) {
  const headers = { 'Content-Type': 'application/json' };
  if (cookies.size) headers.Cookie = [...cookies].map(([k,v]) => `${k}=${v}`).join('; ');
  if (token && authenticated) headers.Authorization = `Bearer ${token}`;
  if (csrf && csrfEnabled) headers['X-XSRF-TOKEN'] = csrf;
  if (origin) headers.Origin = origin;
  const response = await fetch(`${base}${path}`, { method, headers, body: body===undefined?undefined:JSON.stringify(body), signal: AbortSignal.timeout(15000) });
  for (const cookie of response.headers.getSetCookie()) {
    const pair = cookie.split(';')[0]; const equals = pair.indexOf('=');
    cookies.set(pair.slice(0,equals), pair.slice(equals+1));
  }
  return response;
}
async function json(path, options) {
  const response = await request(path, options);
  assert.ok(response.ok, `${options?.method??'GET'} ${path}: ${response.status} ${await response.clone().text()}`);
  return response.json();
}
async function csrfToken() { csrf = (await json('/api/v1/auth/csrf', {authenticated:false})).token; }

assert.equal((await request('/actuator/health/readiness')).status,200);
assert.equal((await request('/api/v1/machine-models', {authenticated:false})).status,401);
assert.equal((await request('/api/v1/auth/demo', {method:'POST',csrfEnabled:false,authenticated:false})).status,403);
await csrfToken();
const session = await json('/api/v1/auth/demo',{method:'POST',authenticated:false});
token = session.accessToken;
assert.equal(session.user.email,'visitor@demo.example');
assert.equal(session.user.mustChangePassword,false);
assert.ok(session.user.permissions.includes('orders.release'));
assert.ok(!session.user.permissions.includes('users.create'));
assert.ok(!session.user.permissions.includes('roles.update'));
console.log('PASS: passwordless visitor session, API authentication and CSRF boundary');

const orders = await json('/api/v1/manufacturing-orders');
assert.equal(orders.filter(o=>o.code.startsWith('DEMO-ORDER-')).length,7);
for (const state of ['DRAFT','OPEN','IN_PROGRESS','AWAITING_TESTING','COMPLETED','REWORK'])
  assert.ok(orders.some(o=>o.status===state),state);
for (const path of ['/parts','/machines','/warehouse/balances','/warehouse/raw/units','/assembly/tasks','/defects','/operations/orders','/operations/templates','/warehouse/preparations','/users','/roles']) {
  const data = await json(`/api/v1${path}`);
  assert.ok(Array.isArray(data) && data.length>0,path);
}
const dates=orders.map(o=>o.deliveryAt.slice(0,10)).sort();
const report=await json(`/api/v1/reports?from=${dates[0]}&to=${dates.at(-1)}`);
assert.ok(report.total>=7);
assert.equal((await request(`/api/v1/reports/export?from=${dates[0]}&to=${dates.at(-1)}`)).status,200);
for (const path of ['/', '/orders', `/orders/${orders[0].id}`, '/operations/templates', '/reports']) {
  const response=await request(path,{authenticated:false});
  assert.equal(response.status,200,path);
  assert.ok((await response.text()).includes('EasyPack Demo'),path);
}
console.log('PASS: seeded manufacturing workflow, reports, CSV and direct SPA routes');

await csrfToken();
const code=`VERIFY-${Date.now()}`;
const created=await json('/api/v1/machine-models',{method:'POST',body:{code,name:'اختبار حفظ الديمو',description:'Synthetic verification fixture',active:true,version:0}});
assert.ok((await json('/api/v1/machine-models')).some(model=>model.id===created.id));
await json(`/api/v1/machine-models/${created.id}`,{method:'PUT',body:{...created,active:false}});
assert.equal((await request('/api/v1/roles',{method:'POST',body:{name:'Denied',description:'',permissions:[],version:0}})).status,403);
assert.equal((await request('/api/v1/auth/password',{method:'POST',body:{currentPassword:'unused',newPassword:'unused'}})).status,403);
assert.equal((await request('/api/v1/auth/demo',{method:'POST',authenticated:false,origin:'https://untrusted.example'})).status,403);
console.log('PASS: real database writes, administrative restrictions and cross-origin rejection');

await csrfToken();
const refreshed=await json('/api/v1/auth/refresh',{method:'POST',authenticated:false});
token=refreshed.accessToken;
assert.equal((await json('/api/v1/auth/me')).id,session.user.id);
await csrfToken();
assert.equal((await request('/api/v1/auth/logout',{method:'POST',authenticated:false})).status,204);
assert.equal((await request('/api/v1/auth/me')).status,401);
await csrfToken();
const fresh=await json('/api/v1/auth/demo',{method:'POST',authenticated:false});
assert.equal(fresh.user.id,session.user.id);
console.log('PASS: refresh, revocation and automatic re-entry');

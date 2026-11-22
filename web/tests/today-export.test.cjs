const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./helpers.cjs');

test('from=today flag captures into sessionStorage and clears cleanly', () => {
  const h = harness();
  const mod = h.load('today-export.ts');
  assert.equal(mod.isFromToday(''), false);
  assert.equal(mod.captureFromTodayFlag('?from=today'), true);
  assert.equal(h.session.get(mod.FROM_TODAY_KEY), '1');
  assert.equal(mod.isFromToday(''), true);
  mod.clearFromTodayFlag();
  assert.equal(mod.isFromToday(''), false);
});

test('deep link whitelist accepts only exact 12-char unambiguous stu-import URIs', () => {
  const { load } = harness();
  const mod = load('today-export.ts');
  assert.equal(mod.isAllowedTodayDeepLink('today://sheet/stu-import?code=2345ABCDJKLM'), true);
  for (const bad of [
    'today://sheet/stu-import?code=2345ABCDJKL',
    'today://sheet/stu-import?code=2345ABCDJKLMO',
    'today://sheet/stu-import?code=0000OOOO1111',
    'today://sheet/other?code=2345ABCDJKLM',
    'javascript:alert(1)',
    'https://today.oyishi.asia/',
    'today://sheet/stu-import?code=2345ABCDJKLM&extra=1',
  ]) {
    assert.equal(mod.isAllowedTodayDeepLink(bad), false, bad);
  }
});

test('formatExportCode, campusLabel and api.exportToday follow the backend contract', async () => {
  const h = harness();
  const apiMod = h.load('api.ts');
  const mod = h.load('today-export.ts');
  assert.equal(mod.formatExportCode('2345abcdjklm'), '2345 ABCD JKLM');
  assert.equal(mod.campusLabel('main'), '汕大本部');
  assert.equal(mod.campusLabel('med'), '汕大医学院');
  assert.equal(mod.campusLabel('manual'), '手动导入');

  apiMod.setToken('test.jwt.token');
  let seen = null;
  h.state.fetch = async (url, init) => {
    seen = { url, method: init.method, body: init.body, auth: init.headers.get('Authorization') };
    return new Response(JSON.stringify({
      code: '2345ABCDJKLM',
      expires_in: 300,
      deep_link: 'today://sheet/stu-import?code=2345ABCDJKLM',
      semester: '2026-2027-1',
      campus: 'med',
      course_count: 6,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  };
  const ticket = await apiMod.api.exportToday('2026-2027-1');
  assert.equal(seen.url, '/v1/export/today');
  assert.equal(seen.method, 'POST');
  assert.equal(seen.body, JSON.stringify({ semester: '2026-2027-1' }));
  assert.equal(seen.auth, 'Bearer test.jwt.token');
  assert.equal(ticket.campus, 'med');
  assert.equal(ticket.course_count, 6);
});

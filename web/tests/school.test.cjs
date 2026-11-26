const assert = require('node:assert/strict');
const test = require('node:test');
const { harness } = require('./helpers.cjs');

test('school credential storage never writes plaintext passwords and purges legacy keys', async () => {
  const h = harness();
  h.storage.set('stu.school', '{"account":"20230001","password":"legacy-plain-password"}');
  h.storage.set('stu.school:local|user@example.com', '{"account":"20230001","password":"legacy-scoped-password"}');
  h.storage.set('stu.theme', 'dark');
  h.storage.set('stu.token', 'keep-token');

  const school = h.load('school.ts');

  assert.equal(school.readSchool(), null);
  assert.equal(h.storage.has('stu.school'), false);
  assert.equal(h.storage.has('stu.school:local|user@example.com'), false);
  assert.equal(h.storage.get('stu.theme'), 'dark');
  assert.equal(h.storage.get('stu.token'), 'keep-token');

  h.storage.set('stu.school:https://stu.oyishi.asia|a@b.c', '{"account":"20230002","password":"p"}');
  school.saveSchool('20230002', 'secret-password');
  assert.equal(h.storage.has('stu.school:https://stu.oyishi.asia|a@b.c'), false);
  for (const [k, v] of h.storage.entries()) {
    assert.ok(!k.startsWith('stu.school'), `unexpected school key persisted: ${k}`);
    assert.ok(!v.includes('secret-password'), `password leaked into key ${k}`);
  }

  let fetchCalls = 0;
  h.state.fetch = async () => {
    fetchCalls += 1;
    return new Response('{}');
  };
  h.storage.set('stu.school:local|anon', '{"account":"20230003","password":"old"}');
  assert.equal(await school.autoRelogin(['jw', 'mystu']), false);
  assert.equal(fetchCalls, 0);
  assert.equal(h.storage.has('stu.school:local|anon'), false);
});

test('web product version constants match v1.2.0 / 68', () => {
  const h = harness();
  const version = h.load('version.ts');
  assert.equal(version.APP_VERSION_NAME, '1.2.0');
  assert.equal(version.APP_VERSION_CODE, 68);
});

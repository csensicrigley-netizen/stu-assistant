const assert = require('node:assert/strict');
const test = require('node:test');
const { harness } = require('./helpers.cjs');

function clients(h) {
  const { api } = h.load('api.ts');
  const { medApi } = h.load('med-api.ts');
  const { planApi } = h.load('plan-api.ts');
  const { importApi } = h.load('timetable-import.ts');
  return {
    core: () => api.me(), medical: () => medApi.status(), plan: () => planApi.list(),
    import: () => importApi.load('2026-2027-1'), template: () => importApi.template(),
  };
}

for (const name of ['core', 'medical', 'plan', 'import', 'template']) {
  for (const phase of ['headers', 'body']) {
    test(`${name}: late 401 ${phase} from A cannot sign out B`, async () => {
      const h = harness();
      const api = h.load('api.ts');
      api.setToken('token-A');
      let release;
      h.state.fetch = async () => {
        if (phase === 'headers') return new Promise(resolve => {
          release = () => resolve(new Response('{"detail":"invalid_token"}', { status: 401 }));
        });
        return new Response(new ReadableStream({
          start(controller) {
            release = () => {
              controller.enqueue(new TextEncoder().encode('{"detail":"invalid_token"}'));
              controller.close();
            };
          },
        }), { status: 401 });
      };
      const pending = clients(h)[name]();
      api.setToken('token-B');
      release();
      await assert.rejects(pending, e => e.code === 'invalid_token');
      assert.equal(api.getToken(), 'token-B');
      assert.deepEqual(h.events, []);
    });
  }

  test(`${name}: current session 401 still requests sign-out`, async () => {
    const h = harness();
    h.load('api.ts').setToken('token-A');
    h.state.fetch = async () => new Response('{"detail":"invalid_token"}', { status: 401 });
    await assert.rejects(clients(h)[name](), e => e.status === 401 && e.code === 'invalid_token');
    assert.deepEqual(h.events, ['stu:auth-lost']);
  });

  test(`${name}: timeout aborts a body that never finishes`, { timeout: 2000 }, async () => {
    const h = harness();
    const signals = [];
    h.state.fetch = async (_url, init) => {
      signals.push(init.signal);
      return new Response(new ReadableStream({
        start(controller) {
          init.signal.addEventListener('abort', () => controller.error(new Error('aborted')), { once: true });
        },
      }));
    };
    await assert.rejects(clients(h)[name](), e => e.code === 'timeout');
    assert.ok(signals.length >= 1);
    assert.ok(signals.every(signal => signal.aborted));
  });

  test(`${name}: validation errors keep their shared ApiError type`, async () => {
    const h = harness();
    const { ApiError } = h.load('api.ts');
    h.state.fetch = async () => new Response('{"detail":[{"msg":"required"}]}', { status: 422 });
    await assert.rejects(clients(h)[name](), e => e instanceof ApiError && e.code === 'invalid_input');
  });
}

test('server switch makes the previous server 401 stale even with the same token', async () => {
  const h = harness({ native: true });
  const api = h.load('api.ts');
  api.setApiBase('https://old.example.invalid');
  api.setToken('test-token');
  let release;
  h.state.fetch = () => new Promise(resolve => { release = resolve; });
  const pending = clients(h).medical();
  api.setApiBase('https://new.example.invalid');
  release(new Response('{}', { status: 401 }));
  await assert.rejects(pending, e => e.status === 401);
  assert.deepEqual(h.events, []);
});

test('a rejected sign-in does not broadcast session loss', async () => {
  const h = harness();
  const { api, setToken } = h.load('api.ts');
  setToken('current-token');
  h.state.fetch = async () => new Response('{"detail":"bad_credentials"}', { status: 401 });
  await assert.rejects(api.login('fake@example.invalid', 'fake'), e => e.code === 'bad_credentials');
  assert.deepEqual(h.events, []);
});

test('plan PATCH preserves authentication, JSON body and endpoint response', async () => {
  const h = harness();
  h.load('api.ts').setToken('test-token');
  const expected = { id: 'p1', title: 'Updated' };
  h.state.fetch = async (url, init) => {
    assert.equal(url, '/v1/plan/p1');
    assert.equal(init.method, 'PATCH');
    assert.equal(init.headers.get('Authorization'), 'Bearer test-token');
    assert.equal(init.headers.get('Content-Type'), 'application/json');
    assert.deepEqual(JSON.parse(init.body), { title: 'Updated' });
    return new Response(JSON.stringify(expected));
  };
  const result = await h.load('plan-api.ts').planApi.update('p1', { title: 'Updated' });
  assert.equal(JSON.stringify(result), JSON.stringify(expected));
});

test('CSV template is returned as text, not parsed as JSON', async () => {
  const h = harness();
  h.state.fetch = async (_url, init) => {
    assert.equal(init.headers.get('Accept'), 'text/csv');
    return new Response('课程,星期\nTest,1');
  };
  assert.equal(await clients(h).template(), '课程,星期\nTest,1');
});

test('import JSON calls retain single-attempt behavior on network failure', async () => {
  const h = harness();
  let calls = 0;
  h.state.fetch = async () => { calls += 1; throw new Error('offline'); };
  await assert.rejects(clients(h).import(), e => e.code === 'timeout');
  assert.equal(calls, 1);
});

test('ordinary GET still retries once on network failure', async () => {
  const h = harness();
  let calls = 0;
  h.state.fetch = async () => {
    if (++calls === 1) throw new Error('offline');
    return new Response('{"id":"me"}');
  };
  assert.equal((await clients(h).core()).id, 'me');
  assert.equal(calls, 2);
});

test('malformed success JSON is reported as ApiError, with no retry of a completed request', async () => {
  const h = harness();
  let calls = 0;
  h.state.fetch = async () => { calls += 1; return new Response('not JSON'); };
  await assert.rejects(clients(h).core(), e => e.code === 'network');
  assert.equal(calls, 1);
});

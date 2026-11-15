const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./helpers.cjs');

const item = (overrides = {}) => ({
  id: 'github-star', revision: 1, title: '喜欢这个小助手？', body: '给项目点一颗 Star。',
  eyebrow: '开发者来信', image_url: null, image_alt: '',
  action_url: 'https://github.com/csensicrigley-netizen/stu-assistant', action_label: '去 GitHub 点 Star',
  display: 'once', priority: 0, ...overrides,
});
const load = () => { const h = harness(); return { ...h, a: h.load('announcements.ts') }; };
const reopen = (previous) => {
  const next = load();
  for (const [key, value] of previous.storage) next.storage.set(key, value);
  return next;
};

test('effects accept only bundled IDs, default off, and deduplicate remote values', () => {
  const { a } = load();
  const parse = (effects) => a.parseAnnouncements({ announcements: [item({ effects })] })[0].effects;
  assert.equal(JSON.stringify(parse(undefined)), '[]');
  assert.equal(JSON.stringify(parse(['star-struck', '../private', 'party-popper', 'star-struck', 12])), '["star-struck","party-popper"]');
  assert.equal(JSON.stringify(parse('https://example.test/remote.json')), '[]');
});

test('receipts isolate servers/accounts, revisions, and suppress all further messages this opening', () => {
  const h = load();
  const first = item();
  assert.equal(h.a.announcementEligible(first, 'server-a/user-a'), true);
  h.a.markAnnouncementShown(first, 'server-a/user-a');
  assert.equal(h.a.announcementEligible(item({ id: 'another', display: 'always' }), 'server-a/user-a'), false);
  const next = reopen(h);
  assert.equal(next.a.announcementEligible(first, 'server-a/user-a'), false);
  assert.equal(next.a.announcementEligible(item({ revision: 2 }), 'server-a/user-a'), true);
  assert.equal(next.a.announcementEligible(first, 'server-a/user-b'), true);
  assert.equal(next.a.announcementEligible(first, 'server-b/user-a'), true);
});

test('daily changes at Shanghai midnight; always may display again next opening', () => {
  const h = load();
  const daily = item({ display: 'daily' });
  const before = new Date('2026-11-14T15:59:59Z');
  const after = new Date('2026-11-14T16:00:00Z');
  assert.equal(h.a.announcementDay(before), '2026-11-14');
  assert.equal(h.a.announcementDay(after), '2026-11-15');
  h.a.markAnnouncementShown(daily, 'daily', before);
  h.a.markAnnouncementShown(item({ display: 'always' }), 'always', before);
  const next = reopen(h);
  assert.equal(next.a.announcementEligible(daily, 'daily', before), false);
  assert.equal(next.a.announcementEligible(daily, 'daily', after), true);
  assert.equal(next.a.announcementEligible(item({ display: 'always' }), 'always', after), true);
});

test('untrusted feed rejects malformed messages and unsafe links/images, sorts valid messages', () => {
  const { a } = load();
  assert.equal(a.parseAnnouncements(null).length, 0);
  assert.equal(a.parseAnnouncements({ announcements: 'bad' }).length, 0);
  const result = a.parseAnnouncements({ announcements: [
    item({ revision: -1 }), item({ display: 'weekly' }), item({ title: '' }),
    item({ id: 'unsafe', action_url: 'javascript:alert(1)', image_url: '//tracker.test/pixel', priority: 2 }),
    item({ id: 'priority', priority: 10 }),
  ] });
  assert.equal(result.length, 2);
  assert.equal(result[0].id, 'priority');
  assert.equal(result[1].action_url, null);
  assert.equal(result[1].image_url, null);
  assert.equal(a.safeAnnouncementLink('http://github.com/project'), null);
  assert.equal(a.safeAnnouncementLink('https://user:password@github.com'), null);
  assert.equal(a.announcementImageUrl('/v1/announcements/assets/hero.webp', 'https://server.test'), 'https://server.test/v1/announcements/assets/hero.webp');
  assert.equal(a.announcementImageUrl('/v1/announcements/assets/../secret', 'https://server.test'), null);
  assert.equal(a.announcementImageUrl('/v1/announcements/assets/%2fsecret', 'https://server.test'), null);
});

test('public feed omits credentials, does not dispatch auth loss, and checks version/platform', async () => {
  const h = load();
  h.load('http.ts').setToken('private-token');
  h.state.fetch = async (url, init) => {
    assert.match(url, /\/v1\/announcements\?platform=web&version_code=\d+$/);
    assert.equal(init.credentials, 'omit');
    assert.equal(init.cache, 'no-store');
    assert.equal(init.headers.Authorization, undefined);
    return new Response('{}', { status: 401 });
  };
  assert.equal((await h.a.fetchAnnouncements(new AbortController().signal)).length, 0);
  assert.equal(h.events.length, 0);
});

test('scope stores only server and JWT subject, never the bearer token', () => {
  const { a } = load();
  const token = `header.${Buffer.from(JSON.stringify({ sub: 'user-123' })).toString('base64url')}.secret`;
  assert.equal(a.announcementScope('https://api.example/', token, 'https://web.example'), '["https://api.example","user-123"]');
  assert.equal(a.announcementScope('', null, 'https://web.example'), '["https://web.example","guest"]');
  assert.equal(a.announcementScope('', 'broken-secret-token', 'https://web.example').includes('secret'), false);
});

const assert = require('node:assert/strict');
const test = require('node:test');
const { harness } = require('./helpers.cjs');

for (const zone of ['UTC', 'America/Chicago', 'Asia/Shanghai']) {
  test(`school deadlines are independent of device timezone (${zone})`, () => {
    const previous = process.env.TZ;
    process.env.TZ = zone;
    try {
      const { normalizeTask } = harness().load('records.ts');
      const expected = Date.parse('2026-10-29T23:59:00+08:00');
      for (const due of ['2026-10-29', '2026-10-29T23:59', '2026-10-29T23:59:00', '2026-10-29 23:59:00', '2026-10-29T23:59:00+08:00', '2026-10-29T15:59:00Z']) {
        const task = normalizeTask({ due });
        assert.equal(task.dueMs, expected, due);
        assert.equal(task.dueRaw, due);
      }
      assert.equal(normalizeTask({ due: '2026-10-29T23:59:00-05:00' }).dueMs,
                   Date.parse('2026-10-29T23:59:00-05:00'));
      assert.equal(normalizeTask({ due: 'bad', dueMs: expected }).dueMs, expected);
      assert.equal(normalizeTask({ due_at: '2026-10-29 23:59:00' }).dueMs, expected);
      assert.equal(normalizeTask({ due: '待定' }).dueMs, null);
      assert.equal(normalizeTask({}).dueMs, null);
    } finally {
      if (previous === undefined) delete process.env.TZ;
      else process.env.TZ = previous;
    }
  });
}

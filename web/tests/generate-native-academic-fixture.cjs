// Re-run from the repository root:
// node web/tests/generate-native-academic-fixture.cjs
// Expected values come from the real web domain modules, not a second implementation.
const fs = require('node:fs');
const path = require('node:path');
const { harness } = require('./helpers.cjs');
const h = harness();
const clock = h.load('clock.ts');
const records = h.load('records.ts');
const tt = h.load('timetable.ts');
const calendar = h.load('calendar.ts');
const plan = h.load('plan.ts');
const now = Date.parse('2026-10-28T10:20:00+08:00');
const due = Date.parse('2026-10-29T23:59:00+08:00');
const taskInputs = [
  ...['2026-10-29', '2026-10-29T23:59', '2026-10-29 23:59:00', '2026-10-29T15:59:00Z', '2026-10-29T23:59:00-05:00', '待定', ''].map(due => ({ due })),
  { due: 'bad', dueMs: due }, { due_at: '2026-10-28T10:30:00' },
  { due: '2026-10-28T10:19:00' }, { due: '2026-10-13T10:19:00' },
  { due: '2026-11-02' }, { due: '2026-10-29', due_at: '2026-10-30' },
  { source: 'yuketang', id: 'yuketang-1', title: '任务', school_status: '未提交', url: 'https://example.edu/task' },
];
const meeting = (periods, location = 'A101', extra = {}) => ({ weekday: 3, weeks: [8, 9], periods, location, raw_time: '原始课时', ...extra });
const timetable = { semester: '2026-2027-1', courses: [
  { id: 'a', title: 'A100', meetings: [meeting([1]), meeting([2])] },
  { id: 'b', title: 'B155', meetings: [meeting([3, 4, 5], 'B202')] },
  { id: 'c', title: 'C午间分开', meetings: [meeting([5, 6], 'C303')] },
  { id: 'd', title: 'D晚饭分开', meetings: [meeting([10, 11], 'D404')] },
  { id: 'e', title: 'E换教室', meetings: [meeting([8], 'E501'), meeting([9], 'E502')] },
  { id: 'f', title: 'F过滤未知', meetings: [meeting([1.5, 0, 99, 13], 'F601', { weeks: '8-9', notes: '保留备注', online_info: '保留线上说明' })] },
] };
const doc = tt.normalizeTimetable(timetable);
const dates = ['2026-09-20', '2026-09-25', '2026-10-01', '2026-10-10', '2026-10-28', '2026-12-03', '2027-01-30', '2027-03-01', '2027-08-29'];
const examInputs = [
  { title: '原文考试', exam_time: '2026年11月2日 8:00–10:00', location: 'E208', seat_number: '17', exam_campus: '东海岸', notes: '携带证件' },
  { title: '仅有日期', exam_time: '2026/11/3' },
  { title: '尚未安排', exam_time: '由学院另行通知', notes: '原文保留' },
];
const grades = [
  { title: '数值成绩', semester: '2026-2027-1', score: '90', credits: 2, gpa: 4 },
  { title: '等级成绩', semester: '2026-2027-1', score: '优秀', credits: 3, gpa: 3.5 },
  { title: '有成绩无绩点', semester: '2026-2027-1', score: '60', credits: 1, gpa: null },
  { title: '重修逐条计入', semester: '2026-2027-1', score: '80', credits: 2, gpa: 3, exam_type: '重修' },
];
const output = {
  source: 'web/src/lib/{clock,records,timetable,calendar,plan}.ts via tests/helpers.cjs', now,
  clock: ['2026-10-28T00:05:00+08:00', '2026-10-27T16:05:00Z', '2026-10-28T00:05:00-05:00'].map(input => { const m = clock.moment(Date.parse(input)); return { input, expected: { ms: m.ms, date: m.date, weekday: m.weekday, minutes: m.minutes } }; }),
  tasks: taskInputs.map(input => { const expected = records.normalizeTask(input); return { input, expected, bucket: records.dueBucket(expected, now) }; }),
  timetable: { input: timetable, blocks: tt.blocksOn(doc, 8, 3).map(b => ({ key: b.key, periods: b.periods, start: b.start, end: b.end, span: b.span, location: b.location, inferred: b.inferred, conflict: b.conflict })),
    gaps: tt.gapsBetween(tt.blocksOn(doc, 8, 3)), slots: tt.courseSlots(doc.courses[5]), progress: tt.courseProgress(doc.courses[0], 8, 3, 600) },
  calendar: dates.map(date => ({ date, status: calendar.dayStatus(date), week: calendar.termPosition(date)?.week ?? null, blocks: plan.planFor(doc, date).blocks.length })),
  milestones: calendar.upcomingMilestones('2026-10-28', 4),
  exams: examInputs.map(input => ({ input, expected: records.normalizeExam(input) })),
  grades: { input: grades, expected: records.summarize(grades.map(records.normalizeGrade)) },
};
const target = path.resolve(__dirname, '../android/app/src/test/resources/native-academic-js-parity.json');
fs.mkdirSync(path.dirname(target), { recursive: true });
fs.writeFileSync(target, JSON.stringify(output, null, 2) + '\n');
console.log(`Wrote ${path.relative(process.cwd(), target)} from real JS domain functions.`);

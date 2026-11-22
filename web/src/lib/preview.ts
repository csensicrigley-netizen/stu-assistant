/**
 * Preview data: what the App shows before an account is connected.
 *
 * Timetable, rooms, teachers, tasks, exams and grades are all synthetic sample
 * data in stu-mcp's shape, and every screen that shows them says "示例".
 */
import { addDaysIso, dayStartMs, moment, nowMs } from "./clock";

const BIG: Record<string, number[]> = {
  第一大节: [1, 2],
  第二大节: [3, 4, 5],
  第三大节: [6, 7],
  第四大节: [8, 9],
  第五大节: [11, 12, 13],
};

const T = [3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18];

type Row = [weekday: number, section: string, title: string, teacher: string, room: string, weeks: number[]];

const ROWS: Row[] = [
  [1, "第一大节", "微积分（二）", "林致远", "明理楼-A201", T],
  [1, "第三大节", "大学英语写作", "陈思宁", "博雅楼-B108", T],
  [2, "第二大节", "数据结构与算法", "周景行", "致知楼-C304", T],
  [2, "第三大节", "概率论与数理统计", "许明川", "明理楼-A305", T],
  [3, "第二大节", "人机交互设计", "唐若琳", "创新工坊-202", T],
  [3, "第五大节", "现代设计史导论", "梁书瑶", "文澜楼-阶梯三", T],
  [4, "第一大节", "数据结构与算法", "周景行", "致知楼-C304", T],
  [4, "第二大节", "信息可视化基础", "程砚秋", "致知楼-C412", T],
  [4, "第三大节", "前端工程实践", "沈清扬", "实验楼-数字媒体室508", T],
  [4, "第四大节", "前端工程实践", "沈清扬", "实验楼-数字媒体室508", T],
  [5, "第二大节", "数据库系统原理", "宋闻达", "致知楼-C208", T],
  [6, "第二大节", "创新项目综合实践", "唐若琳", "创客空间-301", [6, 7, 8, 11, 12]],
  [6, "第三大节", "创新项目综合实践", "唐若琳", "创客空间-301", [6, 7, 8, 11, 12]],
  [7, "第二大节", "跨学科研讨课", "程砚秋", "文澜楼-研讨室105", [9, 10, 12, 13]],
];

function weeksRaw(weeks: number[]): string {
  return weeks.length === T.length ? "3-18(周)" : `${weeks.join(",")}(周)`;
}

export const PREVIEW_TIMETABLE = (() => {
  const courses = new Map<string, { id: string; title: string; teacher: string; meetings: unknown[] }>();
  ROWS.forEach(([weekday, section, title, teacher, room, weeks], i) => {
    const key = `${title}|${teacher}`;
    const course = courses.get(key) ?? { id: `preview:course:${courses.size + 1}`, title, teacher, meetings: [] };
    const periods = BIG[section];
    course.meetings.push({
      id: `preview:meeting:${i + 1}`,
      weekday,
      weeks,
      periods,
      teacher,
      location: room,
      raw_time: `${weeksRaw(weeks)}[${periods.map((p) => String(p).padStart(2, "0")).join("-")}节]`,
      notes: "",
      online_info: "",
    });
    courses.set(key, course);
  });
  return {
    ok: true,
    status: "ok",
    source: "jw",
    semester: "2026-2027-1",
    checked_at: "2026-10-28T01:52:12+00:00",
    courses: [...courses.values()].map((c) => ({
      id: c.id,
      title: c.title,
      course_code: "",
      class_name: "",
      teachers: [c.teacher],
      teacher: c.teacher,
      meetings: c.meetings,
    })),
    unarranged: [],
    unplaced_details: [],
    pending: [],
    coverage: { kind: "theory", complete: true, period_times_verified: false },
  };
})();

/** Synthetic, relative to "now", so the deadline groups always have content. */
export function previewTasks() {
  const today = moment().date;
  const at = (days: number, clock: string) => `${addDaysIso(today, days)}T${clock}:00+08:00`;
  return [
    { id: "preview:task:1", title: "第三次实验：二叉树遍历与最短路径", course: "数据结构与算法", source: "mystu", due_at: at(0, "23:59"), url: null },
    { id: "preview:task:2", title: "随堂小测 4：条件概率与贝叶斯", course: "概率论与数理统计", source: "yuketang", due_at: at(1, "12:00"), url: null },
    { id: "preview:task:3", title: "原型评审报告：校园导览交互草图", course: "人机交互设计", source: "mystu", due_at: at(3, "18:00"), url: null },
    { id: "preview:task:4", title: "案例分析：多维时间序列图表拆解", course: "信息可视化基础", source: "mystu", due_at: at(8, "23:59"), url: null },
    { id: "preview:task:5", title: "期中知识点整理提纲", course: "数据库系统原理", source: "mystu", due_at: "", url: null },
    { id: "preview:task:6", title: "第一次作业：SQL 关联查询练习", course: "数据库系统原理", source: "mystu", due_at: at(-1, "23:59"), url: null },
  ];
}

export const PREVIEW_EXAMS = [
  { id: "preview:exam:1", title: "数据结构与算法", exam_time: "2027-01-12 08:00-10:00", location: "致知楼-C304", seat_number: "028" },
  { id: "preview:exam:2", title: "概率论与数理统计", exam_time: "2027-01-13 13:30-15:30", location: "明理楼-A305", seat_number: "106" },
  { id: "preview:exam:3", title: "数据库系统原理", exam_time: "2027-01-15 10:30-12:30", location: "致知楼-C208", seat_number: "" },
  { id: "preview:exam:4", title: "人机交互设计", exam_time: "", location: "", seat_number: "" },
];

export const PREVIEW_GRADES = [
  { id: "preview:grade:1", semester: "2025-2026-2", title: "程序设计基础（二）", score: "91", credits: 3.5, gpa: 3.9, exam_type: "正常考试" },
  { id: "preview:grade:2", semester: "2025-2026-2", title: "线性代数", score: "88", credits: 3, gpa: 3.6, exam_type: "正常考试" },
  { id: "preview:grade:3", semester: "2025-2026-2", title: "数字逻辑与计算机组成", score: "85", credits: 3, gpa: 3.4, exam_type: "正常考试" },
  { id: "preview:grade:4", semester: "2025-2026-2", title: "体育（二）", score: "良好", credits: 1, gpa: null, exam_type: "正常考试" },
  { id: "preview:grade:5", semester: "2025-2026-2", title: "大学英语（二）", score: "82", credits: 2, gpa: 3.1, exam_type: "正常考试" },
  { id: "preview:grade:6", semester: "2025-2026-1", title: "微积分（一）", score: "76", credits: 4, gpa: 2.6, exam_type: "正常考试" },
  { id: "preview:grade:7", semester: "2025-2026-1", title: "计算机导论", score: "93", credits: 3, gpa: 4.0, exam_type: "正常考试" },
  { id: "preview:grade:8", semester: "2025-2026-1", title: "离散数学", score: "64", credits: 4, gpa: 1.4, exam_type: "补考" },
];

export function previewNotices() {
  const now = nowMs();
  const day = (n: number) => moment(dayStartMs(moment(now).date) - n * 86_400_000 + 3_600_000).date;
  return [
    { id: "preview:n1", source: "public", title: "关于举办第四届全国大学生职业规划大赛校赛的通知", category: "学生通知", published_at: day(0), url: "https://www.stu.edu.cn/" },
    { id: "preview:n2", source: "public", title: "通识学术讲座：人工智能与数据可视化前沿", category: "活动预告", published_at: day(0), url: "https://www.stu.edu.cn/" },
    { id: "preview:n3", source: "public", title: "图书馆 2026 年秋季学期信息检索工作坊安排", category: "活动预告", published_at: day(1), url: "https://www.stu.edu.cn/" },
    { id: "preview:n4", source: "oa", title: "关于 2026 年秋季学期期中教学检查工作的通知", department: "教务处", published_at: day(1), url: "http://oa.stu.edu.cn/" },
    { id: "preview:n5", source: "public", title: "我校学子在全国大学生创新创业竞赛中获佳绩", category: "学校要闻", published_at: day(2), url: "https://www.stu.edu.cn/" },
    { id: "preview:n6", source: "oa", title: "关于开展 2026 年度学生资助工作的通知", department: "学生处", published_at: day(4), url: "http://oa.stu.edu.cn/" },
  ];
}

/**
 * Preview data: what the App shows before an account is connected.
 *
 * The timetable is a real student's 2026-2027-1 timetable (the project
 * owner's), rewritten into stu-mcp's shape. Tasks, exams and grades are
 * synthetic and every screen that shows them says "示例". Notice titles are
 * public listings from www.stu.edu.cn.
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
  [2, "第二大节", "生物信息学", "温以舟", "桑浦山-D座206", T],
  [2, "第三大节", "基因组学", "沈知白", "桑浦山-D座206", T],
  [3, "第二大节", "生物统计学", "苏念", "桑浦山-E305", T],
  [3, "第五大节", "习近平新时代中国特色社会主义思想概论", "顾清和", "桑浦山-E301", T],
  [4, "第一大节", "细胞生物学", "陆星野", "桑浦山-讲堂五", T],
  [4, "第二大节", "基础生态学", "叶青梧", "桑浦山-E401", T],
  [4, "第三大节", "细胞生物学实验", "何昭明", "桑浦山-细胞生物学实验室—理433、434", T],
  [4, "第四大节", "细胞生物学实验", "何昭明", "桑浦山-细胞生物学实验室—理433、434", T],
  [5, "第二大节", "表观遗传学", "陆星野", "桑浦山-D座206", T],
  [6, "第一大节", "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 11]],
  [6, "第二大节", "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 11]],
  [6, "第三大节", "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 11]],
  [6, "第四大节", "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 11]],
  [7, "第一大节", "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 9]],
  [7, "第二大节", "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 9]],
  [7, "第三大节", "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 9]],
  [7, "第四大节", "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 9]],
  [6, "第二大节", "基础生态学实验", "叶青梧", "桑浦山-生态学实验室", [9, 10, 12, 13]],
  [7, "第二大节", "基础生态学实验", "叶青梧", "桑浦山-生态学实验室", [9, 10, 12, 13]],
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
    { id: "preview:task:1", title: "第二次作业：序列比对与打分矩阵", course: "生物信息学", source: "mystu", due_at: at(0, "23:59"), url: null },
    { id: "preview:task:2", title: "课堂小测 3", course: "生物统计学", source: "yuketang", due_at: at(1, "12:00"), url: null },
    { id: "preview:task:3", title: "实验报告：细胞器的分离与观察", course: "细胞生物学实验", source: "mystu", due_at: at(3, "18:00"), url: null },
    { id: "preview:task:4", title: "文献阅读笔记（Nature Reviews Genetics 综述一篇）", course: "表观遗传学", source: "mystu", due_at: at(8, "23:59"), url: null },
    { id: "preview:task:5", title: "期中复习提纲", course: "基础生态学", source: "mystu", due_at: "", url: null },
    { id: "preview:task:6", title: "第一次作业：GWAS 数据整理", course: "基因组学", source: "mystu", due_at: at(-1, "23:59"), url: null },
  ];
}

export const PREVIEW_EXAMS = [
  { id: "preview:exam:1", title: "生物信息学", exam_time: "2027-01-12 08:00-10:00", location: "E座302", seat_number: "021" },
  { id: "preview:exam:2", title: "生物统计学", exam_time: "2027-01-13 13:30-15:30", location: "E305", seat_number: "114" },
  { id: "preview:exam:3", title: "基因组学", exam_time: "2027-01-15 10:30-12:30", location: "D座206", seat_number: "" },
  { id: "preview:exam:4", title: "表观遗传学", exam_time: "", location: "", seat_number: "" },
];

export const PREVIEW_GRADES = [
  { id: "preview:grade:1", semester: "2025-2026-2", title: "生物化学", score: "89", credits: 3.5, gpa: 3.7, exam_type: "正常考试" },
  { id: "preview:grade:2", semester: "2025-2026-2", title: "微生物学", score: "92", credits: 3, gpa: 4.0, exam_type: "正常考试" },
  { id: "preview:grade:3", semester: "2025-2026-2", title: "遗传学", score: "84", credits: 3, gpa: 3.3, exam_type: "正常考试" },
  { id: "preview:grade:4", semester: "2025-2026-2", title: "体育（二）", score: "良好", credits: 1, gpa: null, exam_type: "正常考试" },
  { id: "preview:grade:5", semester: "2025-2026-2", title: "大学英语（二）", score: "78", credits: 2, gpa: 2.8, exam_type: "正常考试" },
  { id: "preview:grade:6", semester: "2025-2026-1", title: "高等数学（一）", score: "71", credits: 4, gpa: 2.1, exam_type: "正常考试" },
  { id: "preview:grade:7", semester: "2025-2026-1", title: "普通生物学", score: "90", credits: 3, gpa: 4.0, exam_type: "正常考试" },
  { id: "preview:grade:8", semester: "2025-2026-1", title: "高等数学（一）", score: "62", credits: 4, gpa: 1.2, exam_type: "补考" },
];

export function previewNotices() {
  const now = nowMs();
  const day = (n: number) => moment(dayStartMs(moment(now).date) - n * 86_400_000 + 3_600_000).date;
  return [
    { id: "preview:n1", source: "public", title: "关于举办第四届全国大学生职业规划大赛汕头大学校赛的通知", category: "学生通知", published_at: day(0), url: "https://www.stu.edu.cn/" },
    { id: "preview:n2", source: "public", title: "走向现代数学学术报告 – Prof. David Kalaj（No. 984）", category: "活动预告", published_at: day(0), url: "https://www.stu.edu.cn/" },
    { id: "preview:n3", source: "public", title: "STU-GTIIT 物理论坛（No.67）", category: "活动预告", published_at: day(1), url: "https://www.stu.edu.cn/" },
    { id: "preview:n4", source: "oa", title: "关于 2026 年秋季学期期中教学检查工作的通知", department: "教务处", published_at: day(1), url: "http://oa.stu.edu.cn/" },
    { id: "preview:n5", source: "public", title: "我校在第十届全国大学生生命科学竞赛中获佳绩", category: "学校要闻", published_at: day(2), url: "https://www.stu.edu.cn/" },
    { id: "preview:n6", source: "oa", title: "关于开展 2026 年度学生资助工作的通知", department: "学生处", published_at: day(4), url: "http://oa.stu.edu.cn/" },
  ];
}

/**
 * Campus life data that is not about courses: charging ports, the library's
 * hours, getting to campus, and the official entrances.
 *
 * Every list here says where it came from and how far it can be trusted.
 */
import { api, needsServer } from "./api";
import { moment, nowMs } from "./clock";

/* ----------------------------------------------------------------- charging */

export interface PortState {
  num: number;
  state: number;
  label: string;
}

export interface ChargingDevice {
  code: string;
  name: string;
  location: string;
  capacity: number;
  /** 编号与地点是否按机身二维码核对过；false 表示地点名待补 */
  verified?: boolean;
}

export interface ChargingSnapshot {
  device: ChargingDevice;
  counts: { total: number; observed: number; free: number; occupied: number; fault: number; unknown: number };
  ports: PortState[];
  /** true only when the third-party platform actually answered */
  live: boolean;
  at: number | null;
  /** 服务器明确回了「这台没数据」时的原因；有值就不要显示任何数字 */
  unavailable?: string;
}

/**
 * 设备清单（2026-10-29 重做）。
 *
 * 上一版是照 stu-mcp 自带注册表抄的，名字与编号**大面积错位**：
 * 例如 4GR00008480 根本不是"明德书院"，而是"修远书院"；学校文档里也
 * 根本没有"明德书院"这个充电站。所以这一版改成按学校《学校充电桩设备地址》
 * 文档里每台机器上的**二维码**逐台解出来的编号为准（`verified` 标出可信度）。
 *
 * 15 台，不代表全校；4GR00008477 编号有效但地点名无二维码可对，标 verified=false。
 */
export const CHARGING_DEVICES: ChargingDevice[] = [
  { code: "4GR00008492", name: "思源书院左一", location: "思源书院", capacity: 20, verified: true },
  { code: "4GR00008485", name: "思源书院左二", location: "思源书院", capacity: 20, verified: true },
  { code: "4GR00008489", name: "思源书院左三", location: "思源书院", capacity: 20, verified: true },
  { code: "4GR00008490", name: "思源左四", location: "思源书院", capacity: 20, verified: true },
  { code: "4GR00008495", name: "图书馆一", location: "图书馆", capacity: 20, verified: true },
  { code: "4GR00008493", name: "图书馆二", location: "图书馆", capacity: 20, verified: true },
  { code: "4GR00008491", name: "图书馆三", location: "图书馆", capacity: 20, verified: true },
  { code: "4GR00008494", name: "敬一书院", location: "敬一书院", capacity: 20, verified: true },
  { code: "4GR00008498", name: "志诚书院", location: "志诚书院", capacity: 20, verified: true },
  { code: "4GR00008480", name: "修远书院", location: "修远书院", capacity: 20, verified: true },
  { code: "4GR00008484", name: "文科教学楼", location: "文科教学楼", capacity: 20, verified: true },
  { code: "4GR00008497", name: "滑板场一", location: "滑板场", capacity: 20, verified: true },
  { code: "4GR00008488", name: "滑板场二", location: "滑板场", capacity: 20, verified: true },
  { code: "4GR00006723", name: "教师宿舍", location: "教师宿舍", capacity: 20, verified: true },
  { code: "4GR00008477", name: "水电中心（名称待核）", location: "水电中心", capacity: 20, verified: false },
];

export const CHARGING_SOURCE = {
  provider: "第三方充电平台",
  registryVerifiedOn: "2026-10-29",
  method: "按机身二维码逐台核对",
  scope: "已核验 15 台设备，不代表全校",
};

const PORT_LABELS: Record<number, string> = {
  0: "粘连故障",
  1: "空闲",
  2: "占用",
  3: "开路故障",
  4: "设备急停",
  5: "过温保护",
  6: "烟感报警",
  7: "过压故障",
  8: "欠压故障",
};

function hash(text: string): number {
  let h = 0;
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) % 1000003;
  return h;
}

/** Sample ports, used only when the platform is unreachable; flagged live=false. */
function sampleCharging(device: ChargingDevice): ChargingSnapshot {
  const bucket = Math.floor(nowMs() / 600_000);
  const ports: PortState[] = Array.from({ length: device.capacity }, (_, i) => {
    const r = hash(`${device.code}|${i}|${bucket}`) % 100;
    const state = r < 24 ? 1 : r < 97 ? 2 : 5;
    return { num: i + 1, state, label: PORT_LABELS[state] ?? "未知状态" };
  });
  const free = ports.filter((p) => p.state === 1).length;
  const occupied = ports.filter((p) => p.state === 2).length;
  return {
    device,
    ports,
    counts: { total: device.capacity, observed: ports.length, free, occupied, fault: ports.length - free - occupied, unknown: 0 },
    live: false,
    at: null,
  };
}

/**
 * All devices in one request (the server keeps a shared one-minute cache).
 * Devices the platform could not answer for fall back to a flagged sample.
 */
export async function fetchChargingAll(): Promise<ChargingSnapshot[]> {
  let rows: {
    device: ChargingDevice;
    state: { ports: PortState[]; counts: ChargingSnapshot["counts"]; collected_at?: string | null } | null;
    error?: string;
  }[] = [];
  let answered = false;
  if (!needsServer()) {
    try {
      const res = await api.chargingAll();
      rows = res.devices;
      answered = true;
    } catch {
      rows = [];
    }
  }
  const byCode = new Map(rows.map((r) => [r.device.code, r]));
  return CHARGING_DEVICES.map((device) => {
    const hit = byCode.get(device.code);
    if (hit?.state?.ports?.length) {
      const at = hit.state.collected_at ? Date.parse(hit.state.collected_at) : nowMs();
      return { device, ports: hit.state.ports, counts: hit.state.counts, live: true, at: Number.isNaN(at) ? nowMs() : at };
    }
    // 服务器明确回答过「这台没有数据」（设备离线 / 平台业务失败）时，
    // **绝不编数字**：给空插座列表 + unavailable，界面上写"暂无数据"。
    // 只有完全连不上服务器（预览模式 / 后端挂了）才退回示意数据。
    if (answered) {
      const cap = device.capacity;
      return {
        device,
        ports: [],
        counts: { total: cap, observed: 0, free: 0, occupied: 0, fault: 0, unknown: cap },
        live: false,
        at: null,
        unavailable: hit?.error ?? "no_data",
      };
    }
    return sampleCharging(device);
  });
}

/* ------------------------------------------------------------------ library */

/** www.lib.stu.edu.cn/article-10.html, checked 2026-10-28. */
export const LIBRARY = {
  source: "https://www.lib.stu.edu.cn/article-10.html",
  checkedOn: "2026-10-28",
  halls: [
    { campus: "桑浦山校区图书馆", open: "08:00", close: "22:00", days: "周一至周日" },
    { campus: "东海岸校区图书馆", open: "08:00", close: "22:00", days: "周一至周日" },
  ],
  notes: ["特藏室：周一至周五 8:00–12:00、14:30–17:30", "考试月部分自习室 24 小时开放", "假期时间以图书馆公告为准"],
};

export function libraryOpenNow(open: string, close: string, at = moment()): { open: boolean; text: string } {
  const toMin = (c: string) => Number(c.slice(0, 2)) * 60 + Number(c.slice(3));
  const o = toMin(open);
  const c = toMin(close);
  if (at.minutes < o) return { open: false, text: `${open} 开馆` };
  if (at.minutes >= c) return { open: false, text: `明天 ${open} 开馆` };
  const left = c - at.minutes;
  return { open: true, text: left <= 60 ? `${left} 分钟后闭馆` : `开到 ${close}` };
}

/* -------------------------------------------------------------------- transit */

/**
 * Lines that reach 桑浦山, from the university's own 2023 visitor guide
 * (elc.stu.edu.cn/conf2023/INFORMATION/Transportation.htm, "for reference").
 * No realtime: the only realtime provider found (车来了) forbids third-party
 * access in its user agreement, so the App links out instead.
 */
export const TRANSIT = {
  source: "https://elc.stu.edu.cn/conf2023/INFORMATION/Transportation.htm",
  sourceLabel: "学校 2023 年来访交通指引（仅供参考）",
  sangpushan: ["6", "17", "21", "27", "39", "K3"],
  notes: ["39 路终点在汕头大学正门", "潮汕站可乘 181b 路", "线路会调整，出发前以公交官方信息为准"],
  dongHaiAn: "暂未找到东海岸校区的官方公交说明",
  realtime: [
    { name: "车来了", url: "https://www.chelaile.net.cn/" },
    { name: "高德地图", url: "https://www.amap.com/" },
  ],
};

/* ------------------------------------------------------------------- entrances */

export interface Entrance {
  name: string;
  hint: string;
  url: string;
  group: "学习" | "缴费" | "办事" | "生活";
  /** the official entrance is plain HTTP */
  insecure?: boolean;
  /** only reachable on the campus network, or after signing in to WebVPN */
  campusOnly?: boolean;
}

export const WEBVPN_URL = "https://webvpn.stu.edu.cn/";

/**
 * Official entrances, navigation only. The App never pays, applies or submits
 * on the student's behalf; these open the school's own pages.
 *
 * Sources: stu-mcp docs/campus-services.md (2026-10-23) and its investigation
 * notes. power.stu.edu.cn answers on HTTP only (HTTPS refused) and timed out
 * off the campus network in that survey; 192.168.48.49 is a private address.
 */
export const ENTRANCES: Entrance[] = [
  { name: "教务系统", hint: "成绩、考试、选课", url: "https://jw.stu.edu.cn/", group: "学习" },
  { name: "MySTU", hint: "课程、作业、资料", url: "https://my.stu.edu.cn/", group: "学习" },
  { name: "图书馆", hint: "馆藏检索、借阅", url: "https://www.lib.stu.edu.cn/", group: "学习" },
  { name: "教务处校历", hint: "2026–2027 学年", url: "https://jwc.stu.edu.cn/wdxz/jxxl.htm", group: "学习" },
  { name: "宿舍水电", hint: "电费读数、历史账单、自助缴费", url: "http://power.stu.edu.cn/", group: "缴费", insecure: true, campusOnly: true },
  { name: "水电缴费平台", hint: "hpao 缴费入口", url: "http://192.168.48.49:8080/hpao/main", group: "缴费", insecure: true, campusOnly: true },
  { name: "一卡通", hint: "余额与流水", url: "http://smartcard.stu.edu.cn/homeLogin.action", group: "缴费", insecure: true },
  { name: "WebVPN", hint: "校外访问校内系统", url: WEBVPN_URL, group: "缴费" },
  { name: "一网通办", hint: "事项目录与办理", url: "https://service.stu.edu.cn/fe/", group: "办事" },
  { name: "综合报修", hint: "两校区设施、网络故障", url: "https://r.stu.edu.cn/", group: "办事" },
  { name: "宿舍报修", hint: "桑浦山学生社区", url: "https://repair.stu.edu.cn/", group: "办事" },
  { name: "上网账号", hint: "套餐与流量", url: "https://netms.stu.edu.cn/", group: "生活" },
  { name: "网络中心", hint: "桑浦山 0754-86503400", url: "https://info.stu.edu.cn/its/lxwm.htm", group: "生活" },
];

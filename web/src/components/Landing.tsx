import { useEffect, useState } from "react";
import { motion } from "motion/react";
import { Icon } from "./Icon";
import { Logo } from "./Logo";
import { LdFan, LdGlow, type LdCard } from "./LandingFan";

/**
 * 落地页（2026-10-31 · 推翻重做版）
 *
 * 这一版**不复用** .welcome / .intro / .stage 任何旧类名，
 * 全部走 landing.css 的 .ld-* 前缀。原因是前面几版都在改老规则，
 * 改一处被另一处覆盖一处，越改越乱。
 *
 * 版式照参考图：
 *   奶油纸底上放一块深色圆角大板 → 板上是"小标签 + 小标题 + 卡组 + 按钮"
 *   **标题刻意做小**（1.375–2.1rem），主角是卡组，不是标题。
 */

/* 卡片 = 功能本身。卡名就是这一屏能回答的那句话，插画就是那个功能的软胶化：
   闹钟=下一节课 / 台历=一周光谱 / 清单=作业排队 / 试卷=考试与成绩 / 铃铛=通知 / 盾牌=隐私。
   色相跟着功能走（不是随机分配），所以颜色也成了记忆钩子。 */
const CARDS: LdCard[] = [
  { id: "today", color: "#b9a7f0", toy: "clock", title: "下一节课", subtitle: "在哪间教室，还剩几分钟" },
  { id: "week", color: "#f2a7bd", toy: "calendar", title: "一周光谱", subtitle: "卡宽就是真实分钟数" },
  { id: "plan", color: "#7ed2e6", toy: "checklist", title: "作业排队", subtitle: "今天 / 三天内 / 已过期", cta: true },
  { id: "study", color: "#8fd99c", toy: "paper", title: "考试与成绩", subtitle: "加权估算标了非官方", cta: true },
  { id: "campus", color: "#85b0f0", toy: "bell", title: "通知", subtitle: "可搜索、按来源筛" },
  { id: "privacy", color: "#f6bd93", toy: "shield", title: "账号只属于你", subtitle: "密码加密存你自己空间" },
];

function useClock() {
  const [t, setT] = useState("");
  useEffect(() => {
    const tick = () => {
      const d = new Date();
      const h = d.getHours();
      const m = d.getMinutes();
      const part = h < 6 ? "凌晨" : h < 11 ? "上午" : h < 13 ? "中午" : h < 18 ? "下午" : "晚上";
      setT(`${part} ${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`);
    };
    tick();
    const id = window.setInterval(tick, 20_000);
    return () => window.clearInterval(id);
  }, []);
  return t;
}

export function Landing({
  onStart,
  onPreview,
}: {
  onStart: () => void;
  onPreview?: () => void;
}) {
  const [active, setActive] = useState<number | null>(null);
  const clock = useClock();

  return (
    <div className="ld">
      <LdGlow />

      <div className="ld__stage">
        {/* 顶部导航：参考图是一条居中的胶囊（主页/怎么用/今天/加入）+ 右侧两个按钮。
            这里换成这个 App 真实的分页，不做假链接。 */}
        <motion.nav
          className="ld__nav"
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.42, ease: [0.22, 1, 0.36, 1] }}
          aria-label="主要导航"
        >
          <span className="ld__navLeft">
            <Logo size={26} className="ld__logo" />
          </span>
          <span className="ld__navPill">
            <span className="is-on">今天</span>
            <span>课表</span>
            <span>学业</span>
            <span>校园</span>
          </span>
          <span className="ld__navRight">
            <span className="ld__clock">
              <i aria-hidden="true" />
              {clock}
            </span>
          </span>
        </motion.nav>

        {/* 参考图里飘着几个带名字的小气泡（Robert / Clarissa / 小恐龙）：
            这里换成课程与地点的真实示例，是"这页在说什么"的注脚。 */}
        <span className="ld__tag ld__tag--a" aria-hidden="true">生物统计 · 桑浦山 E305</span>
        <span className="ld__tag ld__tag--b" aria-hidden="true">下一节 10:00</span>
        <span className="ld__tag ld__tag--c" aria-hidden="true">作业 3 天后截止</span>

        {/* 标题做小：参考图里标题只占板上方一小块，主角是卡片 */}
        <motion.h1
          className="ld__title"
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.42, delay: 0.06, ease: [0.22, 1, 0.36, 1] }}
        >
          下一节课在哪，作业几点截止。
        </motion.h1>

        <motion.p
          className="ld__lead"
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.42, delay: 0.1, ease: [0.22, 1, 0.36, 1] }}
        >
          教务课表、MySTU 作业和学校通知收在一处，按教务处官方作息算准每一节课。
        </motion.p>

        <motion.div
          className="ld__cards"
          initial={{ opacity: 0, y: 14 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5, delay: 0.14, ease: [0.22, 1, 0.36, 1] }}
        >
          <LdFan
            cards={CARDS}
            active={active}
            onActive={setActive}
          />
        </motion.div>

        <motion.div
          className="ld__actions"
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.42, delay: 0.18, ease: [0.22, 1, 0.36, 1] }}
        >
          <button type="button" className="ld__cta" onClick={onStart}>
            开始使用
          </button>
          {onPreview && (
            <button type="button" className="ld__ghost" onClick={onPreview}>
              先看看示例
            </button>
          )}
        </motion.div>

        <motion.ul
          className="ld__foot"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: 0.42, delay: 0.24 }}
        >
          <li>
            <Icon name="shield" size={14} />
            密码由你本人输入，加密存你自己空间
          </li>
          <li>
            <Icon name="check" size={14} />
            只读不代办：不签到、不交作业、不缴费
          </li>
        </motion.ul>
      </div>

      <p className="ld__credits">个人项目，与汕头大学官方无关 · 联合创始人 小爱 · 大象 · FKL</p>
    </div>
  );
}

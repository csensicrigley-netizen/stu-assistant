import { useState } from "react";
import { motion } from "motion/react";
import { IS_NATIVE, api, apiBase, setToken } from "../lib/api";
import { clearAllSchool, clearSchool, readSchool } from "../lib/school";
import {
  APP_VERSION_NAME,
  apkHref,
  checkAnyUpdate,
  clearSkip,
  type UpdateInfo,
} from "../lib/version";

import { forgetUser, useShell, useStore } from "../lib/store";
import { agoText } from "../lib/clock";
import { usePalette, useTheme, type PalettePref, type ThemePref } from "../lib/useTheme";
import { SOURCE_NAME } from "../lib/records";
import { Icon } from "../components/Icon";
import { Magnetic } from "../components/Magnetic";
import { rise } from "../lib/motion";
import { Notice, PageHead, Section } from "../components/Layout";
import { Segmented } from "../components/Segmented";

const STATE: Record<string, { label: string; tone: string }> = {
  ok: { label: "正常", tone: "ok" },
  maintenance: { label: "学校系统暂不可达", tone: "warn" },
  need_login: { label: "登录已过期", tone: "bad" },
  failed: { label: "读取失败", tone: "bad" },
  keystore: { label: "服务器密钥异常", tone: "bad" },
  running: { label: "正在同步", tone: "" },
  never: { label: "还没同步过", tone: "" },
};

const SOURCE_LABEL: Record<string, string> = {
  public: "学校官网通知",
  jw: "教务系统",
  mystu: "MySTU",
};

function hostOf(base: string): string {
  try {
    return new URL(base).host;
  } catch {
    return base;
  }
}

export function MeScreen({ onSignOut }: { onSignOut: () => void }) {
  const store = useStore();
  const { connect } = useShell();
  const { pref, choose } = useTheme();
  const { palette, choose: choosePalette } = usePalette();
  const [remembered, setRemembered] = useState(() => readSchool() !== null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const signedIn = store.mode !== "preview";

  const sync = async () => {
    setMsg(null);
    const ok = await store.refresh("force");
    setMsg(
      ok
        ? "同步结束，下面是每个来源这次的结果。为了不打扰学校系统，同一来源一分钟内只同步一次。"
        : "这次同步没有完成，可能是网络或学校系统暂时不可用。",
    );
  };

  const signOut = () => {
    void api.logout().catch(() => undefined);
    forgetUser();
    // 退出必须连本机的校园凭据一起清。否则换个人用这台机器时，上一份
    // 校园账号密码还躺着，下一次自动续连就会拿新用户的 Bearer 去配旧账号。
    clearAllSchool();
    setToken(null);
    onSignOut();
  };

  const remove = async () => {
    setBusy(true);
    try {
      await api.deleteAccount();
      forgetUser();
      clearAllSchool();
      setToken(null);
      onSignOut();
    } catch {
      setMsg("删除没有完成，请稍后再试。");
    } finally {
      setBusy(false);
    }
  };

  const bound = store.me?.bound ?? [];
  const rows = (store.sources.length
    ? store.sources
    : ["public", "jw", "mystu"].map((s) => ({ source: s, state: "never", detail: "", at: null, code: null, items: 0 }))
  ).filter((s) => s.source === "public" || bound.includes(s.source));

  return (
    <>
      <PageHead eyebrow="账号 · 外观 · 关于" title="我的" />

      {!signedIn ? (
        <motion.section className="connect" {...rise(0, { y: 8 })}>
          <div className="connect__icon" aria-hidden="true">
            <Icon name="shield" size={24} />
          </div>
          <h2 className="connect__title">登录后换成你自己的课表</h2>
          <p className="connect__text">现在看到的是一份真实课表快照和示例作业、成绩。创建账号并连接校园账号后，课表、作业、考试和成绩都会换成你的。</p>
          <button type="button" className="btn btn--primary btn--block" onClick={connect}>
            登录 / 注册
          </button>
        </motion.section>
      ) : (
        <>
          <motion.section className="account" {...rise(0, { y: 8 })}>
            <div className="account__avatar" aria-hidden="true">
              {(store.me?.email ?? "S").slice(0, 1).toUpperCase()}
            </div>
            <div className="account__text">
              <p className="account__name">{store.me?.email ?? "已登录"}</p>
              <p className="account__sub">
                {store.connected
                  ? `已连接 ${bound.filter((s) => s === "jw" || s === "mystu").map((s) => SOURCE_LABEL[s] ?? s).join("、")}`
                  : store.connected === false
                    ? "还没有连接校园账号"
                    : "正在读取账号信息"}
                {IS_NATIVE && apiBase() && ` · ${hostOf(apiBase())}`}
              </p>
            </div>
          </motion.section>

          {store.connected === false && (
            <motion.div {...rise(1, { y: 6 })}>
              <Magnetic pull={0.5}>
                <button type="button" className="cta" onClick={connect}>
                  <span className="cta__icon" aria-hidden="true">
                    <Icon name="shield" size={20} />
                  </span>
                  <span className="cta__text">
                    <b>连接校园账号</b>
                    <small>读取你的课表、作业、考试和成绩</small>
                  </span>
                  <Icon name="chevron-right" size={18} />
                </button>
              </Magnetic>
            </motion.div>
          )}

          {store.needsReconnect.length > 0 && (
            <Notice action={{ label: "重新连接", onClick: connect }}>
              {store.needsReconnect.map((s) => SOURCE_NAME[s] ?? s).join("、")}的学校登录已过期。为了不保存你的密码，需要重新输入一次。
            </Notice>
          )}

          <Section
            title="数据来源"
            action={{ label: store.syncing ? "同步中…" : "立即同步", onClick: () => void sync() }}
            note={store.syncedAt ? `${agoText(store.syncedAt)}读取` : undefined}
          >
            {msg && (
              <p className="banner" role="status">
                {msg}
              </p>
            )}
            <ul className="card card--list">
              {rows.map((s) => {
                const st = STATE[s.state] ?? { label: s.state, tone: "" };
                return (
                  <li key={s.source} className="src-row">
                    <span className={`src-row__dot src-row__dot--${st.tone}`} aria-hidden="true" />
                    <span className="src-row__name">{SOURCE_LABEL[s.source] ?? SOURCE_NAME[s.source] ?? s.source}</span>
                    <span className="src-row__state">
                      {st.label}
                      {s.at ? <small> · {agoText(s.at * 1000)}</small> : null}
                    </span>
                  </li>
                );
              })}
            </ul>
            {store.connected && (
              <button type="button" className="download download--button" onClick={connect}>
                <span className="download__icon" aria-hidden="true">
                  <Icon name="refresh" size={20} />
                </span>
                <span className="download__text">
                  <b>重新连接校园账号</b>
                  <small>教务或 MySTU 掉线时，重新登录一次就能接着读。</small>
                </span>
                <Icon name="arrow-right" size={18} />
              </button>
            )}
            {remembered && (
              <div className="creds">
                <span className="creds__text">已在这台设备记住学校密码，教务过期会自动重连。</span>
                <button
                  type="button"
                  className="link-btn"
                  onClick={() => {
                    clearSchool();
                    setRemembered(false);
                  }}
                >
                  清除
                </button>
              </div>
            )}
          </Section>
        </>
      )}

      <Section title="外观">
        <div className="card card--pad stack-4">
          <div className="stack-2">
            <p className="field__label">主题</p>
            <Segmented<ThemePref>
              label="外观"
              value={pref}
              onChange={choose}
              options={[
                { key: "system", label: "跟随系统" },
                { key: "light", label: "浅色" },
                { key: "dark", label: "深色" },
              ]}
            />
          </div>
          <div className="stack-2">
            <p className="field__label">色系</p>
            <Segmented<PalettePref>
              label="色系"
              value={palette}
              onChange={choosePalette}
              options={[
                { key: "sweet", label: "樱粉" },
                { key: "classic", label: "海蓝" },
              ]}
            />
          </div>
          <p className="field__hint">跟手机系统的深浅色走，装到桌面之后也不会跳。</p>
        </div>
      </Section>

      {!IS_NATIVE && (
        <Section title="安卓 App">
          <Magnetic pull={0.5}>
            <a className="download" href="/download/stu-assistant.apk" download>
              <span className="download__icon" aria-hidden="true">
                <Icon name="monitor" size={20} />
              </span>
              <span className="download__text">
                <b>下载安卓安装包</b>
                <small>和网页版用同一个账号。iPhone 可以在 Safari 里“添加到主屏幕”。</small>
              </span>
              <Icon name="arrow-right" size={18} />
            </a>
          </Magnetic>
        </Section>
      )}

      <UpdateSection />

      <Section title="反馈群">
        <div className="qrcard">
          <img className="qrcard__img" src="./feedback-group.png" alt="反馈群二维码" loading="lazy" />
          <div className="qrcard__text">
            <b>扫码进反馈群</b>
            <small>有 bug、想加功能、觉得哪条冷知识不对，或者就想骂我两句，都可以进群说。</small>
          </div>
        </div>
      </Section>

      <Section title="隐私">
        <ul className="card card--list">
          <li className="privacy-row">
            <Icon name="shield" size={18} />
            <span>
              <b>学校密码只用一次</b>
              <small>连接时交给学校统一认证，成功后只保留可吊销的加密会话，密码不保存。</small>
            </span>
          </li>
          <li className="privacy-row">
            <Icon name="check" size={18} />
            <span>
              <b>只读，不代办</b>
              <small>不提交作业、不签到、不缴费、不报修。勾选完成只记在这台设备上。</small>
            </span>
          </li>
          <li className="privacy-row">
            <Icon name="trash" size={18} />
            <span>
              <b>随时删除</b>
              <small>删除账号会清空服务器上你的会话和缓存数据。</small>
            </span>
          </li>
        </ul>
      </Section>

      <Section title="数据依据">
        <ul className="card card--list">
          <li className="cite">
            <a href="https://jwc.stu.edu.cn/info/1080/1939.htm" target="_blank" rel="noreferrer noopener">
              <span>
                <b>节次时刻</b>
                <small>教务处《课堂作息时间表》，与 2026–27 校历所附表一致</small>
              </span>
              <Icon name="external" size={16} />
            </a>
          </li>
          <li className="cite">
            <a href="https://jwc.stu.edu.cn/info/1080/3307.htm" target="_blank" rel="noreferrer noopener">
              <span>
                <b>周次与假期</b>
                <small>教务处 2026–2027 学年教学校历（2026-05-20 发布）</small>
              </span>
              <Icon name="external" size={16} />
            </a>
          </li>
          <li className="cite">
            <a href="https://github.com/0error0warning/stu-mcp" target="_blank" rel="noreferrer noopener">
              <span>
                <b>学校数据读取</b>
                <small>开源 stu-mcp v0.7.3，MIT 许可</small>
              </span>
              <Icon name="external" size={16} />
            </a>
          </li>
        </ul>
        <p className="footnote">
          <Icon name="info" size={14} />
          <span>个人项目，与学校官方无关。学校原文和通知始终优先。</span>
        </p>
      </Section>

      {signedIn && (
        <div className="stack-3">
          <button type="button" className="btn btn--ghost btn--block" onClick={signOut}>
            <Icon name="logout" size={18} />
            退出登录
          </button>
          {confirmDelete ? (
            <div className="danger-box" role="alert">
              <p>删除后服务器上的会话、课表和缓存会被清空，无法恢复。</p>
              <div className="row-actions">
                <button type="button" className="btn btn--danger btn--sm" onClick={() => void remove()} disabled={busy}>
                  确认删除
                </button>
                <button type="button" className="btn btn--ghost btn--sm" onClick={() => setConfirmDelete(false)}>
                  取消
                </button>
              </div>
            </div>
          ) : (
            <button type="button" className="btn btn--quiet btn--block btn--danger-text" onClick={() => setConfirmDelete(true)}>
              删除账号和数据
            </button>
          )}
        </div>
      )}
    </>
  );
}

/**
 * 「版本与更新」：一个随时可点的检查入口。
 *
 * 自动提示那一条可能被你点了「稍后」，也可能正好错过；这里手动查一次，
 * 查之前先把「稍后」记录清掉，保证结果一定弹给你看。
 */
function UpdateSection() {
  const [state, setState] = useState<"idle" | "checking" | "latest" | "found">("idle");
  const [info, setInfo] = useState<UpdateInfo | null>(null);

  const check = async () => {
    setState("checking");
    setInfo(null);
    clearSkip();
    const found = await checkAnyUpdate({ ignoreSkip: true });
    setInfo(found);
    setState(found ? "found" : "latest");
  };

  return (
    <Section title="版本与更新">
      <div className="card card--list">
        <ul style={{ listStyle: "none", margin: 0, padding: 0 }}>
          <li className="privacy-row">
            <Icon name="monitor" size={18} />
            <span>
              <b>当前版本 {APP_VERSION_NAME}</b>
              <small>
                {state === "checking"
                  ? "正在问服务器…"
                  : state === "latest"
                    ? "已经是最新版本。"
                    : state === "found" && info
                      ? `有新版 ${info.versionName}${info.kind === "page" ? "（刷新页面即可）" : "（装新安装包）"}`
                      : "发了新版会在这里和底部提示条上告诉你。"}
              </small>
            </span>
          </li>
        </ul>
        <div className="row-actions" style={{ padding: "0 16px 16px" }}>
          {state === "found" && info ? (
            info.kind === "page" ? (
              <button type="button" className="btn btn--primary btn--sm" onClick={() => window.location.reload()}>
                刷新页面
              </button>
            ) : (
              <a className="btn btn--primary btn--sm" href={apkHref(info.apk)}>
                下载 {info.versionName}
              </a>
            )
          ) : null}
          <button type="button" className="btn btn--soft btn--sm" onClick={() => void check()} disabled={state === "checking"}>
            {state === "checking" ? "检查中…" : "检查更新"}
          </button>
        </div>
        {state === "found" && info && info.notes.length > 0 && (
          <ul className="cite-more" style={{ padding: "0 16px 16px", margin: 0, listStyle: "none", lineHeight: 1.6 }}>
            {info.notes.map((n) => (
              <li key={n}>· {n}</li>
            ))}
          </ul>
        )}
      </div>
    </Section>
  );
}

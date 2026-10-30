import { useEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { IS_NATIVE, api, apiBase, errorText, needsServer, setApiBase, setToken, type BindJob } from "../lib/api";
import { saveSchool } from "../lib/school";
import { onBack } from "../lib/native";
import { Icon } from "../components/Icon";
import { Logo } from "../components/Logo";
import { Magnetic } from "../components/Magnetic";
import { Segmented } from "../components/Segmented";
import { rise } from "../lib/motion";

export type Step = "hello" | "account" | "bind";

/** Matches the CSS breakpoint where the welcome screen splits in two. */
function useWide(query = "(min-width: 960px)"): boolean {
  const [wide, setWide] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const mq = window.matchMedia(query);
    const on = () => setWide(mq.matches);
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, [query]);
  return wide;
}

/**
 * First run, sign-in, and connecting the campus account.
 *   phone:   hello → account → bind, one surface at a time
 *   desktop: the introduction stays on the left; the form is on the right
 */
export function Welcome({
  start,
  notice,
  onPreview,
  onDone,
  onCancel,
}: {
  start?: Step;
  notice?: string;
  onPreview?: () => void;
  onDone: () => void;
  onCancel?: () => void;
}) {
  const wide = useWide();
  const [step, setStep] = useState<Step>(start ?? "hello");
  const shown: Step = wide && step === "hello" ? "account" : step;

  useEffect(
    () =>
      onBack(() => {
        if (shown === "bind" && !start) return false; // already signed in; back leaves
        if (shown === "account" && !wide) {
          if (onCancel) onCancel();
          else setStep("hello");
          return true;
        }
        if (onCancel) {
          onCancel();
          return true;
        }
        return false;
      }),
    [shown, wide, start, onCancel],
  );

  return (
    <div className="welcome">
      <aside className="welcome__brand" aria-hidden={!wide}>
        <Intro />
      </aside>
      <div className="welcome__main">
        <AnimatePresence mode="wait" initial={false}>
          <motion.div
            key={shown}
            className="welcome__inner"
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }}
            transition={{ duration: 0.3, ease: [0.22, 1, 0.36, 1] }}
          >
            {shown === "hello" && <Hello onStart={() => setStep("account")} onPreview={onPreview} />}
            {shown === "account" && (
              <Account
                notice={notice}
                onBack={wide ? onCancel : onCancel ?? (() => setStep("hello"))}
                onPreview={wide ? onPreview : undefined}
                onDone={() => setStep("bind")}
              />
            )}
            {shown === "bind" && <Bind onDone={onDone} onCancel={onCancel} />}
          </motion.div>
        </AnimatePresence>
        <p className="welcome__credits">联合创始人 小爱 · 大象 · FKL</p>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ intro */

function Intro() {
  return (
    <div className="intro">
      <Logo size={64} className="intro__logo" />
      <h2 className="intro__title">
        下一节课在哪，
        <br />
        作业几点截止。
      </h2>
      <p className="intro__lead">教务课表、MySTU 作业和学校通知收在一处，按教务处官方作息算准每一节课。</p>
      <div className="intro__card" aria-hidden="true">
        <span className="intro__state">
          <i /> 正在上课 · 还剩 1 小时 05 分
        </span>
        <span className="intro__time">10:00–12:35</span>
        <span className="intro__course">生物统计学</span>
        <span className="intro__place">
          <Icon name="pin" size={16} /> 桑浦山-E305
        </span>
        <span className="intro__bar">
          <span />
        </span>
      </div>
      <ul className="intro__points">
        <li>
          <Icon name="shield" size={18} />
          账号密码由你本人输入，加密存在你自己那份空间里
        </li>
        <li>
          <Icon name="check" size={18} />
          只读不代办：不交作业、不签到、不缴费
        </li>
      </ul>
    </div>
  );
}

function Hello({ onStart, onPreview }: { onStart: () => void; onPreview?: () => void }) {
  const android = /Android/i.test(navigator.userAgent) && !IS_NATIVE;
  /*
   * 首屏的进场是有先后的：标志 → 标题 → 说明 → 三条 → 动作。
   * 全部走 rise()，延迟由 stagger() 统一出，谁也不许自己写 delay。
   */
  return (
    <div className="hello">
      <motion.div {...rise(0, { y: 12, scale: 0.96 })}>
        <Logo size={64} className="hello__logo" />
      </motion.div>
      <motion.h1 className="welcome__title" {...rise(1, { y: 8, scale: 1 })}>
        下一节课在哪，
        <br />
        作业几点截止。
      </motion.h1>
      <motion.p className="welcome__lead" {...rise(2, { y: 8, scale: 1 })}>
        教务课表、MySTU 作业和学校通知收在一处，按教务处官方作息算准每一节课。
      </motion.p>
      <motion.ul className="welcome__points" {...rise(3, { y: 8, scale: 1 })}>
        <li>
          <Icon name="clock" size={18} />
          <span>现在该去哪间教室，还有几分钟</span>
        </li>
        <li>
          <Icon name="study" size={18} />
          <span>作业按截止时间排好，考试倒数</span>
        </li>
        <li>
          <Icon name="shield" size={18} />
          <span>只读不代办，账号密码由你本人输入、加密存在你自己的空间</span>
        </li>
      </motion.ul>
      <motion.div className="welcome__actions" {...rise(4, { y: 8, scale: 1 })}>
        <Magnetic pull={0.4}>
          <button type="button" className="btn btn--primary btn--block btn--lg" onClick={onStart}>
            开始使用
          </button>
        </Magnetic>
        {onPreview && (
          <button type="button" className="btn btn--ghost btn--block btn--lg" onClick={onPreview}>
            先看看示例
          </button>
        )}
      </motion.div>
      {android && (
        <a className="welcome__apk" href="/download/stu-assistant.apk" download>
          <Icon name="arrow-right" size={16} />
          下载安卓 App
        </a>
      )}
      <p className="welcome__fine">个人项目，与汕头大学官方无关。</p>
    </div>
  );
}

/* ---------------------------------------------------------------- account */

function hostOf(base: string): string {
  try {
    return new URL(base).host;
  } catch {
    return base;
  }
}

function Account({
  notice,
  onBack: back,
  onPreview,
  onDone,
}: {
  notice?: string;
  onBack?: () => void;
  onPreview?: () => void;
  onDone: () => void;
}) {
  const [mode, setMode] = useState<"login" | "register">(notice ? "login" : "register");
  const [email, setEmail] = useState("");
  const [pass, setPass] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editServer, setEditServer] = useState(needsServer());
  const [server, setServer] = useState(apiBase());

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (IS_NATIVE && (editServer || needsServer())) {
        let base = server.trim().replace(/\/+$/, "");
        if (!/^https?:\/\//i.test(base)) base = `https://${base}`;
        try {
          await api.health(base);
        } catch {
          setError("这个服务器地址连不上，检查一下拼写和网络。");
          return;
        }
        setApiBase(base);
        setServer(base);
        setEditServer(false);
      }
      const res = mode === "login" ? await api.login(email.trim(), pass) : await api.register(email.trim(), pass);
      setToken(res.token);
      // 记下「现在是哪个助手账号」。本机校园凭据按「服务器 + 这个账号」分区，
      // 少了它就只能退化成服务器级分区，换人用同一台机器时会读到上一份。
      try {
        localStorage.setItem("stu.account", email.trim().toLowerCase());
      } catch {
        /* 隐私模式 */
      }
      onDone();
    } catch (exc) {
      setError(errorText(exc));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="form" onSubmit={submit}>
      {back && (
        <button type="button" className="back" onClick={back}>
          <Icon name="chevron-left" size={18} />
          返回
        </button>
      )}
      <h1 className="welcome__title welcome__title--sm">{mode === "register" ? "创建助手账号" : "登录助手账号"}</h1>
      <p className="welcome__lead">这是助手自己的账号，换手机时用它找回设置。和学校账号分开。</p>
      {notice && (
        <p className="form__notice" role="status">
          <Icon name="info" size={16} />
          {notice}
        </p>
      )}
      <Segmented
        label="登录或注册"
        value={mode}
        onChange={(m) => {
          setMode(m);
          setError(null);
        }}
        options={[
          { key: "register", label: "注册" },
          { key: "login", label: "登录" },
        ]}
      />
      {IS_NATIVE &&
        (editServer ? (
          <label className="field">
            <span className="field__label">服务器地址</span>
            <input
              className="input"
              type="url"
              inputMode="url"
              autoCapitalize="off"
              spellCheck={false}
              required
              value={server}
              onChange={(e) => setServer(e.target.value)}
              placeholder="https://你的域名"
            />
            <span className="field__hint">由部署这个助手的人提供。</span>
          </label>
        ) : (
          <div className="server-row">
            <Icon name="link" size={16} />
            <span>
              连接到 <b>{hostOf(apiBase())}</b>
            </span>
            <button type="button" className="link-btn" onClick={() => setEditServer(true)}>
              更改
            </button>
          </div>
        ))}
      <label className="field">
        <span className="field__label">邮箱</span>
        <input
          className="input"
          type="email"
          inputMode="email"
          autoComplete="email"
          autoCapitalize="off"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="name@example.com"
        />
      </label>
      <label className="field">
        <span className="field__label">口令</span>
        <input
          className="input"
          type="password"
          autoComplete={mode === "login" ? "current-password" : "new-password"}
          required
          minLength={8}
          value={pass}
          onChange={(e) => setPass(e.target.value)}
          placeholder="至少 8 位"
          aria-describedby="pass-hint"
        />
        <span className="field__hint" id="pass-hint">
          {mode === "register" ? "不要用学校密码。忘记口令可以换个邮箱重新注册，课表会重新同步。" : "注册时设置的口令。"}
        </span>
      </label>
      {error && (
        <p className="field__error" role="alert">
          {error}
        </p>
      )}
      <button className="btn btn--primary btn--block btn--lg" disabled={busy}>
        {busy ? "正在连服务器…" : mode === "register" ? "创建并继续" : "登录"}
      </button>
      {onPreview && (
        <button type="button" className="btn btn--quiet btn--block" onClick={onPreview}>
          先看看示例
          <Icon name="arrow-right" size={16} />
        </button>
      )}
    </form>
  );
}

/* ------------------------------------------------------------------- bind */

const BIND_ERRORS: Record<string, string> = {
  consent_required: "请先勾选「本人授权」——不授权，助手不会去读你的 OA 通知。",
  invalid_credentials: "学校统一认证没有通过：学号或密码不对。",
  login_rejected: "学校拒绝了这次登录。可以先在浏览器登录一次学校系统，确认账号正常。",
  captcha_rejected: "验证码不对，再试一次。",
  invalid_totp: "动态码不对或已过期，等下一个码再试。",
  authentication_budget: "这次登录尝试次数太多，学校暂时不接受，稍后再试。",
  login_throttled: "这一小时里连接次数太多了，稍后再试，避免学校锁定账号。",
  source_unavailable: "学校系统暂时不可用，可能在夜间维护，白天再试。",
  network_error: "服务器连不上学校系统，可能是网络问题，或学校系统正在维护。",
  request_timeout: "学校系统响应太慢，稍后再试。",
  authentication_schema_changed: "学校登录页面改版了，助手需要更新后才能连接。",
  authentication_outcome_unknown: "没能确认登录结果，请重试一次。",
  authentication_unconfirmed: "学校没有确认这次登录，请重试一次。",
  authentication_identity_mismatch: "登录后的身份和填写的学号不一致，请检查学号。",
  invalid_authentication_input: "学号或密码格式不对，检查一下有没有多余空格。",
  interaction_required: "学校要求额外验证，但这次没能完成，请重试。",
  insecure_transport: "学校教务的登录回调走了不加密的连接，服务器拒绝了，请联系部署者。",
  insecure_authentication_route: "学校登录走了不安全的路线，服务器拒绝了，请联系部署者。",
  secure_storage_unavailable: "服务器的加密存储暂时不可用，请联系部署者。",
};

function bindError(job: BindJob | null, fallback?: string): string {
  const err = job?.error;
  let code = err?.code ?? fallback ?? "";
  if (code === "login_failed" && err?.detail && typeof err.detail === "object") {
    const first = Object.values(err.detail as Record<string, string>)[0];
    if (first) code = first;
  }
  return BIND_ERRORS[code] ?? `连接没有成功（${code || "未知原因"}）`;
}

function Bind({ onDone, onCancel }: { onDone: () => void; onCancel?: () => void }) {
  const [sid, setSid] = useState("");
  const [pw, setPw] = useState("");
  const [remember, setRemember] = useState(true);
  // 本人授权：这一格不勾，服务端连 WebVPN 都不让连（403 consent_required）
  const [agree, setAgree] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [job, setJob] = useState<BindJob | null>(null);
  const [answer, setAnswer] = useState("");
  const poll = useRef<number | null>(null);
  const pendingCreds = useRef<{ account: string; password: string } | null>(null);

  useEffect(() => () => void (poll.current && window.clearInterval(poll.current)), []);

  const settle = (s: BindJob) => {
    setJob(s);
    if (s.state === "ok" || s.state === "failed" || s.state === "cancelled") {
      if (poll.current) window.clearInterval(poll.current);
      poll.current = null;
      setBusy(false);
      if (s.state === "ok") {
        // 只在这台设备上留一份，用于教务会话过期时自动重登
        if (pendingCreds.current) {
          saveSchool(pendingCreds.current.account, pendingCreds.current.password);
          pendingCreds.current = null;
        }
        window.setTimeout(onDone, 700);
      }
      if (s.state === "failed") setError(bindError(s));
    }
  };

  const watch = (id: string) => {
    if (poll.current) window.clearInterval(poll.current);
    // 服务端重启会丢掉内存里的 job，此时接口返回 404。以前这里把 404 也当
    // "暂时性错误"无限轮询，界面就永远停在"正在连接学校系统"——
    // iOS Safari 上尤其明显，因为它不会像桌面浏览器那样被用户手动切走。
    // 现在：404/410 立刻停表并报"这次连接已失效"，其它错误只重试有限次。
    let misses = 0;
    poll.current = window.setInterval(async () => {
      try {
        const next = await api.bindState(id);
        misses = 0;
        settle(next);
      } catch (exc) {
        const status = (exc as { status?: number }).status ?? 0;
        if (status === 404 || status === 410) {
          if (poll.current) window.clearInterval(poll.current);
          poll.current = null;
          setBusy(false);
          setJob(null);
          setError("这次连接已失效（服务器重启或会话超时），请重新点一次连接。");
          return;
        }
        misses += 1;
        if (misses >= 6) {
          if (poll.current) window.clearInterval(poll.current);
          poll.current = null;
          setBusy(false);
          setError("连接状态查不到，网络可能不稳定。请重新点一次连接。");
        }
      }
    }, 1500);
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (!agree) {
        // 前端先拦一次，后端还会再拦一次（403 consent_required）
        setError("请先勾选下面的「本人授权」—— 不授权，助手不会去读你的 OA 通知。");
        setBusy(false);
        return;
      }
      pendingCreds.current = remember ? { account: sid.trim(), password: pw } : null;
      await api.consentGrant();
      // 只绑教务与 MySTU：官网消息是公开页面，冷知识本地生成，
      // 都不再需要那道登录输入（2026-10-29 起登录通道整体下线）
      const started = await api.bind(["jw", "mystu"], sid.trim(), pw);
      setPw(""); // drop it from memory the moment it is sent
      settle(started);
      if (started.state === "running" || started.state === "waiting_input") watch(started.job_id);
    } catch (exc) {
      const code = (exc as { code?: string }).code;
      setError(code && BIND_ERRORS[code] ? BIND_ERRORS[code] : errorText(exc));
      setBusy(false);
    }
  };

  const send = async (challengeId: string) => {
    if (!job || !answer.trim()) return;
    try {
      setJob(await api.bindInput(job.job_id, challengeId, answer.trim()));
      setAnswer("");
    } catch (exc) {
      setError(errorText(exc));
    }
  };

  const running = job?.state === "running" || job?.state === "waiting_input";

  return (
    <form className="form" onSubmit={submit}>
      {onCancel && !running && (
        <button type="button" className="back" onClick={onCancel}>
          <Icon name="chevron-left" size={18} />
          返回
        </button>
      )}
      <h1 className="welcome__title welcome__title--sm">连接校园账号</h1>
      <div className="assure">
        <Icon name="shield" size={18} />
        <p>
          <b>本人授权使用</b>：这里的账号密码由你本人输入，只用于替你读你自己的教务、MySTU 和学校 OA 通知。
          勾选记住密码时，密码会加密保存在你自己这一份空间里，用于会话过期后自动重连；随时可在「我」里一键清除。
          助手不代你提交任何东西，也不保存学校数据的副本到别人的空间。
        </p>
      </div>
      <label className="field">
        <span className="field__label">学号 / 校园账号</span>
        <input
          className="input"
          autoComplete="username"
          autoCapitalize="off"
          spellCheck={false}
          required
          value={sid}
          onChange={(e) => setSid(e.target.value)}
          disabled={running}
        />
      </label>
      <label className="field">
        <span className="field__label">学校统一认证密码</span>
        <input
          className="input"
          type="password"
          autoComplete="current-password"
          required={!running}
          value={pw}
          onChange={(e) => setPw(e.target.value)}
          disabled={running}
        />
      </label>

      <label className="credbx">
        <input type="checkbox" checked={agree} onChange={(e) => setAgree(e.target.checked)} />
        <span>
          <b>本人授权</b>：我确认用的是<b>我自己的</b>学号与密码，授权助手<b>只读</b>取我本人可见的教务与 MySTU
          数据；不代我签到、交作业、缴费或提交任何东西。
        </span>
      </label>

      <label className="credbx">
        <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
        <span>记住学校密码，会话过期时自动重连（加密存在你自己那份空间，可随时清除）</span>
      </label>

      {job?.challenges.map((ch) => (
        <div key={ch.id} className="challenge">
          <p className="field__label">
            {ch.kind === "captcha"
              ? "输入图中的验证码"
              : ch.kind === "totp"
                ? "输入你数盾 App 里当前的 6 位动态口令（30 秒一变，只有你本人能看到；输入后即丢弃，不保存）"
                : "用手机扫码确认"}
          </p>
          {ch.kind === "captcha" && ch.image_b64 && (
            <img className="challenge__img" src={`data:${ch.media_type ?? "image/png"};base64,${ch.image_b64}`} alt="验证码图片" />
          )}
          {ch.kind === "qr" ? (
            <p className="muted">在手机上确认后，这里会自动继续。</p>
          ) : (
            <div className="row-actions">
              <input
                className="input"
                value={answer}
                onChange={(e) => setAnswer(e.target.value)}
                inputMode={ch.kind === "totp" ? "numeric" : "text"}
                autoComplete="one-time-code"
                aria-label={ch.kind === "totp" ? "动态码" : "验证码"}
              />
              <button type="button" className="btn btn--primary" onClick={() => void send(ch.id)}>
                提交
              </button>
            </div>
          )}
        </div>
      ))}

      {job?.state === "running" && (
        <p className="status-line" role="status">
          <span className="spinner" aria-hidden="true" /> 正在连接学校系统，通常要十几秒…
        </p>
      )}
      {job?.state === "ok" && (
        <p className="status-line status-line--ok" role="status">
          <Icon name="check" size={16} /> 已连接，接下来读取你的课表
        </p>
      )}
      {error && (
        <p className="field__error" role="alert">
          {error}
        </p>
      )}

      {!running && job?.state !== "ok" && (
        <button className="btn btn--primary btn--block btn--lg" disabled={busy}>
          {busy ? "连接中…" : "连接"}
        </button>
      )}
      {!running && job?.state !== "ok" && (
        <button type="button" className="btn btn--quiet btn--block" onClick={onCancel ?? onDone}>
          稍后再连
        </button>
      )}
    </form>
  );
}

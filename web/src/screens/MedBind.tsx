/**
 * 医学院教务绑定页。
 *
 * 为什么单独一张页面而不是塞进「导入课表」：医学院那套教务是**强制的图形验证码**
 * 登录，流程是"取图 → 人看图打字 → 换 token → 拉课表"，和导入页那四个步骤
 * 不是同一种东西。混在一起会让人以为导入失败要重录一遍。
 *
 * 三件事必须说清楚，少一句都有人踩坑：
 *   1. 这是**医学院教务系统**的密码，不是统一认证（SSO）那个；
 *   2. 密码只用一次，换到的只是一个会话 token，密码不存；
 *   3. 连上之后课表和主校区一样自动出现，不用再手动录。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { medApi, medErrorText, type MedStatus } from "../lib/med-api";
import { currentOrNextTerm, termPosition } from "../lib/calendar";
import { rise } from "../lib/motion";
import { useShell, useStore } from "../lib/store";
import { Icon } from "../components/Icon";
import { PageHead } from "../components/Layout";

const MED_HINT = "医学院教务系统（jwgl.med.stu.edu.cn）和主校区不是一套，登录要填图形验证码。";

export function MedBindScreen() {
  const shell = useShell();
  const store = useStore();
  const reduce = useReducedMotion() === true;

  // 医学院接口不返回学期号，只能我们自己告诉后端这算哪一学期
  const semester = useMemo(() => {
    const pos = termPosition();
    return (pos?.term ?? currentOrNextTerm()).id;
  }, []);
  const [status, setStatus] = useState<MedStatus | null>(null);
  const [checking, setChecking] = useState(true);

  // 默认走学校统一认证：学生平时在浏览器里用的就是这组，而且不用看验证码
  const [mode, setMode] = useState<"sso" | "local">("sso");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [captcha, setCaptcha] = useState<{ yzm: string; image: string } | null>(null);
  const [loadingImg, setLoadingImg] = useState(false);

  const [busy, setBusy] = useState<"login" | "sync" | "unlink" | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  const refreshCaptcha = useCallback(async () => {
    setLoadingImg(true);
    try {
      const c = await medApi.captcha();
      if (alive.current) {
        setCaptcha({ yzm: c.yzm_code, image: c.image });
        setCode("");
      }
    } catch (exc) {
      if (alive.current) setProblem(medErrorText(exc));
    } finally {
      if (alive.current) setLoadingImg(false);
    }
  }, []);

  useEffect(() => {
    void medApi
      .status()
      .then((s) => {
        if (!alive.current) return;
        setStatus(s);
        if (!s.linked && mode === "local") void refreshCaptcha();
      })
      .catch(() => {
        if (alive.current) setStatus({ linked: false, username: "", name: "", updated_at: null });
        void refreshCaptcha();
      })
      .finally(() => {
        if (alive.current) setChecking(false);
      });
  }, [refreshCaptcha, mode]);

  const doSync = useCallback(async () => {
    setBusy("sync");
    setProblem(null);
    setDone(null);
    try {
      const res = await medApi.sync(semester);
      if (!alive.current) return;
      // 拉完让全局 store 重新读一次课表，页面上的课表才会真的换掉
      await store.refresh("read");
      setDone(`已同步 ${res.semester}：${res.courses} 门课、${res.meetings} 个上课时间。`);
    } catch (exc) {
      if (alive.current) setProblem(medErrorText(exc));
    } finally {
      if (alive.current) setBusy(null);
    }
  }, [store, semester]);

  const doLogin = useCallback(async () => {
    if (mode === "local" && !captcha) {
      setProblem("验证码还没取到，点一下图片换一张。");
      return;
    }
    if (!username.trim() || !password) {
      setProblem("账号和密码都要填。");
      return;
    }
    if (mode === "local" && code.trim().length < 3) {
      setProblem("验证码看着不像对的，照着图片再敲一遍。");
      return;
    }
    setBusy("login");
    setProblem(null);
    setDone(null);
    try {
      const res = await medApi.login({
        mode,
        username: username.trim(),
        password,
        verifyCode: mode === "local" ? code.trim() : "",
        yzmCode: mode === "local" && captcha ? captcha.yzm : "",
      });
      if (!alive.current) return;
      setPassword("");
      setStatus({ linked: true, username: username.trim(), name: res.name, updated_at: Date.now() / 1000 });
      await doSync();
    } catch (exc) {
      if (!alive.current) return;
      setProblem(medErrorText(exc));
      // 验证码是一次性的：不管这次错在哪，都换一张，别让人对着旧图重试
      void refreshCaptcha();
    } finally {
      if (alive.current) setBusy(null);
    }
  }, [mode, captcha, username, password, code, refreshCaptcha, doSync]);

  const doUnlink = useCallback(async () => {
    setBusy("unlink");
    setProblem(null);
    setDone(null);
    try {
      await medApi.unlink();
      if (!alive.current) return;
      setStatus({ linked: false, username: "", name: "", updated_at: null });
      setDone("已经解除绑定，服务端那份登录凭证删掉了。");
      void refreshCaptcha();
    } catch (exc) {
      if (alive.current) setProblem(medErrorText(exc));
    } finally {
      if (alive.current) setBusy(null);
    }
  }, [refreshCaptcha]);

  return (
    <>
      <PageHead
        eyebrow="医学院 · 自动同步"
        title="连接医学院教务"
        sub="连上以后，课表、成绩、考试都会自己同步过来，不用再一门一门录。"
        aside={
          <button type="button" className="btn btn--ghost btn--sm" onClick={() => shell.go("week")}>
            返回课表
          </button>
        }
      />

      {checking ? (
        <div className="card card--pad stack-2">
          <p className="muted">正在看这个账号连过没有…</p>
        </div>
      ) : status?.linked ? (
        <motion.div className="card card--pad stack-3" {...(reduce ? {} : rise(0))}>
          <p className="row-lead">
            <Icon name="check" size={18} />
            <b>已经连上了</b>
          </p>
          <p className="muted">
            {status.name ? `${status.name} · ` : ""}
            {status.username ? `账号 ${status.username}` : "医学院教务账号"}
          </p>
          <p className="footnote">
            存下来的只是一个会话凭证，密码没有留下。凭证过期时会提示你重新连一次。
          </p>
          <div className="row-actions">
            <button
              type="button"
              className="btn btn--primary btn--sm"
              disabled={busy !== null}
              onClick={() => void doSync()}
            >
              {busy === "sync" ? "正在同步…" : "立即同步"}
            </button>
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              disabled={busy !== null}
              onClick={() => void doUnlink()}
            >
              {busy === "unlink" ? "正在解除…" : "解除绑定"}
            </button>
          </div>
        </motion.div>
      ) : (
        <motion.div className="card card--pad stack-3" {...(reduce ? {} : rise(0))}>
          <p className="muted">{MED_HINT}</p>

          <div className="field">
            <label className="field__label" htmlFor="med-user">
              学号 / 账号
            </label>
            <input
              id="med-user"
              className="input"
              inputMode="numeric"
              autoComplete="username"
              placeholder="学号，例如 2026123456"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </div>

          <div className="field">
            <label className="field__label" htmlFor="med-pass">
              密码
            </label>
            <input
              id="med-pass"
              className="input"
              type="password"
              autoComplete="current-password"
              placeholder="医学院教务系统的密码，不是统一认证那个"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            <p className="field__hint">只用这一次换会话凭证，用完就丢，不保存。</p>
          </div>

          <div className="row-actions">
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => {
                const next = mode === "sso" ? "local" : "sso";
                setMode(next);
                setProblem(null);
                if (next === "local") void refreshCaptcha();
              }}
            >
              {mode === "sso" ? "统一认证进不去？换教务本地口令" : "改用学校统一认证"}
            </button>
          </div>

          {mode === "local" && (
          <div className="field">
            <label className="field__label" htmlFor="med-code">
              图形验证码
            </label>
            <div className="row-actions">
              <input
                id="med-code"
                className="input"
                inputMode="text"
                autoComplete="off"
                autoCapitalize="characters"
                placeholder="照着右边图片敲"
                style={{ flex: "1 1 8rem", minWidth: 0 }}
                value={code}
                onChange={(e) => setCode(e.target.value)}
              />
              <button
                type="button"
                className="med-cap"
                onClick={() => void refreshCaptcha()}
                aria-label={loadingImg ? "正在换验证码" : "换一张验证码"}
                title="看不清？点一下换一张"
              >
                {captcha && !loadingImg ? (
                  <img src={captcha.image} alt="图形验证码，点一下换一张" />
                ) : (
                  <span className="med-cap__ph">{loadingImg ? "读取中…" : "点这里取图"}</span>
                )}
              </button>
            </div>
            <p className="field__hint">验证码一两分钟就过期，停留太久就点图片换一张。</p>
          </div>
          )}

          <div className="row-actions">
            <button
              type="button"
              className="btn btn--primary btn--block"
              disabled={busy !== null}
              onClick={() => void doLogin()}
            >
              {busy === "login" ? "正在连接…" : "连接并同步"}
            </button>
          </div>
        </motion.div>
      )}

      <AnimatePresence>
        {problem && (
          <motion.p className="field__error" initial={reduce ? false : { opacity: 0, y: -4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}>
            {problem}
          </motion.p>
        )}
        {done && (
          <motion.p className="assure" initial={reduce ? false : { opacity: 0, y: -4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}>
            <Icon name="check" size={16} />
            <span>{done}</span>
          </motion.p>
        )}
      </AnimatePresence>

      <p className="footnote">
        <Icon name="info" size={14} />
        <span>
          医学院教务系统和主校区不是一家厂商，接口也是另外一套。连不上、或者密码在那边改过，就先用「手动导入课表」把课录进来，功能一样。
        </span>
      </p>
    </>
  );
}

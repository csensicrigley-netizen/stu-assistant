import { useEffect, useState } from "react";
import { Sheet } from "./Sheet";
import { Icon } from "./Icon";
import {
  TODAY_DOWNLOAD_URL,
  campusLabel,
  formatExportCode,
  openTodayDeepLink,
  type TodayExportTicket,
} from "../lib/today-export";

async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* fallback below */
  }
  try {
    const el = document.createElement("textarea");
    el.value = text;
    el.setAttribute("readonly", "");
    el.style.position = "fixed";
    el.style.opacity = "0";
    document.body.appendChild(el);
    el.select();
    const ok = document.execCommand("copy");
    document.body.removeChild(el);
    return ok;
  } catch {
    return false;
  }
}

/**
 * Fallback & handoff sheet shown after generating a one-time export code for `today`.
 * Displays the 12-char code (5 min TTL, single-use), copy button, retry-launch button,
 * and download link if `today` is not installed yet.
 */
export function TodayExportSheet({
  ticket,
  busy,
  error,
  onRegenerate,
  onClose,
}: {
  ticket: TodayExportTicket;
  busy?: boolean;
  error?: string | null;
  onRegenerate: () => void;
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    setCopied(false);
  }, [ticket.code]);

  const handleCopy = async () => {
    const ok = await copyText(ticket.code);
    if (ok) {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2400);
    }
  };

  const minutes = Math.max(1, Math.round((ticket.expires_in || 300) / 60));

  return (
    <Sheet
      title="导入课表到 today"
      eyebrow={
        <>
          <Icon name="spark" size={14} />
          {[ticket.semester, campusLabel(ticket.campus), `${ticket.course_count} 门课`].filter(Boolean).join(" · ")}
        </>
      }
      onClose={onClose}
    >
      <p className="muted">
        已尝试自动拉起 today 安排表。如果浏览器没有自动跳转，可点击下方按钮再次打开，或复制一次性导入码回 today 粘贴导入。
      </p>

      <div className="today-code">
        <p className="today-code__label">12 位一次性导入码</p>
        <p className="today-code__value num" aria-label={ticket.code}>
          {formatExportCode(ticket.code)}
        </p>
        <p className="today-code__meta">{minutes} 分钟内有效 · 仅可使用一次 · 仅含课表快照，不含账号密码</p>
      </div>

      {error && (
        <p className="field__error" role="alert">
          {error}
        </p>
      )}

      <div className="stack-2">
        <button
          type="button"
          className="btn btn--primary btn--block"
          onClick={() => openTodayDeepLink(ticket.deep_link)}
        >
          <Icon name="external" size={16} /> 再次打开 today
        </button>

        <button type="button" className="btn btn--soft btn--block" onClick={() => void handleCopy()}>
          <Icon name={copied ? "check" : "link"} size={16} /> {copied ? "已复制导入码" : "复制导入码"}
        </button>

        <a
          className="btn btn--ghost btn--block"
          href={TODAY_DOWNLOAD_URL}
          target="_blank"
          rel="noreferrer noopener"
        >
          还没装 today？前往下载 <Icon name="external" size={15} />
        </a>

        <button
          type="button"
          className="btn btn--quiet btn--block btn--sm"
          disabled={busy}
          onClick={onRegenerate}
        >
          <Icon name="refresh" size={15} /> {busy ? "正在重新生成…" : "导入码已使用或过期？重新生成"}
        </button>
      </div>

      <p className="footnote">
        <Icon name="info" size={14} />
        <span>课表快照在 today 拉取后立即失效，不保存你的 STU 小助手账号、密码或登录态。</span>
      </p>
    </Sheet>
  );
}

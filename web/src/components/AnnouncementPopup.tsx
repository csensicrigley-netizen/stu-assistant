import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion, useIsPresent, useReducedMotion } from "motion/react";
import { Icon } from "./Icon";
import { Logo } from "./Logo";
import { AnnouncementEffect } from "./AnnouncementEffect";
import { onBack } from "../lib/native";
import {
  announcementEligible, currentAnnouncementScope, fetchAnnouncements, markAnnouncementShown, resetAnnouncementOpening, type Announcement,
} from "../lib/announcements";
import "../styles/announcements.css";

/** Lives alongside the shell so switching tabs cannot restart the opening check. */
export function AnnouncementPopup({ active }: { active: boolean }) {
  const [message, setMessage] = useState<{ item: Announcement; scope: string } | null>(null);
  const scope = currentAnnouncementScope();
  const [opening, setOpening] = useState(0);
  const currentMessage = useRef(message);
  currentMessage.current = message;
  useEffect(() => {
    const returned = () => {
      if (document.visibilityState !== "visible" || currentMessage.current) return;
      resetAnnouncementOpening(currentAnnouncementScope());
      setOpening((value) => value + 1);
    };
    document.addEventListener("visibilitychange", returned);
    return () => document.removeEventListener("visibilitychange", returned);
  }, []);

  useEffect(() => {
    if (!active) {
      setMessage(null);
      return;
    }
    let disposed = false;
    const controller = new AbortController();
    let deadline = 0;
    const timer = window.setTimeout(() => {
      deadline = window.setTimeout(() => controller.abort(), 5_000);
      void fetchAnnouncements(controller.signal).then((items) => {
        if (disposed || controller.signal.aborted || currentAnnouncementScope() !== scope) return;
        const selected = items.find((item) => announcementEligible(item, scope));
        // A user already opening a course or form should keep that interaction.
        if (selected && document.visibilityState === "visible" && !document.querySelector('[role="dialog"],dialog[open]')) {
          setMessage({ item: selected, scope });
        }
      }).catch(() => undefined).finally(() => window.clearTimeout(deadline));
    }, 650);
    return () => {
      disposed = true;
      controller.abort();
      window.clearTimeout(timer);
      window.clearTimeout(deadline);
    };
  }, [active, scope, opening]);

  return <AnimatePresence>{active && message?.scope === scope && (
    <AnnouncementDialog key={`${scope}:${message.item.id}:${message.item.revision}`} message={message.item} scope={scope} onClose={() => setMessage(null)} />
  )}</AnimatePresence>;
}

function AnnouncementDialog({ message, scope, onClose }: { message: Announcement; scope: string; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  const title = useId();
  const body = useId();
  const reduced = useReducedMotion();
  const present = useIsPresent();
  const closing = useRef(false);
  const dismiss = () => { if (!closing.current) { closing.current = true; close.current(); } };
  const [imageFailed, setImageFailed] = useState(false);

  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    const before = document.activeElement as HTMLElement | null;
    element.showModal();
    markAnnouncementShown(message, scope);
    const priorOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const unregister = onBack(() => { dismiss(); return true; });
    return () => {
      unregister();
      element.close();
      document.body.style.overflow = priorOverflow;
      if (before?.isConnected) before.focus();
    };
  }, [message, scope]);

  return createPortal(
    <motion.dialog
      ref={dialog}
      className="announcement"
      data-closing={!present || undefined}
      aria-labelledby={title}
      aria-describedby={message.body ? body : undefined}
      aria-modal="true"
      onCancel={(event) => { event.preventDefault(); dismiss(); }}
      onClick={(event) => { if (event.target === event.currentTarget) dismiss(); }}
      initial={reduced ? { opacity: 0 } : { opacity: 0, y: 28, scale: 0.92 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, y: reduced ? 0 : 12, scale: reduced ? 1 : 0.965,
        transition: { duration: reduced ? .14 : .26, ease: [.4, 0, 1, 1], opacity: { duration: reduced ? .14 : .24 } } }}
      transition={{ duration: reduced ? .14 : .56, ease: [.16, 1, .3, 1], opacity: { duration: reduced ? .14 : .26, ease: [.2, 0, 0, 1] } }}
    >
      <div className="announcement__panel" inert={!present}>
        <div className="announcement__header">
          <p className="announcement__eyebrow">{message.eyebrow}</p>
          <button type="button" className="announcement__close" aria-label="关闭消息" onClick={dismiss} autoFocus>关闭</button>
        </div>
        <div className="announcement__hero">
        {message.image_url && !imageFailed ? (
          <div className="announcement__image">
            <img src={message.image_url} alt={message.image_alt} onError={() => setImageFailed(true)} referrerPolicy="no-referrer" />
          </div>
        ) : (
          <div className="announcement__emblem" aria-hidden="true">
            <Logo size={64} />
            <svg className="announcement__stars" viewBox="0 0 320 124" preserveAspectRatio="none" aria-hidden="true">
              <path d="M64 28 68 37 78 38 71 45 73 55 64 50 55 55 57 45 50 38 60 37Z M262 77 265 84 273 85 267 90 269 98 262 94 255 98 257 90 251 85 259 84Z" />
            </svg>
          </div>
        )}
          {present && <AnnouncementEffect key={`${message.id}/${message.revision}`} effects={message.effects} />}
        </div>
        <div className="announcement__content">
          <h2 className="announcement__title" id={title}>{message.title}</h2>
          {message.body && <p className="announcement__body" id={body}>{message.body}</p>}
          <div className="announcement__actions">
            {message.action_url && (
              <a className="announcement__primary" href={message.action_url} target="_blank" rel="noopener noreferrer" onClick={dismiss}>
                {message.action_label || "了解更多"}<Icon name="external" size={18} />
              </a>
            )}
            <button type="button" className={message.action_url ? "announcement__secondary" : "announcement__primary"} onClick={dismiss}>{message.action_url ? "下次再说" : "知道了"}</button>
          </div>
        </div>
      </div>
    </motion.dialog>, document.body,
  );
}

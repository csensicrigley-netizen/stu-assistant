import { useEffect, useId, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { motion, useDragControls, type PanInfo } from "motion/react";
import { Icon } from "./Icon";
import { onBack } from "../lib/native";
import { SHEET } from "../lib/motion";

/**
 * Bottom sheet. A dialog in every sense a screen reader cares about:
 * labelled, modal, focus moves in and comes back, Esc closes. Drag the handle
 * down to dismiss; the backdrop closes too.
 */
export function Sheet({
  title,
  eyebrow,
  onClose,
  children,
  hue,
}: {
  title: string;
  eyebrow?: ReactNode;
  onClose: () => void;
  children: ReactNode;
  hue?: number;
}) {
  const id = useId();
  const panel = useRef<HTMLDivElement>(null);
  const drag = useDragControls();
  // callers pass a fresh arrow each render; keep the effect below mount-only so
  // a clock tick in the parent never steals focus back to the panel
  const close = useRef(onClose);
  close.current = onClose;

  // Android back closes the sheet before anything else
  useEffect(
    () =>
      onBack(() => {
        close.current();
        return true;
      }),
    [],
  );

  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    panel.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") close.current();
      if (e.key === "Tab" && panel.current) {
        const items = panel.current.querySelectorAll<HTMLElement>(
          'a[href],button:not([disabled]),input,select,textarea,[tabindex]:not([tabindex="-1"])',
        );
        if (items.length === 0) return;
        const first = items[0];
        const last = items[items.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener("keydown", onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prevOverflow;
      before?.focus?.();
    };
  }, []);

  const onDragEnd = (_: unknown, info: PanInfo) => {
    if (info.offset.y > 110 || info.velocity.y > 600) onClose();
  };

  // Portal to <body>: the page is animated with a transform, and a transformed
  // ancestor would turn this fixed layer into one positioned inside the page.
  return createPortal(
    <div className="sheet-layer">
      <motion.div
        className="sheet-scrim"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.2 }}
        onClick={onClose}
      />
      <motion.div
        ref={panel}
        className="sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby={id}
        tabIndex={-1}
        style={hue !== undefined ? ({ "--h": hue } as React.CSSProperties) : undefined}
        initial={{ y: "100%" }}
        animate={{ y: 0 }}
        exit={{ y: "100%" }}
        transition={SHEET}
        drag="y"
        dragControls={drag}
        dragListener={false}
        dragConstraints={{ top: 0, bottom: 0 }}
        dragElastic={{ top: 0, bottom: 0.6 }}
        onDragEnd={onDragEnd}
      >
        <div className="sheet__grip" onPointerDown={(e) => drag.start(e)} aria-hidden="true">
          <span />
        </div>
        <header className="sheet__head">
          <div className="sheet__titles">
            {eyebrow && <p className="sheet__eyebrow">{eyebrow}</p>}
            <h2 className="sheet__title" id={id}>
              {title}
            </h2>
          </div>
          <button className="icon-btn" onClick={onClose} aria-label="关闭">
            <Icon name="close" size={20} />
          </button>
        </header>
        <div className="sheet__body">{children}</div>
      </motion.div>
    </div>,
    document.body,
  );
}

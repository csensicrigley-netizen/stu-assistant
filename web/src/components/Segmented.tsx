import { useId } from "react";
import { motion } from "motion/react";
import { PILL } from "../lib/motion";

/**
 * A segmented control. It switches a view in place, so it is a radio group,
 * not a tab list: arrow keys move, the pill follows with a spring.
 *
 * 药丸用的是共享布局动画（layoutId + PILL），和底栏、导入步骤条同一档参数 ——
 * 全站"选中态在动"这件事只有一种手感。
 */
export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
}: {
  value: T;
  options: { key: T; label: string; badge?: number }[];
  onChange: (key: T) => void;
  label: string;
}) {
  const group = useId();
  const onKey = (e: React.KeyboardEvent) => {
    const i = options.findIndex((o) => o.key === value);
    if (e.key === "ArrowRight" || e.key === "ArrowDown") {
      e.preventDefault();
      onChange(options[(i + 1) % options.length].key);
    }
    if (e.key === "ArrowLeft" || e.key === "ArrowUp") {
      e.preventDefault();
      onChange(options[(i - 1 + options.length) % options.length].key);
    }
  };
  return (
    <div className="seg" role="radiogroup" aria-label={label} onKeyDown={onKey}>
      {options.map((o) => {
        const on = o.key === value;
        return (
          <button
            key={o.key}
            type="button"
            role="radio"
            aria-checked={on}
            tabIndex={on ? 0 : -1}
            className={`seg__btn${on ? " is-on" : ""}`}
            onClick={() => onChange(o.key)}
          >
            {on && <motion.span className="seg__pill" layoutId={`seg-${group}`} transition={PILL} />}
            <span className="seg__label">
              {o.label}
              {o.badge ? <span className="seg__badge">{o.badge}</span> : null}
            </span>
          </button>
        );
      })}
    </div>
  );
}

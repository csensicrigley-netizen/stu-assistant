import { useId, useRef, useState, type CSSProperties } from "react";
import { getHapticStrength, haptic, setHapticStrength } from "../lib/haptics";
import { Section } from "./Layout";

export function FeedbackSettings() {
  const id = useId();
  const [strength, setStrength] = useState(getHapticStrength);
  const lastPreview = useRef(-Infinity);
  const change = (value: number) => {
    const next = setHapticStrength(value);
    setStrength(next);
    // No trailing timer: stopping a drag never leaves a queue of vibrations.
    const now = performance.now();
    if (next > 0 && now - lastPreview.current >= 60) {
      lastPreview.current = now;
      haptic("selection");
    }
  };

  return (
    <Section title="触感反馈">
      <div className="card card--pad feedback" data-haptic="manual">
        <div className="feedback__label">
          <label htmlFor={id}>按键强度</label>
          <output htmlFor={id}>{strength === 0 ? "已关闭" : strength}</output>
        </div>
        <input
          id={id}
          className="feedback__range"
          type="range"
          min={0}
          max={100}
          step={1}
          value={strength}
          aria-valuetext={strength === 0 ? "已关闭" : `${strength}%`}
          aria-describedby={`${id}-hint`}
          style={{ "--strength": `${strength}%` } as CSSProperties}
          onChange={(event) => change(Number(event.target.value))}
        />
        <div className="feedback__ends" aria-hidden="true"><span>0</span><span>100</span></div>
        <p id={`${id}-hint`} className="field__hint">滑动感受力度，调到 0 即可关闭。</p>
      </div>
    </Section>
  );
}

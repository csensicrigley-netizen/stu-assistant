import { useEffect, useRef, useState } from "react";
import type { AnimationItem } from "lottie-web";
import { useReducedMotion } from "motion/react";
import type { AnnouncementEffectId } from "../lib/announcements";

/** Decorative overlay: no layout space, pointer targets, network services or looping. */
export function AnnouncementEffect({ effects }: { effects: AnnouncementEffectId[] }) {
  const container = useRef<HTMLDivElement>(null);
  const [selected] = useState(() => effects[Math.floor(Math.random() * effects.length)]);
  const [finished, setFinished] = useState(false);
  const reduced = useReducedMotion();
  useEffect(() => {
    if (!selected || reduced || finished || !container.current) return;
    const controller = new AbortController();
    let disposed = false;
    let animation: AnimationItem | undefined;
    let startTimer: ReturnType<typeof setTimeout> | undefined;
    const stop = () => { if (!disposed) setFinished(true); };
    const onVisibility = () => { if (document.hidden) stop(); };
    document.addEventListener("visibilitychange", onVisibility);
    const deadline = setTimeout(stop, 8000);
    // Parsing a large vector composition during the card reveal can block JS and
    // make an otherwise smooth transition look like a flash on first opening.
    startTimer = setTimeout(() => { void Promise.all([
      import("lottie-web/build/player/lottie_light"),
      fetch(`${import.meta.env.BASE_URL}announcement-effects/${selected}.json`, { signal: controller.signal })
        .then((response) => { if (!response.ok) throw new Error("Effect unavailable"); return response.json(); }),
    ]).then(([player, animationData]) => {
      if (disposed || !container.current || document.hidden) return;
      animation = player.default.loadAnimation({ container: container.current, renderer: "svg", loop: false, autoplay: false, animationData });
      // Interpolate original 30/60fps keyframes on every browser display tick.
      animation.setSubframe(true);
      animation.addEventListener("complete", stop);
      animation.addEventListener("data_failed", stop);
      animation.addEventListener("DOMLoaded", () => animation?.play());
    }).catch(stop); }, 600);
    return () => {
      disposed = true;
      controller.abort();
      clearTimeout(deadline);
      clearTimeout(startTimer);
      document.removeEventListener("visibilitychange", onVisibility);
      animation?.destroy();
    };
  }, [selected, reduced, finished]);
  if (!selected || reduced || finished) return null;
  return <div ref={container} className="announcement__effect" data-effect={selected} aria-hidden="true" />;
}

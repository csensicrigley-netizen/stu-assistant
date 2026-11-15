import { useEffect, useLayoutEffect, useRef, useState } from "react";
import type { AnimationItem } from "lottie-web";
import { useReducedMotion } from "motion/react";
import type { AnnouncementEffectId } from "../lib/announcements";

export type PreparedAnnouncementEffect = {
  id: AnnouncementEffectId;
  player: typeof import("lottie-web/build/player/lottie_light")["default"];
  animationData: unknown;
};

/** Fetch/parse before revealing the card so cold assets do not delay its first frames. */
export async function prepareAnnouncementEffect(effects: AnnouncementEffectId[], signal: AbortSignal): Promise<PreparedAnnouncementEffect | null> {
  const id = effects[Math.floor(Math.random() * effects.length)];
  if (!id || signal.aborted) return null;
  const controller = new AbortController();
  const cancel = () => controller.abort();
  signal.addEventListener("abort", cancel, { once: true });
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const [player, animationData] = await Promise.race([
      Promise.all([
        import("lottie-web/build/player/lottie_light"),
        fetch(`${import.meta.env.BASE_URL}announcement-effects/${id}.json`, { signal: controller.signal })
          .then((response) => { if (!response.ok) throw new Error("Effect unavailable"); return response.json(); }),
      ]),
      new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error("Effect preparation timed out")), 1500); }),
    ]);
    return signal.aborted ? null : { id, player: player.default, animationData };
  } catch { return null; }
  finally { clearTimeout(timer); controller.abort(); signal.removeEventListener("abort", cancel); }
}

/** Decorative overlay: no layout space, pointer targets, network services or looping. */
export function AnnouncementEffect({ prepared, startedAt, onReady }: { prepared: PreparedAnnouncementEffect | null; startedAt: number | null; onReady: () => void }) {
  const container = useRef<HTMLDivElement>(null);
  const player = useRef<AnimationItem | null>(null);
  const [finished, setFinished] = useState(false);
  const reduced = useReducedMotion();
  useEffect(() => {
    if (!prepared || reduced || finished || !container.current) { onReady(); return; }
    let disposed = false;
    let animation: AnimationItem | undefined;
    const stop = () => { if (!disposed) { setFinished(true); onReady(); } };
    const onVisibility = () => { if (document.hidden) stop(); };
    document.addEventListener("visibilitychange", onVisibility);
    const deadline = setTimeout(stop, 8000);
    const readyDeadline = setTimeout(stop, 1500);
    try {
      animation = prepared.player.loadAnimation({ container: container.current, renderer: "svg", loop: false, autoplay: false, animationData: prepared.animationData });
      player.current = animation;
      // Interpolate original 30/60fps keyframes on every browser display tick.
      animation.setSubframe(true);
      animation.addEventListener("complete", stop);
      animation.addEventListener("data_failed", stop);
      const ready = () => {
        if (disposed) return;
        clearTimeout(readyDeadline);
        // Build the vector renderer while the card is still invisible, then
        // release both animations together on the next presentation commit.
        onReady();
      };
      animation.addEventListener("DOMLoaded", ready);
      if (animation.isLoaded) ready();
    } catch { stop(); }
    return () => {
      disposed = true;
      clearTimeout(deadline);
      clearTimeout(readyDeadline);
      document.removeEventListener("visibilitychange", onVisibility);
      animation?.destroy();
      player.current = null;
    };
  }, [prepared, reduced, finished, onReady]);
  useLayoutEffect(() => {
    const animation = player.current;
    if (startedAt === null || !animation?.isLoaded || !container.current || finished || reduced) return;
    const frame = Math.max(0, performance.now() - startedAt) / 1000 * animation.frameRate;
    if (frame >= animation.totalFrames) { setFinished(true); return; }
    animation.goToAndPlay(frame, true);
    container.current.style.visibility = "visible";
  }, [startedAt, finished, reduced]);
  if (!prepared || reduced || finished) return null;
  return <div ref={container} className="announcement__effect" data-effect={prepared.id} style={{ visibility: "hidden" }} aria-hidden="true" />;
}

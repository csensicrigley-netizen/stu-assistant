import { Capacitor, registerPlugin } from "@capacitor/core";

type HapticKind = "press" | "release" | "selection";
const KEY = "stu.haptics.strength";
export const HAPTIC_STRENGTH_CHANGED = "stu:haptics-strength";
const cancelledClicks = new WeakSet<Event>();
const native = registerPlugin<{
  play(options: { kind: HapticKind; strength: number }): Promise<void>;
  cancel(): Promise<void>;
}>("InteractionHaptics");

export function clampStrength(value: number): number {
  return Number.isFinite(value) ? Math.max(0, Math.min(100, Math.round(value))) : 100;
}

let strength = 100;
try {
  const saved = localStorage.getItem(KEY);
  if (saved !== null && saved.trim() !== "") strength = clampStrength(Number(saved));
  // Preserve an explicit opt-out from early test builds. Lab parameters are retired.
  else if (localStorage.getItem("stu.haptics") === "off") strength = 0;
} catch { /* Feedback must work even when storage is unavailable. */ }

export function getHapticStrength(): number { return strength; }

export function setHapticStrength(value: number): number {
  const previous = strength;
  strength = clampStrength(value);
  try { localStorage.setItem(KEY, String(strength)); } catch { /* In-memory choice. */ }
  if (strength === 0) cancelHaptics();
  if (strength !== previous) window.dispatchEvent(new CustomEvent(HAPTIC_STRENGTH_CHANGED));
  return strength;
}

/** Navigation must not commit a click left over from a cancelled drag. */
export function isCancelledInteraction(event: Event): boolean { return cancelledClicks.has(event); }

export function cancelHaptics(): void {
  if (Capacitor.getPlatform() === "android") void native.cancel().catch(() => {});
}

/** Only direct foreground interactions call this; no vibration from renders or polling. */
export function haptic(kind: HapticKind): void {
  if (strength === 0 || document.visibilityState !== "visible" || Capacitor.getPlatform() !== "android") return;
  void native.play({ kind, strength }).catch(() => {});
}

const CONTROL = 'button,a[href],[role="button"],[role="checkbox"],[role="switch"],[role="radio"],[role="tab"]';

function control(target: EventTarget | null, includeSelected = false): HTMLElement | null {
  if (!(target instanceof Element)) return null;
  const el = target.closest<HTMLElement>(CONTROL);
  if (!el || el.closest('[data-haptic="none"],[data-haptic="manual"],[aria-disabled="true"],[aria-busy="true"],[inert]') || el.matches(":disabled")) return null;
  if (!includeSelected && el.matches('[role="radio"],[role="tab"],input[type="radio"]') &&
      (el.getAttribute("aria-checked") === "true" || el.getAttribute("aria-selected") === "true" ||
       (el instanceof HTMLInputElement && el.checked))) return null;
  if (!includeSelected && el.hasAttribute("aria-current") && el.getAttribute("aria-current") !== "false") return null;
  return el;
}

/** One capture listener covers nested icons, portalled dialogs and assistive clicks.
 * The release belongs to a committed click, never a cancelled/scrolling gesture.
 */
export function installInteractionFeedback(): () => void {
  let pointer: { id: number; x: number; y: number; cancelled: boolean } | null = null;
  let keyHeld = false;
  let keyClicked = false;
  const down = (event: PointerEvent) => {
    if (!event.isTrusted || !event.isPrimary || event.button !== 0) return;
    pointer = null;
    const candidate = control(event.target, true);
    const el = candidate?.closest('[data-haptic="selection"]') ? candidate : control(event.target);
    if (!el) return;
    pointer = { id: event.pointerId, x: event.clientX, y: event.clientY, cancelled: false };
    if (!el.closest('[data-haptic="selection"]')) haptic("press");
  };
  const move = (event: PointerEvent) => {
    if (pointer?.id === event.pointerId && !pointer.cancelled && Math.hypot(event.clientX - pointer.x, event.clientY - pointer.y) > 12) {
      pointer.cancelled = true;
      cancelHaptics();
    }
  };
  const cancel = () => {
    if (pointer) pointer.cancelled = true;
    keyHeld = false;
    keyClicked = false;
    cancelHaptics();
  };
  const key = (event: KeyboardEvent) => {
    if (!event.isTrusted || event.repeat || (event.key !== "Enter" && event.key !== " ")) return;
    const el = control(event.target);
    if (!el || (event.key === " " && el.matches("a[href]"))) return;
    keyHeld = true;
    keyClicked = false;
    if (!el.closest('[data-haptic="selection"]')) haptic("press");
  };
  const keyUp = (event: KeyboardEvent) => {
    if (event.key === "Enter" || event.key === " ") keyHeld = false;
  };
  const click = (event: MouseEvent) => {
    if (!event.isTrusted || event.button !== 0) return;
    if (event.detail > 0) {
      const cancelled = pointer?.cancelled;
      pointer = null;
      if (cancelled) {
        cancelledClicks.add(event);
        return;
      }
    } else if (keyHeld) {
      if (keyClicked) return;
      keyClicked = true;
    }
    const el = control(event.target);
    if (el) haptic(el.closest('[data-haptic="selection"]') ? "selection" : "release");
  };
  const change = (event: Event) => {
    if (!event.isTrusted || !(event.target instanceof HTMLElement) ||
        !event.target.matches('select,input[type="checkbox"],input[type="radio"]') || event.target.matches(":disabled") ||
        event.target.closest('[data-haptic="none"],[data-haptic="manual"],[inert],[aria-disabled="true"]')) return;
    haptic("selection");
  };
  const visibility = () => { if (document.visibilityState !== "visible") cancel(); };
  document.addEventListener("pointerdown", down, true);
  document.addEventListener("pointermove", move, true);
  document.addEventListener("pointercancel", cancel, true);
  document.addEventListener("keydown", key, true);
  document.addEventListener("keyup", keyUp, true);
  document.addEventListener("click", click, true);
  document.addEventListener("change", change, true);
  document.addEventListener("visibilitychange", visibility);
  window.addEventListener("blur", cancel);
  return () => {
    document.removeEventListener("pointerdown", down, true);
    document.removeEventListener("pointermove", move, true);
    document.removeEventListener("pointercancel", cancel, true);
    document.removeEventListener("keydown", key, true);
    document.removeEventListener("keyup", keyUp, true);
    document.removeEventListener("click", click, true);
    document.removeEventListener("change", change, true);
    document.removeEventListener("visibilitychange", visibility);
    window.removeEventListener("blur", cancel);
    cancel();
  };
}

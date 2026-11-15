import { apiBase, getToken, IS_NATIVE } from "./http";
import { APP_VERSION_CODE } from "./version";

export const ANNOUNCEMENT_EFFECTS = ["star-struck", "heart-on-fire", "folded-hands", "party-popper", "keycap-one-6"] as const;
export type AnnouncementEffectId = typeof ANNOUNCEMENT_EFFECTS[number];

export interface Announcement {
  id: string;
  revision: number;
  title: string;
  body: string;
  eyebrow: string;
  image_url: string | null;
  image_alt: string;
  action_label: string;
  action_url: string | null;
  display: "once" | "daily" | "always";
  priority: number;
  effects: AnnouncementEffectId[];
}

type Receipts = Record<string, string>;
const RECEIPT_PREFIX = "stu.announcements.v1:";
const memoryReceipts = new Map<string, Receipts>();
const shownThisOpening = new Set<string>();

/** A visible return is a new opening; persistent id/revision receipts still apply. */
export function resetAnnouncementOpening(scope: string): void { shownThisOpening.delete(scope); }

/** Presentation receipts are separate for each server and assistant account. */
export function announcementScope(base: string, token: string | null, origin: string): string {
  let subject = "guest";
  if (token) {
    try {
      const encoded = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
      const payload: unknown = JSON.parse(atob(encoded));
      if (payload && typeof payload === "object" && "sub" in payload && typeof payload.sub === "string") {
        subject = payload.sub;
      }
    } catch {
      // Never persist the token itself, including malformed tokens.
      subject = "unknown";
    }
  }
  return JSON.stringify([(base || origin).replace(/\/+$/, ""), subject]);
}

export function currentAnnouncementScope(): string {
  return announcementScope(apiBase(), getToken(), location.origin);
}

export function announcementDay(now = new Date()): string {
  // Business dates stay consistent even when a device uses another timezone.
  return new Date(now.getTime() + 8 * 60 * 60_000).toISOString().slice(0, 10);
}

function receiptId(item: Announcement): string {
  return JSON.stringify([item.id, item.revision]);
}

function receipts(scope: string): Receipts {
  if (memoryReceipts.has(scope)) return memoryReceipts.get(scope)!;
  let found: Receipts = {};
  try {
    const value: unknown = JSON.parse(localStorage.getItem(RECEIPT_PREFIX + scope) || "{}");
    if (value && typeof value === "object" && !Array.isArray(value)) {
      found = Object.fromEntries(Object.entries(value).filter((entry): entry is [string, string] => typeof entry[1] === "string"));
    }
  } catch {
    // Private mode/quota/corrupt storage must never interrupt the home screen.
  }
  memoryReceipts.set(scope, found);
  return found;
}

export function announcementEligible(item: Announcement, scope: string, now = new Date()): boolean {
  if (shownThisOpening.has(scope)) return false;
  const receipt = receipts(scope)[receiptId(item)];
  if (item.display === "always") return true;
  return item.display === "daily" ? receipt !== announcementDay(now) : !receipt;
}

/** Record only once the dialog is actually mounted, including a close via Back. */
export function markAnnouncementShown(item: Announcement, scope: string, now = new Date()): void {
  shownThisOpening.add(scope);
  const updated = { ...receipts(scope), [receiptId(item)]: announcementDay(now) };
  // Bound local storage growth while keeping recent campaign receipts.
  const bounded = Object.fromEntries(Object.entries(updated).slice(-200));
  memoryReceipts.set(scope, bounded);
  try {
    localStorage.setItem(RECEIPT_PREFIX + scope, JSON.stringify(bounded));
  } catch {
    // In-memory receipts still suppress repeats during this opening.
  }
}

export function safeAnnouncementLink(value: unknown): string | null {
  if (typeof value !== "string" || !/^https:\/\//i.test(value)) return null;
  try {
    const url = new URL(value);
    return url.protocol === "https:" && !url.username && !url.password ? url.href : null;
  } catch {
    return null;
  }
}

export function announcementImageUrl(value: unknown, base = apiBase()): string | null {
  if (typeof value !== "string") return null;
  if (/^\/v1\/announcements\/assets\/[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(value)) return base + value;
  return safeAnnouncementLink(value);
}

/** Validate the public feed at the boundary; no remote HTML is ever rendered. */
export function parseAnnouncements(value: unknown): Announcement[] {
  if (!value || typeof value !== "object" || !("announcements" in value) || !Array.isArray(value.announcements)) return [];
  return value.announcements.slice(0, 50).flatMap((raw: unknown): Announcement[] => {
    if (!raw || typeof raw !== "object") return [];
    const item = raw as Record<string, unknown>;
    if (typeof item.id !== "string" || !item.id || item.id.length > 128 || !Number.isSafeInteger(item.revision) || Number(item.revision) < 1
      || typeof item.title !== "string" || !item.title.trim() || item.title.length > 200
      || typeof item.body !== "string" || item.body.length > 4000
      || !["once", "daily", "always"].includes(String(item.display))) return [];
    return [{
      id: item.id, revision: Number(item.revision), title: item.title, body: item.body,
      eyebrow: typeof item.eyebrow === "string" ? item.eyebrow.slice(0, 80) : "开发者来信",
      image_url: announcementImageUrl(item.image_url),
      image_alt: typeof item.image_alt === "string" ? item.image_alt.slice(0, 300) : "",
      action_label: typeof item.action_label === "string" ? item.action_label.slice(0, 80) : "了解更多",
      action_url: safeAnnouncementLink(item.action_url),
      display: item.display as Announcement["display"],
      priority: typeof item.priority === "number" && Number.isFinite(item.priority) ? item.priority : 0,
      effects: Array.isArray(item.effects) ? [...new Set(item.effects.slice(0, 5).filter((id): id is AnnouncementEffectId => ANNOUNCEMENT_EFFECTS.includes(id)))] : [],
    }];
  }).sort((a, b) => b.priority - a.priority);
}

/** Public, optional metadata: do not attach credentials or trigger auth recovery. */
export async function fetchAnnouncements(signal: AbortSignal): Promise<Announcement[]> {
  const platform = IS_NATIVE ? "android" : "web";
  const response = await fetch(`${apiBase()}/v1/announcements?platform=${platform}&version_code=${APP_VERSION_CODE}`, {
    signal, cache: "no-store", credentials: "omit", headers: { Accept: "application/json" },
  });
  if (!response.ok) return [];
  return parseAnnouncements(await response.json());
}

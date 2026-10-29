/**
 * One icon family, drawn for this App: 24px grid, 1.75 stroke, round joins.
 * Decorative by default (aria-hidden); pass `label` when the icon is the only
 * thing that names a control.
 */
export type IconName =
  | "today"
  | "week"
  | "study"
  | "campus"
  | "me"
  | "chevron-left"
  | "chevron-right"
  | "chevron-down"
  | "arrow-right"
  | "external"
  | "search"
  | "check"
  | "close"
  | "pin"
  | "person"
  | "bolt"
  | "bus"
  | "book"
  | "calendar"
  | "bell"
  | "refresh"
  | "sun"
  | "moon"
  | "monitor"
  | "shield"
  | "alert"
  | "info"
  | "clock"
  | "link"
  | "trash"
  | "logout"
  | "walk"
  | "spark";

const P: Record<IconName, string> = {
  today: "M12 3.5v2M12 18.5v2M3.5 12h2M18.5 12h2M12 8.2a3.8 3.8 0 1 0 0 7.6 3.8 3.8 0 0 0 0-7.6Z",
  week: "M4.5 7.5A2.5 2.5 0 0 1 7 5h10a2.5 2.5 0 0 1 2.5 2.5v10A2.5 2.5 0 0 1 17 20H7a2.5 2.5 0 0 1-2.5-2.5v-10ZM4.5 9.8h15M8.5 3.5v3M15.5 3.5v3M8 13.5h3M8 16.5h6",
  study: "M4.5 6.2c2.6-1 5.1-.8 7.5.7v12.4c-2.4-1.5-4.9-1.7-7.5-.7V6.2ZM19.5 6.2c-2.6-1-5.1-.8-7.5.7v12.4c2.4-1.5 4.9-1.7 7.5-.7V6.2Z",
  campus: "M3.5 20h17M5.5 20v-8.5M18.5 20v-8.5M9.5 20v-5h5v5M3.5 11.5 12 5l8.5 6.5",
  me: "M12 12.2a3.7 3.7 0 1 0 0-7.4 3.7 3.7 0 0 0 0 7.4ZM5 20c.8-3.5 3.6-5.4 7-5.4s6.2 1.9 7 5.4",
  "chevron-left": "M14.5 6 8.5 12l6 6",
  "chevron-right": "M9.5 6l6 6-6 6",
  "chevron-down": "M6 9.5l6 6 6-6",
  "arrow-right": "M5 12h13.5M13 6.5l5.5 5.5-5.5 5.5",
  external: "M13.5 5H19v5.5M19 5l-8 8M17 14v4a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 5 18V8.5A1.5 1.5 0 0 1 6.5 7H10",
  search: "M10.8 17.6a6.8 6.8 0 1 0 0-13.6 6.8 6.8 0 0 0 0 13.6ZM16 16l4 4",
  check: "M5 12.5l4.5 4.5L19 7.5",
  close: "M6.5 6.5l11 11M17.5 6.5l-11 11",
  pin: "M12 21s6.5-5.6 6.5-11a6.5 6.5 0 0 0-13 0c0 5.4 6.5 11 6.5 11ZM12 12.3a2.3 2.3 0 1 0 0-4.6 2.3 2.3 0 0 0 0 4.6Z",
  person: "M12 11.5a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4ZM6 19.5c.7-3 3-4.6 6-4.6s5.3 1.6 6 4.6",
  bolt: "M13 3.5 6 13.2h5.2L10.5 20.5 18 10.6h-5.3L13 3.5Z",
  bus: "M6.5 17.5V6.8A2.8 2.8 0 0 1 9.3 4h5.4a2.8 2.8 0 0 1 2.8 2.8v10.7M6.5 12h11M6.5 17.5h11M8.5 17.5V20M15.5 17.5V20M9 14.8h.01M15 14.8h.01",
  book: "M6 4.5h10.5A1.5 1.5 0 0 1 18 6v13.5H7.5A1.5 1.5 0 0 1 6 18V4.5ZM6 18a1.5 1.5 0 0 1 1.5-1.5H18M9.5 8.5h5",
  calendar: "M4.5 7.5A2.5 2.5 0 0 1 7 5h10a2.5 2.5 0 0 1 2.5 2.5v10A2.5 2.5 0 0 1 17 20H7a2.5 2.5 0 0 1-2.5-2.5v-10ZM4.5 9.8h15M8.5 3.5v3M15.5 3.5v3",
  bell: "M6.5 16.5V11a5.5 5.5 0 0 1 11 0v5.5l1.5 1.5H5l1.5-1.5ZM10 20h4",
  refresh: "M19 12a7 7 0 1 1-2.1-5M19 4.5V8h-3.5",
  sun: "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM12 2.8v1.7M12 19.5v1.7M2.8 12h1.7M19.5 12h1.7M5.5 5.5l1.2 1.2M17.3 17.3l1.2 1.2M5.5 18.5l1.2-1.2M17.3 6.7l1.2-1.2",
  moon: "M19 14.2A7.5 7.5 0 0 1 9.8 5a7.5 7.5 0 1 0 9.2 9.2Z",
  monitor: "M4 6.5A1.5 1.5 0 0 1 5.5 5h13A1.5 1.5 0 0 1 20 6.5v8a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 14.5v-8ZM9 20h6M12 16v4",
  shield: "M12 3.5 5 6.2v5.3c0 4.3 3 7.6 7 9 4-1.4 7-4.7 7-9V6.2l-7-2.7ZM9 12l2.2 2.2L15.5 10",
  alert: "M12 4 3 19.5h18L12 4ZM12 10v4.2M12 17h.01",
  info: "M12 20.5a8.5 8.5 0 1 0 0-17 8.5 8.5 0 0 0 0 17ZM12 11v5.5M12 7.8h.01",
  clock: "M12 20.5a8.5 8.5 0 1 0 0-17 8.5 8.5 0 0 0 0 17ZM12 7.5V12l3 2",
  link: "M10 14a4 4 0 0 0 5.7 0l2.8-2.8a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-2.8 2.8a4 4 0 0 0 5.7 5.7l1-1",
  trash: "M5 7h14M10 4.5h4M7 7l.8 12a1.5 1.5 0 0 0 1.5 1.5h5.4a1.5 1.5 0 0 0 1.5-1.5L17 7M10.5 11v5.5M13.5 11v5.5",
  logout: "M14 4.5H7A1.5 1.5 0 0 0 5.5 6v12A1.5 1.5 0 0 0 7 19.5h7M10.5 12H20M16.5 8.5 20 12l-3.5 3.5",
  walk: "M13 5.5a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2ZM10 21l2.2-5.5 2.8 2.5V21M8 12.5l2-4.5 4 1 2 3.5 2.5 1M12.2 15.5 11 9",
  spark: "M12 3.5c.6 4.3 2.9 6.6 7.2 7.2-4.3.6-6.6 2.9-7.2 7.2-.6-4.3-2.9-6.6-7.2-7.2 4.3-.6 6.6-2.9 7.2-7.2Z",
};

export function Icon({
  name,
  size = 20,
  label,
  strokeWidth = 1.75,
  className,
}: {
  name: IconName;
  size?: number;
  label?: string;
  strokeWidth?: number;
  className?: string;
}) {
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      focusable="false"
    >
      <path d={P[name]} />
    </svg>
  );
}

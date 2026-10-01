import { ReactNode, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { ChartMode } from './Chart';

/* ---------------------------------------------------------------- icons
   Small inline SVG set (Lucide-style, currentColor). No icon dependency. */
const ICON_PATHS: Record<string, ReactNode> = {
  dashboard: (<><rect x="3" y="3" width="7" height="7" rx="1.5" /><rect x="14" y="3" width="7" height="7" rx="1.5" /><rect x="14" y="14" width="7" height="7" rx="1.5" /><rect x="3" y="14" width="7" height="7" rx="1.5" /></>),
  accounts: (<><rect x="3" y="4" width="18" height="7" rx="2" /><rect x="3" y="13" width="18" height="7" rx="2" /><path d="M6.5 7.5h.01M6.5 16.5h.01" /></>),
  mystats: (<><circle cx="12" cy="8" r="4" /><path d="M4 20c0-4 4-6 8-6s8 2 8 6" /></>),
  stats: (<><path d="M4 20V10" /><path d="M10 20V4" /><path d="M16 20v-7" /><path d="M3 20h18" /></>),
  tokens: (<><circle cx="8" cy="15" r="4" /><path d="M10.8 12.2 20 3" /><path d="m17 6 2 2" /><path d="m14.5 8.5 2 2" /></>),
  routing: (<><circle cx="6" cy="6" r="2.4" /><circle cx="6" cy="18" r="2.4" /><circle cx="18" cy="12" r="2.4" /><path d="M6 8.4v7.2" /><path d="M8.4 6H12a3 3 0 0 1 3 3v1" /><path d="M8.4 18H12a3 3 0 0 0 3-3v-1" /></>),
  pricing: (<><path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0l-7-7A2 2 0 0 1 3 12V4a1 1 0 0 1 1-1h8a2 2 0 0 1 1.4.6l7.2 7.2a2 2 0 0 1 0 2.6z" /><path d="M7.5 7.5h.01" /></>),
  users: (<><circle cx="9" cy="8" r="3.5" /><path d="M2.5 20c0-3.6 3-5.2 6.5-5.2s6.5 1.6 6.5 5.2" /><path d="M16 5.2a3.5 3.5 0 0 1 0 6.6" /><path d="M17.5 15c2.4.5 4 2 4 5" /></>),
  menu: (<><line x1="3" y1="6" x2="21" y2="6" /><line x1="3" y1="12" x2="21" y2="12" /><line x1="3" y1="18" x2="21" y2="18" /></>),
  collapse: (<><path d="M11 6l-6 6 6 6" /><path d="M18 6l-6 6 6 6" /></>),
  expand: (<><path d="M13 6l6 6-6 6" /><path d="M6 6l6 6-6 6" /></>),
  'chevron-down': (<path d="M6 9l6 6 6-6" />),
  sun: (<><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4" /></>),
  moon: (<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" />),
  auto: (<><rect x="3" y="4" width="18" height="13" rx="2" /><path d="M8 21h8M12 17v4" /></>),
  logout: (<><path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3" /><path d="M10 17l5-5-5-5" /><path d="M15 12H3" /></>),
  settings: (<><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" /></>),
  sparkle: (<path d="M12 3l1.9 5.6a2 2 0 0 0 1.3 1.3L21 12l-5.8 1.9a2 2 0 0 0-1.3 1.3L12 21l-1.9-5.8a2 2 0 0 0-1.3-1.3L3 12l5.8-1.9a2 2 0 0 0 1.3-1.3z" />),
  bars: (<><rect x="4" y="12" width="4" height="8" rx="1" /><rect x="10" y="7" width="4" height="13" rx="1" /><rect x="16" y="14" width="4" height="6" rx="1" /></>),
  // ---- chat ----
  chat: (<><path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 9.6 9.6 0 0 1-2.8-.4L3 21l1.6-4.6A8.2 8.2 0 0 1 3.6 11.5 8.4 8.4 0 0 1 12 3.1a8.4 8.4 0 0 1 9 8.4z" /></>),
  plus: (<><path d="M12 5v14M5 12h14" /></>),
  search: (<><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></>),
  send: (<><path d="M4 12 20 4l-8 16-2-6z" /><path d="m10 14 10-10" /></>),
  stop: (<><rect x="6" y="6" width="12" height="12" rx="2" /></>),
  clip: (<path d="M21 11.5 12.5 20a5 5 0 0 1-7-7l8.5-8.5a3.4 3.4 0 0 1 4.8 4.8L10.2 17.8a1.7 1.7 0 0 1-2.4-2.4l7.8-7.8" />),
  globe: (<><circle cx="12" cy="12" r="9" /><path d="M3 12h18" /><path d="M12 3a15 15 0 0 1 0 18 15 15 0 0 1 0-18z" /></>),
  brain: (<><path d="M9.5 4a2.5 2.5 0 0 0-2.4 3.2A2.6 2.6 0 0 0 5 9.8a2.6 2.6 0 0 0 1 2 2.6 2.6 0 0 0 1.4 4.7A2.5 2.5 0 0 0 12 17V6.5A2.5 2.5 0 0 0 9.5 4z" /><path d="M14.5 4A2.5 2.5 0 0 1 17 6.5a2.6 2.6 0 0 1 2 2.5 2.6 2.6 0 0 1-1 2 2.6 2.6 0 0 1-1.4 4.7A2.5 2.5 0 0 1 12 17" /></>),
  ghost: (<><path d="M12 3a7 7 0 0 0-7 7v10l2.5-2 2.2 2 2.3-2 2.3 2 2.2-2 2.5 2V10a7 7 0 0 0-7-7z" /><path d="M9.5 10h.01M14.5 10h.01" /></>),
  memory: (<><rect x="6" y="6" width="12" height="12" rx="2.5" /><path d="M9.5 3v3M14.5 3v3M9.5 18v3M14.5 18v3M3 9.5h3M3 14.5h3M18 9.5h3M18 14.5h3" /></>),
  import: (<><path d="M12 3v11" /><path d="m8 10 4 4 4-4" /><path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" /></>),
  copy: (<><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V6a2 2 0 0 1 2-2h9" /></>),
  edit: (<><path d="M4 20h4l10-10-4-4L4 16z" /><path d="m14.5 5.5 4 4" /></>),
  retry: (<><path d="M4 12a8 8 0 1 1 2.6 5.9" /><path d="M4 20v-5h5" /></>),
  trash: (<><path d="M4 7h16" /><path d="M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2" /><path d="M6 7v12a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7" /></>),
  pin: (<><path d="M9 3h6l-1 6 4 3v2H6v-2l4-3z" /><path d="M12 14v7" /></>),
  file: (<><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5" /></>),
  pdf: (<><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5" /><path d="M8.5 16v-3h1.2a1.1 1.1 0 0 1 0 2.2H8.5" /></>),
  sidebar: (<><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M9 4v16" /></>),
  warn: (<><path d="M12 4 2.5 20h19z" /><path d="M12 10v4M12 17.5h.01" /></>),
  dots: (<><circle cx="5" cy="12" r="1.4" /><circle cx="12" cy="12" r="1.4" /><circle cx="19" cy="12" r="1.4" /></>),
  rewind: (<><path d="m11 7-6 5 6 5z" /><path d="m19 7-6 5 6 5z" /></>),
  ask: (<><circle cx="12" cy="12" r="9" /><path d="M9.6 9.2a2.5 2.5 0 1 1 3.3 2.9c-.6.2-.9.8-.9 1.4v.4" /><path d="M12 17h.01" /></>),
  model: (<><path d="M12 3.2 14 8l4.8 2-4.8 2L12 16.8 10 12 5.2 10 10 8z" /><path d="M18.5 15.5 19 17l1.5.5L19 18l-.5 1.5L18 18l-1.5-.5L18 17z" /></>),
  area: (<><path d="M3 16c3 0 3-6 6-6s4 4 6 4 3-5 6-5" /><path d="M3 20h18" /></>),
};

export function Icon({ name, size = 18 }: { name: string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {ICON_PATHS[name] ?? null}
    </svg>
  );
}

export function IconButton({ icon, onClick, label, className = '' }: {
  icon: string; onClick: () => void; label: string; className?: string;
}) {
  return (
    <button type="button" className={`iconbtn ${className}`} onClick={onClick} aria-label={label} title={label}>
      <Icon name={icon} />
    </button>
  );
}

/* ---------------------------------------------------------------- select */
export interface Opt { value: string; label: string; }
export function Select({ value, options, onChange, minWidth, ariaLabel }: {
  value: string; options: Opt[]; onChange: (v: string) => void; minWidth?: number; ariaLabel?: string;
}) {
  return (
    <span className="uiselect">
      <select aria-label={ariaLabel} value={value} onChange={(e) => onChange(e.target.value)}
        style={minWidth ? { minWidth } : undefined}>
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
      <span className="chev"><Icon name="chevron-down" /></span>
    </span>
  );
}

/* ---------------------------------------------------------------- number input
   Spinner-free numeric field. Commits on blur/Enter: empty or (unless allowNegative)
   negative values snap to `min` (0 by default), and the value is clamped to [min, max]. */
export function NumberInput({ value, onChange, min = 0, max, step, allowNegative = false, placeholder, className = '', mono = false }: {
  value: number; onChange: (v: number) => void;
  min?: number | null; max?: number | null; step?: number; allowNegative?: boolean;
  placeholder?: string; className?: string; mono?: boolean;
}) {
  const [text, setText] = useState(() => String(value));
  // reflect external value changes (only fires when `value` itself changes, so it never
  // clobbers an in-progress edit triggered by unrelated re-renders)
  useEffect(() => { setText(String(value)); }, [value]);

  function commit() {
    let n = text.trim() === '' ? NaN : Number(text);
    if (!isFinite(n)) n = 0;
    if (!allowNegative && n < 0) n = 0;
    if (min != null && n < min) n = min;
    if (max != null && n > max) n = max;
    onChange(n);
    setText(String(n));
  }
  return (
    <input
      type="text" inputMode="decimal" step={step}
      className={`num-input${mono ? ' mono' : ''} ${className}`.trim()}
      value={text} placeholder={placeholder}
      onChange={(e) => setText(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }}
    />
  );
}

/* ---------------------------------------------------------------- segmented */
export function Segmented<T extends string | number>({ value, options, onChange, className = '', disabled = false }: {
  value: T; options: { value: T; label: ReactNode }[]; onChange: (v: T) => void; className?: string; disabled?: boolean;
}) {
  return (
    <div className={`segmented ${className}` + (disabled ? ' disabled' : '')} role="group">
      {options.map((o) => (
        <button key={String(o.value)} type="button" disabled={disabled}
          className={'seg' + (o.value === value ? ' active' : '')}
          aria-pressed={o.value === value}
          onClick={() => { if (!disabled) onChange(o.value); }}>{o.label}</button>
      ))}
    </div>
  );
}

/* ---------------------------------------------------------------- theme */
export type ThemeMode = 'auto' | 'light' | 'dark';
const THEME_KEY = 'cp-theme';

function applyTheme(m: ThemeMode) {
  const el = document.documentElement;
  if (m === 'auto') el.removeAttribute('data-theme');
  else el.setAttribute('data-theme', m);
}
export function getStoredTheme(): ThemeMode {
  const v = (typeof localStorage !== 'undefined' && localStorage.getItem(THEME_KEY)) as ThemeMode | null;
  return v === 'light' || v === 'dark' ? v : 'auto';
}
/** Apply the persisted theme once at startup (call before render to avoid a flash). */
export function initTheme() { applyTheme(getStoredTheme()); }

export function ThemeToggle() {
  const [mode, setMode] = useState<ThemeMode>(getStoredTheme);
  function set(m: ThemeMode) { setMode(m); try { localStorage.setItem(THEME_KEY, m); } catch { /* ignore */ } applyTheme(m); }
  return (
    <Segmented<ThemeMode> className="theme" value={mode} onChange={set} options={[
      { value: 'auto', label: <><Icon name="auto" size={15} /><span>Auto</span></> },
      { value: 'light', label: <><Icon name="sun" size={15} /><span>Light</span></> },
      { value: 'dark', label: <><Icon name="moon" size={15} /><span>Dark</span></> },
    ]} />
  );
}

/* ---------------------------------------------------------------- chart mode
   Bars vs smooth stacked area for the Spend/Tokens charts — a display preference, so it
   sticks across reloads like the theme choice. */
const CHART_MODE_KEY = 'cp-chart-mode';
export function getStoredChartMode(): ChartMode {
  try { const v = localStorage.getItem(CHART_MODE_KEY); if (v === 'bars' || v === 'area') return v; } catch { /* ignore */ }
  return 'bars';
}
export function useChartMode(): [ChartMode, (m: ChartMode) => void] {
  const [mode, setMode] = useState<ChartMode>(getStoredChartMode);
  const set = (m: ChartMode) => { setMode(m); try { localStorage.setItem(CHART_MODE_KEY, m); } catch { /* ignore */ } };
  return [mode, set];
}

/* ---------------------------------------------------------------- existing primitives */
export function Check({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label?: ReactNode }) {
  return (
    <label className="check">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span className="box">
        <svg viewBox="0 0 24 24" fill="none" stroke="var(--on-accent)" strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="20 6 9 17 4 12" />
        </svg>
      </span>
      {label != null && <span>{label}</span>}
    </label>
  );
}

export function Switch({ checked, onChange }: { checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="switch">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span className="track" />
    </label>
  );
}

/**
 * A dialog, rendered into `document.body` through a portal.
 *
 * The portal is load-bearing, not tidiness: the chat page nests its dialogs inside a column that
 * clips its own overflow and stacks a drawer, a scrim and a sticky header on top of each other.
 * Left in place, a dialog opened from there ends up under that furniture — visible but unclickable.
 * Rendering at the document root puts every dialog in the same, predictable stacking context.
 */
export function Modal({ title, onClose, children, footer, width }: {
  title: string; onClose: () => void; children: ReactNode; footer?: ReactNode; width?: number;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  // A dialog is modal: the page behind it must not scroll away under the user's finger.
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, []);

  return createPortal(
    <div className="overlay" onMouseDown={onClose}>
      <div className="modal" style={width ? { width } : undefined} onMouseDown={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <h3>{title}</h3>
          <button className="x" onClick={onClose} aria-label="Close">×</button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>,
    document.body,
  );
}

/**
 * A menu anchored to the element that opened it, rendered at the document root. Same reason as
 * [Modal] — plus the chat list scrolls, and an in-flow menu would be clipped by it.
 */
export function AnchoredMenu({ anchor, onClose, children, align = 'right' }: {
  anchor: HTMLElement | null; onClose: () => void; children: ReactNode; align?: 'left' | 'right';
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    // Any scroll moves the anchor out from under the menu; closing beats chasing it.
    window.addEventListener('keydown', onKey);
    window.addEventListener('scroll', onClose, true);
    window.addEventListener('resize', onClose);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('scroll', onClose, true);
      window.removeEventListener('resize', onClose);
    };
  }, [onClose]);

  if (!anchor) return null;
  const r = anchor.getBoundingClientRect();
  const style: React.CSSProperties = align === 'right'
    ? { top: r.bottom + 6, right: Math.max(8, window.innerWidth - r.right) }
    : { top: r.bottom + 6, left: Math.max(8, r.left) };

  return createPortal(
    <>
      <div className="menu-backdrop" onMouseDown={onClose} />
      <div className="row-menu floating" style={style} role="menu" onMouseDown={(e) => e.stopPropagation()}>
        {children}
      </div>
    </>,
    document.body,
  );
}

export function Copy({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [done, setDone] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      const ta = document.createElement('textarea');
      ta.value = text; document.body.appendChild(ta); ta.select();
      document.execCommand('copy'); document.body.removeChild(ta);
    }
    setDone(true); setTimeout(() => setDone(false), 1400);
  }
  return <button className="ghost sm copybtn" onClick={copy}>{done ? '✓ Copied' : label}</button>;
}

export function CodeBlock({ text }: { text: string }) {
  return (
    <div className="codeblock">
      {text}
      <Copy text={text} />
    </div>
  );
}

/* ---------------------------------------------------------------- OS icons
   Filled brand glyphs (fill=currentColor) — logos read cleaner filled than the
   stroke-based Icon set, so they get their own component. */
const OS_ICON: Record<string, ReactNode> = {
  windows: (<><rect x="3" y="4" width="8" height="8" rx="0.5" /><rect x="13" y="4" width="8" height="8" rx="0.5" /><rect x="3" y="14" width="8" height="8" rx="0.5" /><rect x="13" y="14" width="8" height="8" rx="0.5" /></>),
  mac: (<path d="M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014-2.117 3.675-.546 9.103 1.519 12.09 1.013 1.454 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 1.637-.026 2.676-1.48 3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857-.026-3.04 2.48-4.494 2.597-4.559-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.61 1.09zM15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532 1.818-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701" />),
  linux: (<>
    <ellipse cx="9.4" cy="20.4" rx="1.8" ry="0.85" />
    <ellipse cx="14.6" cy="20.4" rx="1.8" ry="0.85" />
    <path fillRule="evenodd" clipRule="evenodd" d="M12 3C9 3 7.5 5.2 7.5 7.6c0 1.2.3 2 .3 2.8 0 1.6-1.8 3.1-1.8 6 0 2.5 2.4 4.1 6 4.1s6-1.6 6-4.1c0-2.9-1.8-4.4-1.8-6 0-.8.3-1.6.3-2.8C16.5 5.2 15 3 12 3zm-1.4 3.8a.85.9 0 1 1 0 1.8.85.9 0 0 1 0-1.8zm2.8 0a.85.9 0 1 1 0 1.8.85.9 0 0 1 0-1.8zM12 8.2l1 1-1 1-1-1 1-1z" />
  </>),
};

export function OsIcon({ name, size = 16 }: { name: string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      {OS_ICON[name] ?? null}
    </svg>
  );
}

/* ---------------------------------------------------------------- connect scripts
   OS-aware setup snippets for Claude Code. Auto-detects the current OS for the
   default tab; the choice is manual + remembered. `token` may be a real cxp_...
   or a placeholder; `wrapper` adds the persistent claude-proxy install. */
export type Os = 'mac' | 'linux' | 'windows';
export type WinShell = 'powershell' | 'cmd';
const OS_KEY = 'cp-os';
const WINSHELL_KEY = 'cp-winshell';

export function detectOs(): Os {
  try {
    const uaData = (navigator as unknown as { userAgentData?: { platform?: string } }).userAgentData;
    const p = (uaData?.platform || navigator.platform || navigator.userAgent || '').toLowerCase();
    if (/win/.test(p)) return 'windows';
    if (/mac|iphone|ipad|ipod/.test(p)) return 'mac';
    return 'linux';
  } catch { return 'linux'; }
}
function storedOs(): Os {
  try { const v = localStorage.getItem(OS_KEY); if (v === 'mac' || v === 'linux' || v === 'windows') return v; } catch { /* ignore */ }
  return detectOs();
}
function storedWinShell(): WinShell {
  try { const v = localStorage.getItem(WINSHELL_KEY); if (v === 'powershell' || v === 'cmd') return v; } catch { /* ignore */ }
  return 'powershell';
}

// The install scripts live on the service (/api/install.*) at the same origin as the gateway base.
const scriptOrigin = (base: string) => base.replace(/\/gateway\/?$/, '');
const unixQuick = (base: string, t: string) =>
  `curl -fsSL ${scriptOrigin(base)}/api/install.sh | bash -s -- ${t}`;
const psQuick = (base: string, t: string) =>
  `& ([scriptblock]::Create((irm ${scriptOrigin(base)}/api/install.ps1))) -Token ${t}`;
const unixEnvRun = (base: string, t: string) =>
  `export ANTHROPIC_BASE_URL=${base}\nexport ANTHROPIC_AUTH_TOKEN=${t}\nclaude`;
const unixWrapper = (base: string, t: string) =>
  `mkdir -p ~/.local/bin && cat > ~/.local/bin/claude-proxy <<'EOF'\n` +
  `#!/usr/bin/env bash\n` +
  `ANTHROPIC_BASE_URL="${base}" ANTHROPIC_AUTH_TOKEN="${t}" exec claude "$@"\n` +
  `EOF\n` +
  `chmod +x ~/.local/bin/claude-proxy\n` +
  // macOS (and some distros) don't ship ~/.local/bin on PATH — append it to the shell rc once
  `case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) rc=~/."$(basename "\${SHELL:-zsh}")"rc; ` +
  `echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$rc"; export PATH="$HOME/.local/bin:$PATH"; ` +
  `echo "Added ~/.local/bin to PATH in $rc";; esac\n` +
  `echo 'Installed. Run: claude-proxy [claude args]'`;
const psEnvRun = (base: string, t: string) =>
  `$env:ANTHROPIC_BASE_URL="${base}"\n$env:ANTHROPIC_AUTH_TOKEN="${t}"\nclaude`;
const psWrapper = (base: string, t: string) =>
  `if (!(Test-Path $PROFILE)) { New-Item -ItemType File -Path $PROFILE -Force | Out-Null }\n` +
  `Add-Content $PROFILE 'function claude-proxy { $env:ANTHROPIC_BASE_URL="${base}"; $env:ANTHROPIC_AUTH_TOKEN="${t}"; claude @args }'\n` +
  `. $PROFILE; Write-Host 'Installed. Run: claude-proxy [claude args]'`;
const cmdEnvRun = (base: string, t: string) =>
  `set ANTHROPIC_BASE_URL=${base}\nset ANTHROPIC_AUTH_TOKEN=${t}\nclaude`;

export function ConnectScripts({ base, token, wrapper = false }: { base: string; token: string; wrapper?: boolean }) {
  const [os, setOs] = useState<Os>(storedOs);
  const [win, setWin] = useState<WinShell>(storedWinShell);
  const pickOs = (v: Os) => { setOs(v); try { localStorage.setItem(OS_KEY, v); } catch { /* ignore */ } };
  const pickWin = (v: WinShell) => { setWin(v); try { localStorage.setItem(WINSHELL_KEY, v); } catch { /* ignore */ } };

  let envRun: string;
  let wrap: string | null = null;
  let quick: string | null = null;
  let winCmdNote = false;
  if (os === 'windows') {
    if (win === 'powershell') { envRun = psEnvRun(base, token); if (wrapper) { wrap = psWrapper(base, token); quick = psQuick(base, token); } }
    else { envRun = cmdEnvRun(base, token); winCmdNote = wrapper; }
  } else {
    envRun = unixEnvRun(base, token);
    if (wrapper) { wrap = unixWrapper(base, token); quick = unixQuick(base, token); }
  }

  return (
    <div className="connect">
      <div className="connect-tabs">
        <Segmented<Os> className="ostabs" value={os} onChange={pickOs} options={[
          { value: 'windows', label: <><OsIcon name="windows" /><span>Windows</span></> },
          { value: 'mac', label: <><OsIcon name="mac" /><span>macOS</span></> },
          { value: 'linux', label: <><OsIcon name="linux" /><span>Linux</span></> },
        ]} />
        {os === 'windows' && (
          <Segmented<WinShell> className="winshell" value={win} onChange={pickWin} options={[
            { value: 'powershell', label: 'PowerShell' },
            { value: 'cmd', label: 'CMD' },
          ]} />
        )}
      </div>

      {quick && (
        <>
          <p className="hint" style={{ marginBottom: 4 }}>Quick install — one line, sets up the persistent <span className="mono">claude-proxy</span> command:</p>
          <CodeBlock text={quick} />
        </>
      )}

      <p className="hint" style={{ marginBottom: 4 }}>Set the variables and run:</p>
      <CodeBlock text={envRun} />

      {wrap && (
        <>
          <p className="hint" style={{ marginBottom: 4 }}>Or install a persistent <span className="mono">claude-proxy</span> command (paste once, then run <span className="mono">claude-proxy [args]</span>):</p>
          <CodeBlock text={wrap} />
        </>
      )}
      {winCmdNote && (
        <p className="hint" style={{ marginTop: 8 }}>The persistent <span className="mono">claude-proxy</span> command is available on the PowerShell tab.</p>
      )}
    </div>
  );
}

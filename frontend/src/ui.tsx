import { ReactNode, useEffect, useState } from 'react';

/* ---------------------------------------------------------------- icons
   Small inline SVG set (Lucide-style, currentColor). No icon dependency. */
const ICON_PATHS: Record<string, ReactNode> = {
  dashboard: (<><rect x="3" y="3" width="7" height="7" rx="1.5" /><rect x="14" y="3" width="7" height="7" rx="1.5" /><rect x="14" y="14" width="7" height="7" rx="1.5" /><rect x="3" y="14" width="7" height="7" rx="1.5" /></>),
  accounts: (<><rect x="3" y="4" width="18" height="7" rx="2" /><rect x="3" y="13" width="18" height="7" rx="2" /><path d="M6.5 7.5h.01M6.5 16.5h.01" /></>),
  mystats: (<><circle cx="12" cy="8" r="4" /><path d="M4 20c0-4 4-6 8-6s8 2 8 6" /></>),
  stats: (<><path d="M4 20V10" /><path d="M10 20V4" /><path d="M16 20v-7" /><path d="M3 20h18" /></>),
  tokens: (<><circle cx="8" cy="15" r="4" /><path d="M10.8 12.2 20 3" /><path d="m17 6 2 2" /><path d="m14.5 8.5 2 2" /></>),
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
  sparkle: (<path d="M12 3l1.9 5.6a2 2 0 0 0 1.3 1.3L21 12l-5.8 1.9a2 2 0 0 0-1.3 1.3L12 21l-1.9-5.8a2 2 0 0 0-1.3-1.3L3 12l5.8-1.9a2 2 0 0 0 1.3-1.3z" />),
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

/* ---------------------------------------------------------------- segmented */
export function Segmented<T extends string | number>({ value, options, onChange, className = '' }: {
  value: T; options: { value: T; label: ReactNode }[]; onChange: (v: T) => void; className?: string;
}) {
  return (
    <div className={`segmented ${className}`} role="group">
      {options.map((o) => (
        <button key={String(o.value)} type="button"
          className={'seg' + (o.value === value ? ' active' : '')}
          aria-pressed={o.value === value}
          onClick={() => onChange(o.value)}>{o.label}</button>
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

export function Modal({ title, onClose, children, footer, width }: {
  title: string; onClose: () => void; children: ReactNode; footer?: ReactNode; width?: number;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="overlay" onMouseDown={onClose}>
      <div className="modal" style={width ? { width } : undefined} onMouseDown={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <h3>{title}</h3>
          <button className="x" onClick={onClose} aria-label="Close">×</button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
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

// Shared chart helpers for the Statistics + My Stats pages: token-kind palette, series
// builders, UTC date math and the interactive legend chip row.
import { ReactNode } from 'react';
import { Series } from '../Chart';
import { TokenKindSeries } from '../api';

export const W5H = '#5a7fb0';
export const WWK = '#c96442';

export const TOKEN_KINDS: { key: keyof TokenKindSeries; label: string; color: string }[] = [
  { key: 'input', label: 'Input', color: '#c96442' },
  { key: 'output', label: 'Output', color: '#5a7fb0' },
  { key: 'cacheRead', label: 'Cache read', color: '#4f9d69' },
  { key: 'cacheWrite', label: 'Cache write', color: '#c08a2e' },
];

export function todayUtc(): string { return new Date().toISOString().slice(0, 10); }

export function shiftDate(d: string, days: number): string {
  const dt = new Date(d + 'T00:00:00Z'); dt.setUTCDate(dt.getUTCDate() + days); return dt.toISOString().slice(0, 10);
}

export function sumKinds(s: TokenKindSeries): number {
  return (['input', 'output', 'cacheRead', 'cacheWrite'] as const)
    .reduce((t, k) => t + (s[k] || []).reduce((a, b) => a + b, 0), 0);
}

export function tokenSeries(src: TokenKindSeries, hidden: Set<string>): Series[] {
  return TOKEN_KINDS.filter((k) => !hidden.has(k.key)).map((k) => ({ name: k.label, color: k.color, values: src[k.key] || [] }));
}

/** Legend chips; interactive (toggles series) when `onToggle` is supplied. */
export function Legend({ items, hidden, onToggle }: {
  items: { key: string; label: string; color: string }[];
  hidden?: Set<string>; onToggle?: (k: string) => void;
}) {
  return (
    <div className="legend">
      {items.map((it) => {
        const off = !!hidden?.has(it.key);
        const inner: ReactNode = (<><span className="sw" style={{ background: it.color }} />{it.label}</>);
        return onToggle
          ? <button key={it.key} type="button" className={'lg' + (off ? ' off' : '')} onClick={() => onToggle(it.key)}>{inner}</button>
          : <span key={it.key} className="lg">{inner}</span>;
      })}
    </div>
  );
}

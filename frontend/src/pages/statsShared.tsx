// Shared chart helpers for the Statistics + My Stats pages: token-kind palette, series
// builders, UTC date math and the interactive legend chip row.
import { ReactNode, useState } from 'react';
import { ChartMode, Series } from '../Chart';
import { TokenKindSeries } from '../api';
import { Icon, NumberInput, Segmented } from '../ui';

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

/** Shared time-series control bar for the Statistics + My Stats pages: period presets
    (7/30/90 + a custom day count), the bars/area chart-mode toggle, and prev/next date
    navigation. Drives every chart on the page at once. `maxDays` mirrors the backend cap. */
export function RangeControls({ days, onDays, endDate, onEndDate, mode, onMode, maxDays = 90 }: {
  days: number; onDays: (d: number) => void;
  endDate: string; onEndDate: (d: string) => void;
  mode: ChartMode; onMode: (m: ChartMode) => void;
  maxDays?: number;
}) {
  const PRESETS = [7, 30, 90];
  const [showCustom, setShowCustom] = useState(!PRESETS.includes(days));
  const atToday = endDate >= todayUtc();
  const rangeStart = shiftDate(endDate, -(days - 1));
  return (
    <div className="controlbar">
      <div className="segmented" role="group">
        {PRESETS.map((p) => (
          <button key={p} type="button" aria-pressed={!showCustom && days === p}
            className={'seg' + (!showCustom && days === p ? ' active' : '')}
            onClick={() => { setShowCustom(false); onDays(p); }}>{p}d</button>
        ))}
        <button type="button" aria-pressed={showCustom}
          className={'seg' + (showCustom ? ' active' : '')}
          onClick={() => setShowCustom(true)}>Custom</button>
      </div>
      {showCustom && (
        <span className="custom-days">
          <NumberInput value={days} min={1} max={maxDays} onChange={onDays} className="days-field" />
          <span className="hint">days</span>
        </span>
      )}
      <Segmented<ChartMode> value={mode} onChange={onMode} options={[
        { value: 'bars', label: <><Icon name="bars" size={15} /><span>Bars</span></> },
        { value: 'area', label: <><Icon name="area" size={15} /><span>Area</span></> },
      ]} />
      <div className="daterange">
        <button className="ghost sm" onClick={() => onEndDate(shiftDate(endDate, -days))}>← prev</button>
        <span className="hint mono">{rangeStart} … {endDate}</span>
        <button className="ghost sm" disabled={atToday} onClick={() => onEndDate(shiftDate(endDate, days))}>next →</button>
      </div>
    </div>
  );
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

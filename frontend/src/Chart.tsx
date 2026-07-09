import { useState } from 'react';

export const SERIES_COLORS = [
  '#d97757', '#6ea8fe', '#4ade80', '#fbbf24', '#c084fc', '#22d3ee', '#f472b6', '#a3e635',
];

export interface Series { name: string; color: string; values: number[]; }

/** Stacked daily bar chart. `days` are date strings; each series has one value per day. */
export function StackedBarChart({ days, series, height = 200, fmt }: {
  days: string[]; series: Series[]; height?: number; fmt: (n: number) => string;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const W = 720, H = height;
  const padL = 52, padR = 12, padT = 12, padB = 26;
  const plotW = W - padL - padR, plotH = H - padT - padB;

  const totals = days.map((_, i) => series.reduce((s, se) => s + (se.values[i] || 0), 0));
  const max = Math.max(0.0000001, ...totals);
  // nice-ish upper bound
  const niceMax = niceCeil(max);
  const bandW = plotW / Math.max(1, days.length);
  const barW = Math.min(38, bandW * 0.62);
  const x = (i: number) => padL + i * bandW + (bandW - barW) / 2;
  const y = (v: number) => padT + plotH - (v / niceMax) * plotH;

  const gridLines = 4;
  const everyN = Math.ceil(days.length / 8);

  return (
    <div style={{ position: 'relative', width: '100%', overflowX: 'auto' }}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" style={{ display: 'block', minWidth: 420 }}
        onMouseLeave={() => setHover(null)}>
        {/* y grid + labels */}
        {Array.from({ length: gridLines + 1 }).map((_, g) => {
          const v = (niceMax / gridLines) * g;
          const yy = y(v);
          return (
            <g key={g}>
              <line x1={padL} x2={W - padR} y1={yy} y2={yy} stroke="var(--border)" strokeWidth={1} />
              <text x={padL - 6} y={yy + 3} textAnchor="end" fontSize={10} fill="var(--faint)">{fmt(v)}</text>
            </g>
          );
        })}
        {/* bars */}
        {days.map((d, i) => {
          let acc = 0;
          return (
            <g key={i} onMouseEnter={() => setHover(i)}>
              <rect x={padL + i * bandW} y={padT} width={bandW} height={plotH} fill={hover === i ? 'rgba(255,255,255,.04)' : 'transparent'} />
              {series.map((se, si) => {
                const v = se.values[i] || 0;
                if (v <= 0) return null;
                const h = (v / niceMax) * plotH;
                const yy = padT + plotH - acc - h;
                acc += h;
                return <rect key={si} x={x(i)} y={yy} width={barW} height={Math.max(0, h)} fill={se.color} rx={1.5} />;
              })}
              {(i % everyN === 0) && <text x={padL + i * bandW + bandW / 2} y={H - 8} textAnchor="middle" fontSize={10} fill="var(--faint)">{d.slice(5)}</text>}
            </g>
          );
        })}
      </svg>
      {hover != null && (
        <div style={{ position: 'absolute', top: 4, right: 12, background: 'var(--panel-2)', border: '1px solid var(--border-2)', borderRadius: 8, padding: '8px 10px', fontSize: 12, pointerEvents: 'none', minWidth: 130 }}>
          <div style={{ color: 'var(--muted)', marginBottom: 4 }}>{days[hover]}</div>
          <div style={{ fontWeight: 700, marginBottom: 4 }}>{fmt(totals[hover])}</div>
          {series.filter((s) => (s.values[hover] || 0) > 0).slice(0, 8).map((s, i) => (
            <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <span style={{ width: 8, height: 8, borderRadius: 2, background: s.color, display: 'inline-block' }} />
              <span style={{ flex: 1, color: 'var(--muted)' }}>{s.name}</span>
              <span>{fmt(s.values[hover] || 0)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function niceCeil(v: number): number {
  if (v <= 0) return 1;
  const pow = Math.pow(10, Math.floor(Math.log10(v)));
  const f = v / pow;
  const nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
  return nice * pow;
}

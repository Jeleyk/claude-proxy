import { useEffect, useRef, useState } from 'react';

// Editorial categorical palette — mid-tone, legible on both cream and warm-dark.
export const SERIES_COLORS = [
  '#c96442', '#5a7fb0', '#4f9d69', '#c08a2e', '#8b6db0', '#3f9aa0', '#c56b8a', '#8f9350',
];

/** Track the container's pixel width so the SVG viewBox renders 1:1 at any size
    (crisp text, no inner horizontal scroll) — key for 3-up + mobile layouts. */
function useMeasuredWidth(fallback = 560, min = 220) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [w, setW] = useState(fallback);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver((entries) => {
      const cw = entries[0]?.contentRect.width ?? 0;
      if (cw > 0) setW(Math.max(min, Math.round(cw)));
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return { ref, w };
}

export interface Series { name: string; color: string; values: number[]; }

/** Stacked daily bar chart. `days` are date strings; each series has one value per day. */
export function StackedBarChart({ days, series, height = 200, fmt }: {
  days: string[]; series: Series[]; height?: number; fmt: (n: number) => string;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const { ref, w } = useMeasuredWidth();
  const W = w, H = height;
  const padL = 48, padR = 10, padT = 12, padB = 26;
  const plotW = W - padL - padR, plotH = H - padT - padB;

  const totals = days.map((_, i) => series.reduce((s, se) => s + (se.values[i] || 0), 0));
  const max = Math.max(0.0000001, ...totals);
  const niceMax = niceCeil(max);
  const bandW = plotW / Math.max(1, days.length);
  const barW = Math.min(38, bandW * 0.64);
  const x = (i: number) => padL + i * bandW + (bandW - barW) / 2;
  const y = (v: number) => padT + plotH - (v / niceMax) * plotH;

  const gridLines = 4;
  const everyN = Math.max(1, Math.ceil(days.length / Math.max(4, Math.floor(plotW / 46))));

  return (
    <div ref={ref} style={{ position: 'relative', width: '100%' }}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" style={{ display: 'block' }}
        onMouseLeave={() => setHover(null)}>
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
        {days.map((d, i) => {
          let acc = 0;
          return (
            <g key={i} onMouseEnter={() => setHover(i)}>
              <rect x={padL + i * bandW} y={padT} width={bandW} height={plotH} fill={hover === i ? 'var(--accent-soft)' : 'transparent'} />
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
        <div style={{ position: 'absolute', top: 4, right: 10, background: 'var(--panel-2)', border: '1px solid var(--border-2)', borderRadius: 8, padding: '8px 10px', fontSize: 12, pointerEvents: 'none', minWidth: 130, boxShadow: 'var(--shadow)' }}>
          <div style={{ color: 'var(--muted)', marginBottom: 4 }}>{days[hover]}</div>
          <div style={{ fontWeight: 700, marginBottom: 4 }}>{fmt(totals[hover])}</div>
          {series.filter((s) => (s.values[hover] || 0) > 0).slice(0, 8).map((s, i) => (
            <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <span style={{ width: 8, height: 8, borderRadius: 2, background: s.color, display: 'inline-block' }} />
              <span style={{ flex: 1, color: 'var(--muted)' }}>{s.name}</span>
              <span style={{ fontVariantNumeric: 'tabular-nums' }}>{fmt(s.values[hover] || 0)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export interface Line { name: string; color: string; values: (number | null)[]; }

/** Multi-line chart over evenly-spaced buckets. Values are 0..1 fractions shown as %. */
export function LineChart({ labels, lines, height = 170 }: { labels: string[]; lines: Line[]; height?: number }) {
  const [hover, setHover] = useState<number | null>(null);
  const { ref, w } = useMeasuredWidth();
  const W = w, H = height;
  const padL = 40, padR = 10, padT = 10, padB = 22;
  const plotW = W - padL - padR, plotH = H - padT - padB;
  const n = labels.length;
  const x = (i: number) => padL + (n <= 1 ? plotW / 2 : (i / (n - 1)) * plotW);
  const y = (v: number) => padT + plotH - Math.max(0, Math.min(1, v)) * plotH;
  const everyN = Math.max(1, Math.ceil(n / Math.max(4, Math.floor(plotW / 46))));

  function path(vals: (number | null)[]): string {
    let d = ''; let pen = false;
    vals.forEach((v, i) => {
      if (v == null) { pen = false; return; }
      d += `${pen ? 'L' : 'M'}${x(i).toFixed(1)} ${y(v).toFixed(1)} `; pen = true;
    });
    return d;
  }

  return (
    <div ref={ref} style={{ position: 'relative', width: '100%' }}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" style={{ display: 'block' }}
        onMouseMove={(e) => {
          const r = (e.currentTarget as SVGElement).getBoundingClientRect();
          const px = ((e.clientX - r.left) / r.width) * W;
          const i = Math.round(((px - padL) / plotW) * (n - 1));
          setHover(i >= 0 && i < n ? i : null);
        }}
        onMouseLeave={() => setHover(null)}>
        {[0, 0.25, 0.5, 0.75, 1].map((f, g) => (
          <g key={g}>
            <line x1={padL} x2={W - padR} y1={y(f)} y2={y(f)} stroke="var(--border)" strokeWidth={1} />
            <text x={padL - 6} y={y(f) + 3} textAnchor="end" fontSize={10} fill="var(--faint)">{Math.round(f * 100)}%</text>
          </g>
        ))}
        {hover != null && <line x1={x(hover)} x2={x(hover)} y1={padT} y2={padT + plotH} stroke="var(--border-2)" strokeWidth={1} />}
        {lines.map((l, li) => (
          <path key={li} d={path(l.values)} fill="none" stroke={l.color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
        ))}
        {labels.map((lb, i) => (i % everyN === 0) && (
          <text key={i} x={x(i)} y={H - 6} textAnchor="middle" fontSize={9} fill="var(--faint)">{lb.slice(0, 5)}</text>
        ))}
      </svg>
      {hover != null && (
        <div style={{ position: 'absolute', top: 4, right: 10, background: 'var(--panel-2)', border: '1px solid var(--border-2)', borderRadius: 8, padding: '8px 10px', fontSize: 12, pointerEvents: 'none', minWidth: 120, boxShadow: 'var(--shadow)' }}>
          <div style={{ color: 'var(--muted)', marginBottom: 4 }}>{labels[hover]}</div>
          {lines.map((l, i) => (
            <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <span style={{ width: 8, height: 8, borderRadius: 2, background: l.color, display: 'inline-block' }} />
              <span style={{ flex: 1, color: 'var(--muted)' }}>{l.name}</span>
              <span>{l.values[hover] == null ? '—' : `${Math.round((l.values[hover] as number) * 100)}%`}</span>
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

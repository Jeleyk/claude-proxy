// Loading placeholders shaped like the content they stand in for — cards, chart panels, table
// rows. Styling (the shimmer sweep, the reduced-motion opt-out) lives in styles.css under .sk*.
//
// These render only on the FIRST load of a view. Every stats page polls on an interval, and
// swapping live numbers back to grey bars every few seconds would be worse than a stale number.

/** Deterministic pseudo-random column heights: same skeleton renders identically each time. */
function colHeight(i: number, seed: number): string {
  const h = Math.abs(Math.sin((i + 1) * (seed + 1.7)) * 100);
  return `${28 + (h % 62)}%`;
}

export function SkeletonLine({ width = '100%', height, className = '' }: {
  width?: number | string; height?: number; className?: string;
}) {
  return <div className={`sk ${className}`.trim()} style={{ width, height }} />;
}

/** Headline metric cards — mirrors the `.cards` grid on the stats/pool views. */
export function SkeletonCards({ n = 4 }: { n?: number }) {
  return (
    <div className="cards">
      {Array.from({ length: n }).map((_, i) => (
        <div className="card" key={i}>
          <SkeletonLine className="sk-text" width={72} />
          <SkeletonLine className="sk-title" width={104} height={20} />
          <SkeletonLine className="sk-text" width={86} height={9} />
        </div>
      ))}
    </div>
  );
}

/** One chart panel: heading, y-axis ticks, columns, legend chips. */
export function SkeletonChart({ height = 190, seed = 0, legend = 3 }: {
  height?: number; seed?: number; legend?: number;
}) {
  return (
    <div className="panel">
      <div className="chart-card-head">
        <SkeletonLine className="sk-title" width={118} />
        <SkeletonLine className="sk-text" width={54} />
      </div>
      <div className="sk-chart" style={{ height }}>
        <div className="sk-axis">
          {Array.from({ length: 5 }).map((_, i) => <SkeletonLine key={i} width={i === 0 ? 18 : 24} />)}
        </div>
        {Array.from({ length: 12 }).map((_, i) => (
          <div className="sk sk-col" key={i} style={{ height: colHeight(i, seed) }} />
        ))}
      </div>
      {legend > 0 && (
        <div className="sk-legend">
          {Array.from({ length: legend }).map((_, i) => <SkeletonLine key={i} width={54 + (i % 3) * 14} />)}
        </div>
      )}
    </div>
  );
}

/** A row of chart panels, matching the `.chart-row` grid. */
export function SkeletonChartRow({ n = 3, height = 190, legend = 3 }: {
  n?: number; height?: number; legend?: number;
}) {
  return (
    <div className="chart-row">
      {Array.from({ length: n }).map((_, i) => (
        <SkeletonChart key={i} height={height} seed={i} legend={legend} />
      ))}
    </div>
  );
}

/** Table body placeholder — sits inside a real `.tablewrap` so borders line up with the real table. */
export function SkeletonTable({ rows = 5, cols = 4 }: { rows?: number; cols?: number }) {
  return (
    <div className="tablewrap">
      {Array.from({ length: rows }).map((_, r) => (
        <div className="sk-row" key={r}>
          {Array.from({ length: cols }).map((_, c) => (
            <SkeletonLine key={c} width={c === 0 ? '22%' : `${10 + ((r + c) % 3) * 4}%`} />
          ))}
        </div>
      ))}
    </div>
  );
}

/** The stats control bar (period presets, chart mode, date nav). */
export function SkeletonControls() {
  return (
    <div className="controlbar">
      <SkeletonLine width={168} height={30} />
      <SkeletonLine width={132} height={30} />
      <SkeletonLine width={210} height={26} />
    </div>
  );
}

/** Full stats page body: cards, controls, one chart row, a table. */
export function SkeletonStatsPage({ cards = 4 }: { cards?: number }) {
  return (
    <>
      <SkeletonCards n={cards} />
      <SkeletonControls />
      <SkeletonChartRow />
      <SkeletonTable rows={5} cols={4} />
    </>
  );
}

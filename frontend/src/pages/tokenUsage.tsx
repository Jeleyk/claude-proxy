// Per-inbound-token usage: the "Your tokens" table with all-time token/cost totals, plus
// daily cost + tokens charts stacked per token. Shared by the Proxy Tokens (source="proxy")
// and API Routing (source="routing") pages, and reused by the My Stats / User Stats views.
import { useEffect, useMemo, useState } from 'react';
import { api, fmtTokens, fmtUsd, ProxyTokenDto, TokenUsage, TokenUsageSeries } from '../api';
import { ChartMode, SERIES_COLORS, StackedChart } from '../Chart';
import { Legend, RangeControls, todayUtc } from './statsShared';

export function seriesLabel(s: TokenUsageSeries): string {
  if (s.tokenId == null) return 'unattributed';
  return s.name ?? `deleted #${s.tokenId}`;
}

/** Tokens with any usage, colored consistently across the table + both charts. */
export function usageItems(data: TokenUsage | null) {
  return (data?.perToken ?? [])
    .filter((s) => s.totalRequests > 0 || s.cost.some((v) => v > 0) || s.tokens.some((v) => v > 0))
    .map((s, i) => ({ key: String(s.tokenId ?? 'none'), label: seriesLabel(s), color: SERIES_COLORS[i % SERIES_COLORS.length], s }));
}

/** The Spend/Tokens per-day stacked chart pair for one datapath's per-token series. */
export function TokenUsageChartsRow({ data, mode }: { data: TokenUsage; mode: ChartMode }) {
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const items = useMemo(() => usageItems(data), [data]);
  const visible = items.filter((it) => !hidden.has(it.key));
  const costTotal = visible.reduce((t, it) => t + it.s.cost.reduce((a, b) => a + b, 0), 0);
  const tokTotal = visible.reduce((t, it) => t + it.s.tokens.reduce((a, b) => a + b, 0), 0);

  function toggle(k: string) {
    setHidden((h) => { const n = new Set(h); if (n.has(k)) n.delete(k); else n.add(k); return n; });
  }

  return (
    <div className="chart-row">
      <div className="panel">
        <div className="chart-card-head"><span className="t">Spend per day</span><span className="v">{fmtUsd(costTotal)}</span></div>
        <StackedChart days={data.days} height={190} fmt={fmtUsd} mode={mode}
          series={visible.map((it) => ({ name: it.label, color: it.color, values: it.s.cost }))} />
        <Legend items={items} hidden={hidden} onToggle={toggle} />
      </div>
      <div className="panel">
        <div className="chart-card-head"><span className="t">Tokens per day</span><span className="v">{fmtTokens(tokTotal)}</span></div>
        <StackedChart days={data.days} height={190} fmt={fmtTokens} mode={mode}
          series={visible.map((it) => ({ name: it.label, color: it.color, values: it.s.tokens }))} />
        <Legend items={items} hidden={hidden} onToggle={toggle} />
      </div>
    </div>
  );
}

export function TokenUsageSection({ source, tokens, onDelete, emptyHint }: {
  source: 'proxy' | 'routing';
  tokens: ProxyTokenDto[];
  onDelete: (id: number) => void;
  emptyHint: string;
}) {
  const [data, setData] = useState<TokenUsage | null>(null);
  const [days, setDays] = useState(7);
  const [endDate, setEndDate] = useState(todayUtc());
  const [mode, setMode] = useState<ChartMode>('bars');

  useEffect(() => {
    let gone = false;
    api.tokenUsage(source, days, endDate).then((d) => { if (!gone) setData(d); }).catch(() => {});
    return () => { gone = true; };
    // `tokens` in deps: a token created/deleted on the page should refresh attribution.
  }, [source, days, endDate, tokens.length]);

  // All-time totals per existing token, for the table columns.
  const totals = useMemo(() => {
    const m = new Map<number, TokenUsageSeries>();
    data?.perToken.forEach((s) => { if (s.tokenId != null) m.set(s.tokenId, s); });
    return m;
  }, [data]);

  const hasUsage = usageItems(data).length > 0;

  return (
    <>
      <h2>Your tokens</h2>
      <div className="tablewrap">
        <table>
          <thead><tr><th>Name</th><th>Created</th><th>Last used</th><th>Tokens</th><th>Cost</th><th></th></tr></thead>
          <tbody>
            {tokens.map((t) => {
              const u = totals.get(t.id);
              return (
                <tr key={t.id}>
                  <td>{t.name}</td>
                  <td className="hint">{new Date(t.createdAt).toLocaleString()}</td>
                  <td className="hint">{t.lastUsedAt ? new Date(t.lastUsedAt).toLocaleString() : 'never'}</td>
                  <td>{u ? fmtTokens(u.totalTokens) : '—'}</td>
                  <td>{u ? fmtUsd(u.totalCost) : '—'}</td>
                  <td><button className="sm danger" onClick={() => onDelete(t.id)}>Delete</button></td>
                </tr>
              );
            })}
            {tokens.length === 0 && <tr><td colSpan={6} className="hint">{emptyHint}</td></tr>}
          </tbody>
        </table>
      </div>

      {hasUsage && data && (
        <>
          <h2>Usage by token</h2>
          <RangeControls days={days} onDays={setDays} endDate={endDate} onEndDate={setEndDate} mode={mode} onMode={setMode} />
          <TokenUsageChartsRow data={data} mode={mode} />
        </>
      )}
    </>
  );
}

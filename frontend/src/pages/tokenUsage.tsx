// Per-inbound-token usage: the "Your tokens" table with all-time token/cost totals, plus
// daily cost + tokens charts stacked per token. Shared by the Proxy Tokens (source="proxy")
// and API Routing (source="routing") pages, and reused by the My Stats / User Stats views.
import { useEffect, useMemo, useState } from 'react';
import { api, fmtTokens, fmtUsd, ProxyTokenDto, TokenUsage, TokenUsageSeries } from '../api';
import { ChartMode, SERIES_COLORS, StackedChart } from '../Chart';
import { Legend, RangeControls, todayLocal } from './statsShared';

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

/** The Spend/Tokens/Requests per-day stacked charts for one datapath's per-token series. */
export function TokenUsageChartsRow({ data, mode }: { data: TokenUsage; mode: ChartMode }) {
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const items = useMemo(() => usageItems(data), [data]);
  const visible = items.filter((it) => !hidden.has(it.key));
  const sum = (pick: (s: TokenUsageSeries) => number[]) =>
    visible.reduce((t, it) => t + pick(it.s).reduce((a, b) => a + b, 0), 0);

  function toggle(k: string) {
    setHidden((h) => { const n = new Set(h); if (n.has(k)) n.delete(k); else n.add(k); return n; });
  }

  const charts: { title: string; pick: (s: TokenUsageSeries) => number[]; fmt: (n: number) => string }[] = [
    { title: 'Spend per day', pick: (s) => s.cost, fmt: fmtUsd },
    { title: 'Tokens per day', pick: (s) => s.tokens, fmt: fmtTokens },
    { title: 'Requests per day', pick: (s) => s.requests, fmt: (n) => n.toLocaleString() },
  ];

  return (
    <div className="chart-row">
      {charts.map((c) => (
        <div className="panel" key={c.title}>
          <div className="chart-card-head"><span className="t">{c.title}</span><span className="v">{c.fmt(sum(c.pick))}</span></div>
          <StackedChart days={data.days} height={190} fmt={c.fmt} mode={mode}
            series={visible.map((it) => ({ name: it.label, color: it.color, values: c.pick(it.s) }))} />
          <Legend items={items} hidden={hidden} onToggle={toggle} />
        </div>
      ))}
    </div>
  );
}

export function TokenUsageSection({ source, tokens, onDelete, onToggle, onEdit, emptyHint }: {
  source: 'proxy' | 'routing';
  tokens: ProxyTokenDto[];
  onDelete: (id: number) => void;
  // Disable/enable the token: reversible, so no confirmation — unlike delete.
  onToggle: (id: number, enabled: boolean) => void;
  // When supplied, the row offers "Edit" (a settings dialog) instead of an inline enable toggle —
  // tokens with more than one setting get a dialog.
  onEdit?: (t: ProxyTokenDto) => void;
  emptyHint: string;
}) {
  const [data, setData] = useState<TokenUsage | null>(null);
  const [days, setDays] = useState(7);
  const [endDate, setEndDate] = useState(todayLocal());
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
                  <td>
                    <span className={t.enabled ? undefined : 'hint'}>{t.name}</span>
                    {!t.enabled && <span className="badge muted" style={{ marginLeft: 8 }}>disabled</span>}
                    {t.systemPrompt && <span className="badge accent" style={{ marginLeft: 8 }} title={t.systemPrompt}>prompt</span>}
                    {t.defaultModel && <span className="badge accent mono" style={{ marginLeft: 8 }} title="Every request of this token runs on this model">{t.defaultModel}</span>}
                  </td>
                  <td className="hint">{new Date(t.createdAt).toLocaleString()}</td>
                  <td className="hint">{t.lastUsedAt ? new Date(t.lastUsedAt).toLocaleString() : 'never'}</td>
                  <td>{u ? fmtTokens(u.totalTokens) : '—'}</td>
                  <td>{u ? fmtUsd(u.totalCost) : '—'}</td>
                  <td>
                    <div className="row" style={{ gap: 6, justifyContent: 'flex-end' }}>
                      {onEdit
                        ? <button className="sm ghost" onClick={() => onEdit(t)}>Edit</button>
                        : (
                          <button className="sm ghost" onClick={() => onToggle(t.id, !t.enabled)}>
                            {t.enabled ? 'Disable' : 'Enable'}
                          </button>
                        )}
                      <button className="sm danger" onClick={() => onDelete(t.id)}>Delete</button>
                    </div>
                  </td>
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

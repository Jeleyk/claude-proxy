import { ReactNode, useEffect, useMemo, useState } from 'react';
import {
  api, DailyStats, fmtTokens, fmtUsd, has, TokenKindSeries, TokenStats,
  UsageEvent, UsageSummary, UserDto, WindowStats,
} from '../api';
import { LineChart, Series, SERIES_COLORS, StackedBarChart } from '../Chart';
import { Segmented, Select } from '../ui';

const W5H = '#5a7fb0', WWK = '#c96442';
const TOKEN_KINDS: { key: keyof TokenKindSeries; label: string; color: string }[] = [
  { key: 'input', label: 'Input', color: '#c96442' },
  { key: 'output', label: 'Output', color: '#5a7fb0' },
  { key: 'cacheRead', label: 'Cache read', color: '#4f9d69' },
  { key: 'cacheWrite', label: 'Cache write', color: '#c08a2e' },
];

function todayUtc(): string { return new Date().toISOString().slice(0, 10); }
function shiftDate(d: string, days: number): string {
  const dt = new Date(d + 'T00:00:00Z'); dt.setUTCDate(dt.getUTCDate() + days); return dt.toISOString().slice(0, 10);
}
function sumKinds(s: TokenKindSeries): number {
  return (['input', 'output', 'cacheRead', 'cacheWrite'] as const)
    .reduce((t, k) => t + (s[k] || []).reduce((a, b) => a + b, 0), 0);
}
function tokenSeries(src: TokenKindSeries, hidden: Set<string>): Series[] {
  return TOKEN_KINDS.filter((k) => !hidden.has(k.key)).map((k) => ({ name: k.label, color: k.color, values: src[k.key] || [] }));
}

/** Legend chips; interactive (toggles series) when `onToggle` is supplied. */
function Legend({ items, hidden, onToggle }: {
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

export function Stats({ user }: { user: UserDto }) {
  const canStats = has(user, 'STATS_VIEW');
  const canRecent = has(user, 'STATS_VIEW_RECENT') || canStats;
  const canAccounts = has(user, 'STATS_VIEW_ACCOUNTS') || canStats;

  const [daily, setDaily] = useState<DailyStats | null>(null);
  const [windows, setWindows] = useState<WindowStats | null>(null);
  const [tokens, setTokens] = useState<TokenStats | null>(null);
  const [summary, setSummary] = useState<UsageSummary[]>([]);
  const [recent, setRecent] = useState<UsageEvent[]>([]);
  const [endDate, setEndDate] = useState(todayUtc());
  const [days, setDays] = useState(7);
  const [err, setErr] = useState<string | null>(null);

  const [modelFilter, setModelFilter] = useState('');
  const [hiddenKinds, setHiddenKinds] = useState<Set<string>>(new Set());
  const [selAcct, setSelAcct] = useState<number | null>(null);

  async function load() {
    try {
      if (canStats) {
        const [d, w, t, s] = await Promise.all([
          api.statsDaily(days, endDate), api.statsWindows(days, endDate),
          api.statsTokens(days, endDate), api.statsSummary(),
        ]);
        setDaily(d); setWindows(w); setTokens(t); setSummary(s);
      }
      if (canRecent) setRecent(await api.statsRecent());
      setErr(null);
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [endDate, days]);
  useEffect(() => { const t = setInterval(load, 10000); return () => clearInterval(t); /* eslint-disable-line */ }, [endDate, days]);

  // accounts present across any dataset, for the per-account picker
  const accountList = useMemo(() => {
    const m = new Map<number, string | null>();
    daily?.perAccount.forEach((a) => m.set(a.accountId, a.accountName));
    tokens?.perAccount.forEach((a) => m.set(a.accountId, a.accountName));
    windows?.perAccount.forEach((a) => m.set(a.accountId, a.accountName));
    return [...m.entries()].map(([id, name]) => ({ id, name: name ?? `#${id}` }));
  }, [daily, tokens, windows]);
  const accountKey = accountList.map((a) => a.id).join(',');
  useEffect(() => {
    if (!accountList.length) { setSelAcct(null); return; }
    setSelAcct((prev) => (prev != null && accountList.some((a) => a.id === prev)) ? prev : accountList[0].id);
    /* eslint-disable-next-line */
  }, [accountKey]);

  if (err && !daily) return <div className="main-inner"><div className="err">{err}</div></div>;

  const atToday = endDate >= todayUtc();
  const rangeStart = daily?.days[0] ?? shiftDate(endDate, -(days - 1));
  const dayLabels = daily?.days ?? [];
  const zerosD = dayLabels.map(() => 0);
  const emptyKinds: TokenKindSeries = { input: zerosD, output: zerosD, cacheRead: zerosD, cacheWrite: zerosD };

  // --- top row aggregates ---
  const spendByAccount = canAccounts && !!daily?.perAccount.length;
  const spendSeries: Series[] = daily
    ? (spendByAccount
        ? daily.perAccount.map((a, i) => ({ name: a.accountName ?? `#${a.accountId}`, color: SERIES_COLORS[i % SERIES_COLORS.length], values: a.cost }))
        : [{ name: 'Total', color: SERIES_COLORS[0], values: daily.totalCost }])
    : [];
  const spendLegend = spendByAccount
    ? daily!.perAccount.map((a, i) => ({ key: String(a.accountId), label: a.accountName ?? `#${a.accountId}`, color: SERIES_COLORS[i % SERIES_COLORS.length] }))
    : [];
  const weekTotal = daily ? daily.totalCost.reduce((s, v) => s + v, 0) : 0;

  const tokenTopSrc: TokenKindSeries = tokens
    ? (modelFilter === '' ? tokens.total : (tokens.perModel.find((m) => m.model === modelFilter) ?? tokens.total))
    : emptyKinds;
  const tokenTotal = sumKinds(tokenTopSrc);
  const modelOptions = [{ value: '', label: 'All models' }, ...(tokens?.models ?? []).map((m) => ({ value: m, label: m }))];

  function toggleKind(k: string) {
    setHiddenKinds((prev) => { const n = new Set(prev); n.has(k) ? n.delete(k) : n.add(k); return n; });
  }

  // --- per-account selection ---
  const acct = accountList.find((a) => a.id === selAcct) ?? null;
  const acctCost = daily?.perAccount.find((a) => a.accountId === selAcct)?.cost ?? zerosD;
  const acctTok = tokens?.perAccount.find((a) => a.accountId === selAcct) ?? emptyKinds;
  const wBuckets = windows?.buckets ?? [];
  const wNulls = wBuckets.map(() => null as number | null);
  const acctWin = windows?.perAccount.find((a) => a.accountId === selAcct);

  return (
    <div className="main-inner">
      <div className="section-head" style={{ marginTop: 0 }}>
        <div><h1>Statistics</h1><p className="sub" style={{ margin: 0 }}>Usage across the account pool.</p></div>
        {canStats && <button className="ghost" onClick={async () => { if (confirm('Reset usage statistics for ALL users?')) { await api.resetAllStats(); load(); } }}>Reset all stats</button>}
      </div>

      {canStats && daily && (
        <>
          {/* control bar — drives every chart at once */}
          <div className="controlbar">
            <Segmented<number> value={days} onChange={setDays} options={[
              { value: 7, label: '7d' }, { value: 30, label: '30d' }, { value: 90, label: '90d' },
            ]} />
            <div className="daterange">
              <button className="ghost sm" onClick={() => setEndDate(shiftDate(endDate, -days))}>← prev</button>
              <span className="hint mono">{rangeStart} … {endDate}</span>
              <button className="ghost sm" disabled={atToday} onClick={() => setEndDate(shiftDate(endDate, days))}>next →</button>
            </div>
          </div>

          {/* row 1 — three chart types side by side */}
          <div className="chart-row">
            <div className="panel">
              <div className="chart-card-head"><span className="t">Spend per day</span><span className="v">{fmtUsd(weekTotal)}</span></div>
              <StackedBarChart days={daily.days} series={spendSeries} height={190} fmt={fmtUsd} />
              {spendLegend.length > 1 && <Legend items={spendLegend} />}
            </div>

            <div className="panel">
              <div className="chart-card-head">
                <span className="t">Tokens per day</span>
                <Select ariaLabel="Filter by model" value={modelFilter} onChange={setModelFilter} options={modelOptions} minWidth={120} />
              </div>
              <StackedBarChart days={daily.days} series={tokenSeries(tokenTopSrc, hiddenKinds)} height={190} fmt={fmtTokens} />
              <Legend items={TOKEN_KINDS.map((k) => ({ key: k.key, label: k.label, color: k.color }))} hidden={hiddenKinds} onToggle={toggleKind} />
              <div className="hint" style={{ marginTop: 6 }}>Total tokens: <b style={{ color: 'var(--text)' }}>{fmtTokens(tokenTotal)}</b></div>
            </div>

            {windows && (
              <div className="panel">
                <div className="chart-card-head"><span className="t">Window utilization</span></div>
                <LineChart labels={windows.buckets} height={190} lines={[
                  { name: '5-hour', color: W5H, values: windows.totalFiveHour },
                  { name: 'weekly', color: WWK, values: windows.totalWeekly },
                ]} />
                <Legend items={[{ key: '5h', label: '5-hour', color: W5H }, { key: 'wk', label: 'Weekly', color: WWK }]} />
              </div>
            )}
          </div>

          {/* per-account — pick one account, see its three charts */}
          {canAccounts && accountList.length > 0 && (
            <>
              <div className="section-head">
                <h2 style={{ margin: 0 }}>Per account</h2>
                <Select ariaLabel="Select account" value={String(selAcct ?? '')} minWidth={160}
                  onChange={(v) => setSelAcct(Number(v))}
                  options={accountList.map((a) => ({ value: String(a.id), label: a.name }))} />
              </div>
              <div className="chart-row">
                <div className="panel">
                  <div className="chart-card-head"><span className="t">Spend per day</span><span className="v">{fmtUsd(acctCost.reduce((s, v) => s + v, 0))}</span></div>
                  <StackedBarChart days={daily.days} height={175} fmt={fmtUsd}
                    series={[{ name: acct?.name ?? '', color: SERIES_COLORS[0], values: acctCost }]} />
                </div>
                <div className="panel">
                  <div className="chart-card-head"><span className="t">Tokens per day</span><span className="v">{fmtTokens(sumKinds(acctTok))}</span></div>
                  <StackedBarChart days={daily.days} height={175} fmt={fmtTokens}
                    series={tokenSeries(acctTok, hiddenKinds)} />
                  <Legend items={TOKEN_KINDS.map((k) => ({ key: k.key, label: k.label, color: k.color }))} hidden={hiddenKinds} onToggle={toggleKind} />
                </div>
                <div className="panel">
                  <div className="chart-card-head"><span className="t">Window utilization</span></div>
                  <LineChart labels={wBuckets} height={175} lines={[
                    { name: '5-hour', color: W5H, values: acctWin?.fiveHour ?? wNulls },
                    { name: 'weekly', color: WWK, values: acctWin?.weekly ?? wNulls },
                  ]} />
                  <Legend items={[{ key: '5h', label: '5-hour', color: W5H }, { key: 'wk', label: 'Weekly', color: WWK }]} />
                </div>
              </div>
            </>
          )}

          <h2>Per account (24h)</h2>
          <div className="tablewrap">
            <table>
              <thead><tr>{canAccounts && <th>Account</th>}<th className="num">Requests</th><th className="num">Input</th><th className="num">Output</th><th className="num">Cost</th></tr></thead>
              <tbody>
                {summary.map((s) => (
                  <tr key={s.accountId}>
                    {canAccounts && <td>{s.accountName ?? `#${s.accountId}`}</td>}
                    <td className="num">{s.requests}</td>
                    <td className="num">{s.inputTokens.toLocaleString()}</td>
                    <td className="num">{s.outputTokens.toLocaleString()}</td>
                    <td className="num">{fmtUsd(s.cost)}</td>
                  </tr>
                ))}
                {summary.length === 0 && <tr><td colSpan={canAccounts ? 5 : 4} className="empty">No usage yet.</td></tr>}
              </tbody>
            </table>
          </div>
        </>
      )}

      {canRecent && (
        <>
          <h2>Recent requests</h2>
          <div className="tablewrap">
            <table>
              <thead><tr><th>Time</th>{canAccounts && <th>Account</th>}<th>Model</th><th className="num">In</th><th className="num">Out</th><th className="num">Cache R</th><th className="num">Cache W</th><th className="num">Cost</th><th>Status</th></tr></thead>
              <tbody>
                {recent.map((e) => (
                  <tr key={e.id}>
                    <td className="hint">{new Date(e.ts).toLocaleTimeString()}</td>
                    {canAccounts && <td>{e.accountName ?? '—'}</td>}
                    <td className="hint">{e.model ?? '—'}</td>
                    <td className="num">{e.inputTokens}</td>
                    <td className="num">{e.outputTokens}</td>
                    <td className="num">{e.cacheReadTokens}</td>
                    <td className="num">{e.cacheWriteTokens}</td>
                    <td className="num">{fmtUsd(e.cost)}</td>
                    <td><span className={`badge ${e.httpStatus >= 200 && e.httpStatus < 300 ? 'ok' : e.httpStatus === 429 ? 'warn' : 'bad'}`}>{e.httpStatus}</span></td>
                  </tr>
                ))}
                {recent.length === 0 && <tr><td colSpan={canAccounts ? 9 : 8} className="empty">No requests yet.</td></tr>}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}

import { useEffect, useMemo, useState } from 'react';
import {
  api, DailyStats, fmtTokens, fmtUsd, MyStats as MyStatsDto, TokenKindSeries, TokenStats, WindowStats,
} from '../api';
import { LineChart, SERIES_COLORS, StackedBarChart } from '../Chart';
import { Legend, shiftDate, sumKinds, todayUtc, tokenSeries, TOKEN_KINDS, W5H, WWK } from './statsShared';
import { Segmented, Select } from '../ui';

export function MyStats({ canReset }: { canReset: boolean }) {
  const [s, setS] = useState<MyStatsDto | null>(null);
  const [period, setPeriod] = useState<'today' | 'all'>('all');
  const [err, setErr] = useState<string | null>(null);

  // time-series charts (own usage + own personal accounts)
  const [daily, setDaily] = useState<DailyStats | null>(null);
  const [tokens, setTokens] = useState<TokenStats | null>(null);
  const [windows, setWindows] = useState<WindowStats | null>(null);
  const [days, setDays] = useState(7);
  const [endDate, setEndDate] = useState(todayUtc());
  const [modelFilter, setModelFilter] = useState('');
  const [hiddenKinds, setHiddenKinds] = useState<Set<string>>(new Set());
  const [selAcct, setSelAcct] = useState<number | null>(null);

  async function load() {
    try { setS(await api.myStats()); } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); const t = setInterval(load, 8000); return () => clearInterval(t); }, []);

  async function loadCharts() {
    try {
      const [d, t, w] = await Promise.all([
        api.myStatsDaily(days, endDate), api.myStatsTokens(days, endDate), api.myStatsWindows(days, endDate),
      ]);
      setDaily(d); setTokens(t); setWindows(w);
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { loadCharts(); /* eslint-disable-next-line */ }, [days, endDate]);
  useEffect(() => { const t = setInterval(loadCharts, 10000); return () => clearInterval(t); /* eslint-disable-line */ }, [days, endDate]);

  // personal accounts seen across any dataset — drives the per-account picker + window section
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

  if (err) return <div className="err">{err}</div>;
  if (!s) return <div className="hint">Loading…</div>;

  function statusBadge(st: number) {
    const cls = st >= 200 && st < 300 ? 'ok' : st === 429 ? 'warn' : 'bad';
    return <span className={`badge ${cls}`}>{st}</span>;
  }

  const atToday = endDate >= todayUtc();
  const rangeStart = daily?.days[0] ?? shiftDate(endDate, -(days - 1));
  const zerosD = (daily?.days ?? []).map(() => 0);
  const emptyKinds: TokenKindSeries = { input: zerosD, output: zerosD, cacheRead: zerosD, cacheWrite: zerosD };

  const spendTotal = daily ? daily.totalCost.reduce((a, b) => a + b, 0) : 0;
  const tokenTopSrc: TokenKindSeries = tokens
    ? (modelFilter === '' ? tokens.total : (tokens.perModel.find((m) => m.model === modelFilter) ?? tokens.total))
    : emptyKinds;
  const tokenTotal = sumKinds(tokenTopSrc);
  const modelOptions = [{ value: '', label: 'All models' }, ...(tokens?.models ?? []).map((m) => ({ value: m, label: m }))];
  function toggleKind(k: string) {
    setHiddenKinds((prev) => { const n = new Set(prev); n.has(k) ? n.delete(k) : n.add(k); return n; });
  }

  // per-account selection (own personal accounts only)
  const acct = accountList.find((a) => a.id === selAcct) ?? null;
  const acctCost = daily?.perAccount.find((a) => a.accountId === selAcct)?.cost ?? zerosD;
  const acctTok = tokens?.perAccount.find((a) => a.accountId === selAcct) ?? emptyKinds;
  const wBuckets = windows?.buckets ?? [];
  const wNulls = wBuckets.map(() => null as number | null);
  const acctWin = windows?.perAccount.find((a) => a.accountId === selAcct);
  const hasAccounts = accountList.length > 0;

  return (
    <div className="main-inner">
      <div className="section-head" style={{ marginTop: 0 }}>
        <div><h1>My statistics</h1><p className="sub" style={{ margin: 0 }}>Your own usage only. Refreshes every 8s.</p></div>
        {canReset && <button className="ghost" onClick={async () => { if (confirm('Reset your own statistics? This cannot be undone.')) { await api.resetMyStats(); load(); loadCharts(); } }}>Reset my stats</button>}
      </div>

      <div className="cards" style={{ marginTop: 18 }}>
        <div className="card"><div className="label">Spent today</div><div className="value">{fmtUsd(s.todayCost)}</div><div className="hint">{fmtUsd(s.totalCost)} all-time</div></div>
        <div className="card" style={{ minWidth: 200 }}>
          <div className="label">Daily limit</div>
          {s.dailyCostLimit == null ? <div className="value" style={{ fontSize: 20 }}>unlimited</div> : (
            <>
              <div className="value" style={{ fontSize: 18 }}>{fmtUsd(s.todayCost)} / {fmtUsd(s.dailyCostLimit)}</div>
              <div className="bar" style={{ marginTop: 8 }}><span style={{ width: `${Math.min(100, Math.round((s.todayCost / s.dailyCostLimit) * 100))}%` }} /></div>
            </>
          )}
        </div>
        <div className="card"><div className="label">Requests today</div><div className="value">{s.todayRequests.toLocaleString()}</div><div className="hint">{s.totalRequests.toLocaleString()} all-time</div></div>
        <div className="card"><div className="label">Tokens today</div><div className="value" style={{ fontSize: 22 }}>{fmtTokens(s.todayClean)}</div><div className="hint">{fmtTokens(s.totalClean)} all-time</div></div>
      </div>

      {daily && (
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

          {/* row 1 — my own usage (Spend + Tokens); window is per personal account */}
          <div className="chart-row">
            <div className="panel">
              <div className="chart-card-head"><span className="t">Spend per day</span><span className="v">{fmtUsd(spendTotal)}</span></div>
              <StackedBarChart days={daily.days} height={190} fmt={fmtUsd}
                series={[{ name: 'Total', color: SERIES_COLORS[0], values: daily.totalCost }]} />
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

            {hasAccounts && windows && (
              <div className="panel">
                <div className="chart-card-head"><span className="t">Window utilization</span><span className="hint">my accounts</span></div>
                <LineChart labels={windows.buckets} height={190} lines={[
                  { name: '5-hour', color: W5H, values: windows.totalFiveHour },
                  { name: 'weekly', color: WWK, values: windows.totalWeekly },
                ]} />
                <Legend items={[{ key: '5h', label: '5-hour', color: W5H }, { key: 'wk', label: 'Weekly', color: WWK }]} />
              </div>
            )}
          </div>

          {/* per-account — pick one of my personal accounts, see its three charts */}
          {hasAccounts && (
            <>
              <div className="section-head">
                <h2 style={{ margin: 0 }}>Per account</h2>
                <Select ariaLabel="Select account" value={String(selAcct ?? '')} minWidth={160}
                  onChange={(v) => setSelAcct(Number(v))}
                  options={accountList.map((a) => ({ value: String(a.id), label: a.name }))} />
              </div>
              <div className="chart-row">
                <div className="panel">
                  <div className="chart-card-head"><span className="t">Spend per day</span><span className="v">{fmtUsd(acctCost.reduce((a, v) => a + v, 0))}</span></div>
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
        </>
      )}

      <div className="head-row">
        <h2>By model</h2>
        <Segmented<'today' | 'all'> value={period} onChange={setPeriod} options={[
          { value: 'today', label: 'Today' }, { value: 'all', label: 'All time' },
        ]} />
      </div>
      {(() => {
        const rows = period === 'today' ? s.perModelToday : s.perModel;
        return (
          <div className="tablewrap">
            <table>
              <thead><tr><th>Model</th><th className="num">Requests</th><th className="num">Tokens</th><th className="num">Cost</th></tr></thead>
              <tbody>
                {rows.map((m, i) => (
                  <tr key={i}>
                    <td className="mono">{m.model ?? '—'}</td>
                    <td className="num">{m.requests.toLocaleString()}</td>
                    <td className="num">{m.cleanTokens.toLocaleString()}</td>
                    <td className="num">{fmtUsd(m.cost)}</td>
                  </tr>
                ))}
                {rows.length === 0 && <tr><td colSpan={4} className="hint">No usage {period === 'today' ? 'today' : 'yet'}.</td></tr>}
              </tbody>
            </table>
          </div>
        );
      })()}

      <h2>Recent requests</h2>
      <div className="tablewrap">
        <table>
          <thead><tr><th>Time</th><th>Account</th><th>Model</th><th>In</th><th>Out</th><th>Cache R</th><th>Cache W</th><th>Cost</th><th>Status</th></tr></thead>
          <tbody>
            {s.recent.map((e) => (
              <tr key={e.id}>
                <td className="hint">{new Date(e.ts).toLocaleTimeString()}</td>
                <td>{e.accountName ?? '—'}</td>
                <td className="hint">{e.model ?? '—'}</td>
                <td className="num">{e.inputTokens}</td>
                <td className="num">{e.outputTokens}</td>
                <td className="num">{e.cacheReadTokens}</td>
                <td className="num">{e.cacheWriteTokens}</td>
                <td className="num">{fmtUsd(e.cost)}</td>
                <td>{statusBadge(e.httpStatus)}</td>
              </tr>
            ))}
            {s.recent.length === 0 && <tr><td colSpan={9} className="hint">No requests yet.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}

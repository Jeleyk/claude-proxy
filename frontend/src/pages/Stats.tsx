import { useEffect, useState } from 'react';
import { api, DailyStats, fmtUsd, has, UsageEvent, UsageSummary, UserDto, WindowStats } from '../api';
import { LineChart, SERIES_COLORS, StackedBarChart } from '../Chart';

const C5H = '#6ea8fe', CWK = '#d97757';

function todayUtc(): string { return new Date().toISOString().slice(0, 10); }
function shiftDate(d: string, days: number): string {
  const dt = new Date(d + 'T00:00:00Z'); dt.setUTCDate(dt.getUTCDate() + days); return dt.toISOString().slice(0, 10);
}

export function Stats({ user }: { user: UserDto }) {
  const canStats = has(user, 'STATS_VIEW');
  const canRecent = has(user, 'STATS_VIEW_RECENT') || canStats;
  const canAccounts = has(user, 'STATS_VIEW_ACCOUNTS') || canStats;

  const [daily, setDaily] = useState<DailyStats | null>(null);
  const [windows, setWindows] = useState<WindowStats | null>(null);
  const [summary, setSummary] = useState<UsageSummary[]>([]);
  const [recent, setRecent] = useState<UsageEvent[]>([]);
  const [endDate, setEndDate] = useState(todayUtc());
  const [days] = useState(7);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    try {
      if (canStats) { setDaily(await api.statsDaily(days, endDate)); setWindows(await api.statsWindows(days, endDate)); setSummary(await api.statsSummary()); }
      if (canRecent) setRecent(await api.statsRecent());
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); }, [endDate]);
  useEffect(() => { const t = setInterval(load, 10000); return () => clearInterval(t); }, [endDate]);

  if (err) return <div className="err">{err}</div>;

  const atToday = endDate >= todayUtc();
  const rangeStart = daily?.days[0] ?? shiftDate(endDate, -(days - 1));

  // combined chart series: stacked by account if allowed, else a single total bar
  const combinedSeries = daily
    ? (canAccounts && daily.perAccount.length
        ? daily.perAccount.map((a, i) => ({ name: a.accountName ?? `#${a.accountId}`, color: SERIES_COLORS[i % SERIES_COLORS.length], values: a.cost }))
        : [{ name: 'Total', color: SERIES_COLORS[0], values: daily.totalCost }])
    : [];
  const weekTotal = daily ? daily.totalCost.reduce((s, v) => s + v, 0) : 0;

  return (
    <div className="main-inner">
      <div className="section-head" style={{ marginTop: 0 }}>
        <div><h1>Statistics</h1><p className="sub" style={{ margin: 0 }}>Daily spend and recent activity.</p></div>
        {canStats && <button className="ghost" onClick={async () => { if (confirm('Reset usage statistics for ALL users?')) { await api.resetAllStats(); load(); } }}>Reset all stats</button>}
      </div>

      {canStats && daily && (
        <>
          <div className="section-head">
            <h2 style={{ margin: 0 }}>Spend per day — all accounts</h2>
            <div className="row">
              <button className="ghost sm" onClick={() => setEndDate(shiftDate(endDate, -days))}>← prev</button>
              <span className="hint mono">{rangeStart} … {endDate}</span>
              <button className="ghost sm" disabled={atToday} onClick={() => setEndDate(shiftDate(endDate, days))}>next →</button>
            </div>
          </div>
          <div className="panel">
            <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
              <span className="hint">Total for range</span><b>{fmtUsd(weekTotal)}</b>
            </div>
            <StackedBarChart days={daily.days} series={combinedSeries} fmt={fmtUsd} />
            {canAccounts && daily.perAccount.length > 0 && (
              <div className="pillrow" style={{ marginTop: 10 }}>
                {daily.perAccount.map((a, i) => (
                  <span key={a.accountId} className="grouptag" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                    <span style={{ width: 8, height: 8, borderRadius: 2, background: SERIES_COLORS[i % SERIES_COLORS.length] }} />
                    {a.accountName ?? `#${a.accountId}`}
                  </span>
                ))}
              </div>
            )}
          </div>

          {canAccounts && daily.perAccount.map((a, i) => (
            <div key={a.accountId}>
              <h2>{a.accountName ?? `#${a.accountId}`} — spend per day</h2>
              <div className="panel">
                <StackedBarChart days={daily.days} height={150}
                  series={[{ name: a.accountName ?? `#${a.accountId}`, color: SERIES_COLORS[i % SERIES_COLORS.length], values: a.cost }]} fmt={fmtUsd} />
              </div>
            </div>
          ))}

          {windows && (
            <>
              <h2>Window utilization — all accounts (avg)</h2>
              <div className="panel">
                <LineChart labels={windows.buckets} lines={[
                  { name: '5-hour', color: C5H, values: windows.totalFiveHour },
                  { name: 'weekly', color: CWK, values: windows.totalWeekly },
                ]} />
              </div>
              {canAccounts && windows.perAccount.map((a) => (
                <div key={a.accountId}>
                  <h2>{a.accountName ?? `#${a.accountId}`} — window utilization</h2>
                  <div className="panel">
                    <LineChart labels={windows.buckets} height={150} lines={[
                      { name: '5-hour', color: C5H, values: a.fiveHour },
                      { name: 'weekly', color: CWK, values: a.weekly },
                    ]} />
                  </div>
                </div>
              ))}
            </>
          )}

          <h2>Per account (24h)</h2>
          <div className="tablewrap">
            <table>
              <thead><tr>{canAccounts && <th>Account</th>}<th>Requests</th><th>Input</th><th>Output</th><th>Cost</th></tr></thead>
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
                {summary.length === 0 && <tr><td colSpan={canAccounts ? 5 : 4} className="hint">No usage yet.</td></tr>}
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
              <thead><tr><th>Time</th>{canAccounts && <th>Account</th>}<th>Model</th><th>In</th><th>Out</th><th>Cache R</th><th>Cache W</th><th>Cost</th><th>Status</th></tr></thead>
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
                {recent.length === 0 && <tr><td colSpan={canAccounts ? 9 : 8} className="hint">No requests yet.</td></tr>}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}

import { useEffect, useState } from 'react';
import { api, fmtTokens, MyStats as MyStatsDto } from '../api';

export function MyStats() {
  const [s, setS] = useState<MyStatsDto | null>(null);
  const [tpp, setTpp] = useState(10000);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    try {
      setS(await api.myStats());
      setTpp((await api.config().catch(() => ({ tokensPerWindowPercent: 10000 }))).tokensPerWindowPercent);
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); const t = setInterval(load, 8000); return () => clearInterval(t); }, []);

  if (err) return <div className="err">{err}</div>;
  if (!s) return <div className="hint">Loading…</div>;

  function statusBadge(st: number) {
    const cls = st >= 200 && st < 300 ? 'ok' : st === 429 ? 'warn' : 'bad';
    return <span className={`badge ${cls}`}>{st}</span>;
  }

  return (
    <div className="main-inner">
      <div className="section-head" style={{ marginTop: 0 }}>
        <div><h1>My statistics</h1><p className="sub" style={{ margin: 0 }}>Your own usage only. Refreshes every 8s.</p></div>
        <button className="ghost" onClick={async () => { if (confirm('Reset your own statistics? This cannot be undone.')) { await api.resetMyStats(); load(); } }}>Reset my stats</button>
      </div>

      <div className="cards" style={{ marginTop: 18 }}>
        <div className="card"><div className="label">Requests today</div><div className="value">{s.todayRequests.toLocaleString()}</div><div className="hint">{s.totalRequests.toLocaleString()} all-time</div></div>
        <div className="card"><div className="label">Clean tokens today</div><div className="value" style={{ fontSize: 22 }}>{fmtTokens(s.todayClean)}</div><div className="hint">{fmtTokens(s.totalClean)} all-time</div></div>
        <div className="card"><div className="label">Dirty tokens today</div><div className="value" style={{ fontSize: 22 }}>{fmtTokens(s.todayDirty)}</div><div className="hint">≈ {(s.todayDirty / tpp).toFixed(1)}% window · {fmtTokens(s.totalDirty)} all-time</div></div>
      </div>

      <h2>By model (all-time)</h2>
      <div className="tablewrap">
        <table>
          <thead><tr><th>Model</th><th>Requests</th><th>Clean</th><th>Dirty</th></tr></thead>
          <tbody>
            {s.perModel.map((m, i) => (
              <tr key={i}>
                <td className="mono">{m.model ?? '—'}</td>
                <td className="num">{m.requests.toLocaleString()}</td>
                <td className="num">{m.cleanTokens.toLocaleString()}</td>
                <td className="num">{m.dirtyTokens.toLocaleString()}</td>
              </tr>
            ))}
            {s.perModel.length === 0 && <tr><td colSpan={4} className="hint">No usage yet.</td></tr>}
          </tbody>
        </table>
      </div>

      <h2>Recent requests</h2>
      <div className="tablewrap">
        <table>
          <thead><tr><th>Time</th><th>Account</th><th>Model</th><th>In</th><th>Out</th><th>Dirty</th><th>Status</th></tr></thead>
          <tbody>
            {s.recent.map((e) => (
              <tr key={e.id}>
                <td className="hint">{new Date(e.ts).toLocaleTimeString()}</td>
                <td>{e.accountName ?? '—'}</td>
                <td className="hint">{e.model ?? '—'}</td>
                <td className="num">{e.inputTokens}</td>
                <td className="num">{e.outputTokens}</td>
                <td className="num">{e.dirtyTokens}</td>
                <td>{statusBadge(e.httpStatus)}</td>
              </tr>
            ))}
            {s.recent.length === 0 && <tr><td colSpan={7} className="hint">No requests yet.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}

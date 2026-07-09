import { useEffect, useState } from 'react';
import { api } from '../api';

interface Summary { accountId: number; accountName: string | null; requests: number; inputTokens: number; outputTokens: number; dirtyTokens: number; }
interface Event { id: number; accountName: string | null; ts: string; inputTokens: number; outputTokens: number; dirtyTokens: number; httpStatus: number; model: string | null; }

export function Stats() {
  const [summary, setSummary] = useState<Summary[]>([]);
  const [recent, setRecent] = useState<Event[]>([]);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    try {
      const d = await api.stats();
      setSummary(d.summary); setRecent(d.recent);
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); const t = setInterval(load, 8000); return () => clearInterval(t); }, []);

  if (err) return <div className="err">{err}</div>;

  function statusBadge(s: number) {
    const cls = s >= 200 && s < 300 ? 'ok' : s === 429 ? 'warn' : 'bad';
    return <span className={`badge ${cls}`}>{s}</span>;
  }

  return (
    <div className="main-inner">
      <h1>Statistics</h1>
      <p className="sub">Usage over the last 24h. Refreshes every 8s.</p>

      <h2>Per account (24h)</h2>
      <div className="tablewrap">
        <table>
          <thead><tr><th>Account</th><th>Requests</th><th>Input</th><th>Output</th><th>Clean</th><th>Dirty</th></tr></thead>
          <tbody>
            {summary.map((s) => (
              <tr key={s.accountId}>
                <td>{s.accountName ?? `#${s.accountId}`}</td>
                <td className="num">{s.requests}</td>
                <td className="num">{s.inputTokens.toLocaleString()}</td>
                <td className="num">{s.outputTokens.toLocaleString()}</td>
                <td className="num">{(s.inputTokens + s.outputTokens).toLocaleString()}</td>
                <td className="num">{s.dirtyTokens.toLocaleString()}</td>
              </tr>
            ))}
            {summary.length === 0 && <tr><td colSpan={6} className="hint">No usage yet.</td></tr>}
          </tbody>
        </table>
      </div>

      <h2>Recent requests</h2>
      <div className="tablewrap">
        <table>
          <thead><tr><th>Time</th><th>Account</th><th>Model</th><th>In</th><th>Out</th><th>Dirty</th><th>Status</th></tr></thead>
          <tbody>
            {recent.map((e) => (
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
            {recent.length === 0 && <tr><td colSpan={7} className="hint">No requests yet.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}

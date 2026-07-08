import { useEffect, useState } from 'react';
import { api, AccountDto, PoolStats } from '../api';

function pct(n: number | null): string {
  return n == null ? '—' : `${Math.round(n * 100)}%`;
}

function healthBadge(a: AccountDto) {
  const cls = a.health === 'OK' ? (a.enabled ? 'ok' : 'muted') : a.health === 'REFRESH_FAILED' ? 'warn' : 'bad';
  const label = !a.enabled ? 'disabled' : a.health.toLowerCase().replace('_', ' ');
  return <span className={`badge ${cls}`}>{label}</span>;
}

export function Dashboard() {
  const [stats, setStats] = useState<PoolStats | null>(null);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    try {
      setStats(await api.accounts());
    } catch (e: any) {
      setErr(e.message);
    }
  }

  useEffect(() => {
    load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, []);

  if (err) return <div className="err">{err}</div>;
  if (!stats) return <div>Loading…</div>;

  const capPct = stats.totalEffectiveCapacity > 0
    ? stats.totalEffectiveRemaining / stats.totalEffectiveCapacity
    : 0;

  return (
    <div>
      <h1>Dashboard</h1>
      <p className="sub">Live view of the upstream account pool. Refreshes every 5s.</p>

      <div className="cards">
        <div className="card">
          <div className="label">Accounts</div>
          <div className="value">{stats.healthyAccounts}/{stats.totalAccounts}</div>
        </div>
        <div className="card">
          <div className="label">Active now</div>
          <div className="value">{stats.activeAccountId
            ? stats.accounts.find((a) => a.id === stats.activeAccountId)?.name ?? `#${stats.activeAccountId}`
            : '—'}</div>
        </div>
        <div className="card">
          <div className="label">Pool capacity left</div>
          <div className="value">{Math.round(capPct * 100)}%</div>
          <div className="hint">{stats.totalEffectiveRemaining.toFixed(2)} / {stats.totalEffectiveCapacity.toFixed(2)} weighted</div>
        </div>
      </div>

      <h2>Accounts by priority</h2>
      <table>
        <thead>
          <tr>
            <th>Prio</th><th>Name</th><th>Type</th><th>Usage</th><th>Threshold</th>
            <th>Coef</th><th>Eff. left</th><th>Reset</th><th>Status</th>
          </tr>
        </thead>
        <tbody>
          {stats.accounts.map((a) => (
            <tr key={a.id}>
              <td className="num">{a.priority}</td>
              <td>
                {a.name}{' '}
                {a.id === stats.activeAccountId && <span className="badge active">active</span>}
              </td>
              <td><span className="badge muted">{a.type.toLowerCase()}</span></td>
              <td>
                <div className="row">
                  <div className="bar" style={{ width: 90 }}>
                    <span style={{ width: `${Math.round((a.usageFraction ?? 0) * 100)}%` }} />
                  </div>
                  <span className="num">{pct(a.usageFraction)}</span>
                </div>
              </td>
              <td className="num">{pct(a.threshold)}</td>
              <td className="num">×{a.coefficient}</td>
              <td className="num">{a.effectiveRemaining == null ? '—' : a.effectiveRemaining.toFixed(2)}</td>
              <td className="hint">{a.resetAt ? new Date(a.resetAt).toLocaleTimeString() : '—'}</td>
              <td>{healthBadge(a)}</td>
            </tr>
          ))}
          {stats.accounts.length === 0 && (
            <tr><td colSpan={9} className="hint">No accounts yet. Add one on the Accounts page.</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

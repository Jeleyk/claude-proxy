import { useEffect, useState } from 'react';
import { AccountDto, api, fmtReset, fmtTokens, GroupDto, PoolStats, WindowLimitDto } from '../api';

function WindowCell({ w }: { w: WindowLimitDto | null }) {
  if (!w || (w.usageFraction == null && w.status == null && w.resetAt == null)) {
    return <span className="hint">—</span>;
  }
  const frac = w.usageFraction ?? (w.status === 'REJECTED' ? 1 : 0);
  return (
    <div className="win">
      <div className="win-top">
        <span>{w.usageFraction != null ? `${Math.round(frac * 100)}%` : (w.status?.toLowerCase() ?? '—')}</span>
        <span>reset <b>{fmtReset(w.resetAt)}</b></span>
      </div>
      <div className="bar"><span style={{ width: `${Math.round(frac * 100)}%` }} /></div>
    </div>
  );
}

function healthBadge(a: AccountDto) {
  const cls = !a.enabled ? 'muted' : a.health === 'OK' ? 'ok' : a.health === 'REFRESH_FAILED' ? 'warn' : 'bad';
  const label = !a.enabled ? 'disabled' : a.rateLimitedUntil && new Date(a.rateLimitedUntil) > new Date() ? 'limited' : a.health.toLowerCase().replace('_', ' ');
  return <span className={`badge ${cls}`}>{label}</span>;
}

export function Dashboard() {
  const [stats, setStats] = useState<PoolStats | null>(null);
  const [groups, setGroups] = useState<GroupDto[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  async function load() {
    try {
      setStats(await api.accounts());
      setGroups(await api.groups().catch(() => []));
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); const t = setInterval(load, 5000); return () => clearInterval(t); }, []);

  async function refreshAll() {
    setRefreshing(true);
    try { setStats(await api.refreshAll()); } catch (e: any) { setErr(e.message); } finally { setRefreshing(false); }
  }

  if (err) return <div className="err">{err}</div>;
  if (!stats) return <div className="hint">Loading…</div>;

  const groupName = (id: number | null) => groups.find((g) => g.id === id)?.name;
  const capPct = stats.totalEffectiveCapacity > 0 ? stats.totalEffectiveRemaining / stats.totalEffectiveCapacity : 0;
  const activeName = stats.activeAccountId ? stats.accounts.find((a) => a.id === stats.activeAccountId)?.name ?? `#${stats.activeAccountId}` : '—';

  return (
    <div className="main-inner">
      <div className="section-head" style={{ marginTop: 0 }}>
        <div>
          <h1>Dashboard</h1>
          <p className="sub" style={{ margin: 0 }}>Live view of the upstream account pool.</p>
        </div>
        <button className="ghost" disabled={refreshing} onClick={refreshAll}>{refreshing ? 'Refreshing…' : '↻ Refresh limits'}</button>
      </div>

      <div className="cards" style={{ marginTop: 18 }}>
        <div className="card"><div className="label">Accounts healthy</div><div className="value">{stats.healthyAccounts}/{stats.totalAccounts}</div></div>
        <div className="card"><div className="label">Active now</div><div className="value" style={{ fontSize: 20 }}>{activeName}</div></div>
        <div className="card">
          <div className="label">Pool capacity left</div>
          <div className="value">{Math.round(capPct * 100)}%</div>
          <div className="hint">{stats.totalEffectiveRemaining.toFixed(2)} / {stats.totalEffectiveCapacity.toFixed(2)} weighted</div>
        </div>
        <div className="card"><div className="label">Total requests</div><div className="value">{stats.totalRequests.toLocaleString()}</div></div>
        <div className="card">
          <div className="label">Tokens (in / out)</div>
          <div className="value" style={{ fontSize: 20 }}>{fmtTokens(stats.totalInputTokens)} / {fmtTokens(stats.totalOutputTokens)}</div>
        </div>
        <div className="card">
          <div className="label">Next reset</div>
          <div className="value" style={{ fontSize: 18 }}>5h: {fmtReset(stats.nextFiveHourReset)}</div>
          <div className="hint">weekly: {fmtReset(stats.nextWeeklyReset)}</div>
        </div>
      </div>

      <h2>Accounts by priority</h2>
      <div className="tablewrap">
        <table>
          <thead>
            <tr>
              <th>Prio</th><th>Name</th><th>Group</th><th>Type</th>
              <th>5-hour</th><th>Weekly</th><th>Coef</th><th>Eff. left</th><th>Tokens in/out</th><th>Status</th>
            </tr>
          </thead>
          <tbody>
            {stats.accounts.map((a) => (
              <tr key={a.id}>
                <td className="num">{a.priority}</td>
                <td>{a.name} {a.id === stats.activeAccountId && <span className="badge active">active</span>}</td>
                <td>{a.groupId ? <span className="grouptag">{groupName(a.groupId) ?? `#${a.groupId}`}</span> : <span className="hint">—</span>}</td>
                <td><span className="badge muted">{a.type.toLowerCase()}</span></td>
                <td><WindowCell w={a.fiveHour} /></td>
                <td><WindowCell w={a.weekly} /></td>
                <td className="num">×{a.coefficient}</td>
                <td className="num">{a.effectiveRemaining == null ? '—' : a.effectiveRemaining.toFixed(2)}</td>
                <td className="num">{fmtTokens(a.totalInputTokens)} / {fmtTokens(a.totalOutputTokens)}</td>
                <td>{healthBadge(a)}</td>
              </tr>
            ))}
            {stats.accounts.length === 0 && <tr><td colSpan={10} className="hint">No accounts yet. Add one on the Accounts page.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}

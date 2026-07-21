// Admin per-user statistics drill-in (USERS_MANAGE), opened from the Users list ("Stats"
// button): the full per-user stats view — the same as "My Stats" (source filter, charts,
// per-token breakdowns) — for one selected user.
import { useEffect, useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';
import { api, UserStatsOverview } from '../api';
import { UserStatsView } from './UserStatsView';

export function UserStats() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [rows, setRows] = useState<UserStatsOverview[] | null>(null);
  const [err, setErr] = useState<string | null>(null);

  // the overview only supplies the header (username / enabled badge) here
  async function load() {
    try { setRows(await api.usersOverview()); } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); }, []);

  const uid = id != null && /^\d+$/.test(id) ? Number(id) : null;
  if (uid == null) return <Navigate to="/users" replace />;
  const selected = rows?.find((r) => r.userId === uid);

  if (err) return <div className="err">{err}</div>;

  return (
    <div className="main-inner">
      <div className="section-head" style={{ marginTop: 0 }}>
        <div>
          <h1>
            {selected?.username ?? `User #${uid}`}
            {selected && !selected.enabled && <span className="badge bad" style={{ marginLeft: 10, verticalAlign: 'middle' }}>disabled</span>}
          </h1>
          <p className="sub" style={{ margin: 0 }}>Full usage statistics for this user — all sources, per token.</p>
        </div>
        <button className="ghost" onClick={() => navigate('/users')}>← Users</button>
      </div>
      <UserStatsView userId={uid} canReset onResetDone={load} />
    </div>
  );
}

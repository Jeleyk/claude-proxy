import { useEffect, useState } from 'react';
import { api, has, UserDto } from './api';
import { Login } from './pages/Login';
import { Dashboard } from './pages/Dashboard';
import { Accounts } from './pages/Accounts';
import { Users } from './pages/Users';
import { Tokens } from './pages/Tokens';
import { ModelPricing } from './pages/ModelPricing';
import { Stats } from './pages/Stats';
import { MyStats } from './pages/MyStats';

type View = 'dashboard' | 'accounts' | 'users' | 'tokens' | 'pricing' | 'stats' | 'mystats';

interface NavDef {
  key: View;
  label: string;
  perm?: string;
}

const NAV: NavDef[] = [
  { key: 'dashboard', label: 'Dashboard', perm: 'ACCOUNTS_VIEW' },
  { key: 'accounts', label: 'Accounts', perm: 'ACCOUNTS_VIEW' },
  { key: 'mystats', label: 'My Stats', perm: 'STATS_VIEW_OWN' },
  { key: 'stats', label: 'Statistics', perm: 'STATS_VIEW' },
  { key: 'tokens', label: 'Proxy Tokens', perm: 'PROXY_USE' },
  { key: 'pricing', label: 'Model Pricing', perm: 'ADMIN' },
  { key: 'users', label: 'Users & Roles', perm: 'USERS_MANAGE' },
];

export function App() {
  const [user, setUser] = useState<UserDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [view, setView] = useState<View>('dashboard');

  useEffect(() => {
    api.me().then(setUser).catch(() => setUser(null)).finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="login-wrap">Loading…</div>;
  if (!user) return <Login onLogin={(u) => { setUser(u); setView(firstAllowed(u)); }} />;

  const allowed = NAV.filter((n) => !n.perm || has(user, n.perm));
  const activeView = allowed.some((n) => n.key === view) ? view : (allowed[0]?.key ?? 'tokens');

  async function logout() {
    await api.logout().catch(() => {});
    setUser(null);
  }

  return (
    <div className="app">
      <div className="sidebar">
        <div className="brand"><span className="dot" />claude-proxy</div>
        {allowed.map((n) => (
          <div
            key={n.key}
            className={'navitem' + (n.key === activeView ? ' active' : '')}
            onClick={() => setView(n.key)}
          >
            {n.label}
          </div>
        ))}
        <div className="spacer" />
        <div className="userbox">
          Signed in as <b>{user.username}</b>
          <br />
          <span>{user.roles.join(', ') || 'no roles'}</span>
        </div>
        <button className="ghost" onClick={logout}>Log out</button>
      </div>
      <div className="main">
        {activeView === 'dashboard' && <Dashboard />}
        {activeView === 'accounts' && <Accounts user={user} />}
        {activeView === 'mystats' && <MyStats />}
        {activeView === 'stats' && <Stats />}
        {activeView === 'tokens' && <Tokens />}
        {activeView === 'pricing' && <ModelPricing />}
        {activeView === 'users' && <Users isAdmin={has(user, 'ADMIN')} />}
      </div>
    </div>
  );
}

function firstAllowed(u: UserDto): View {
  const first = NAV.find((n) => !n.perm || has(u, n.perm));
  return (first?.key ?? 'tokens') as View;
}

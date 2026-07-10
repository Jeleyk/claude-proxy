import { useEffect, useState } from 'react';
import { api, has, UserDto } from './api';
import { Icon, IconButton, ThemeToggle } from './ui';
import { Login } from './pages/Login';
import { Dashboard } from './pages/Dashboard';
import { MyAccounts } from './pages/MyAccounts';
import { Users } from './pages/Users';
import { Tokens } from './pages/Tokens';
import { ModelPricing } from './pages/ModelPricing';
import { Stats } from './pages/Stats';
import { MyStats } from './pages/MyStats';

type View = 'dashboard' | 'myaccounts' | 'users' | 'tokens' | 'pricing' | 'stats' | 'mystats';

interface NavDef {
  key: View;
  label: string;
  icon: string;
  perm?: string;
  anyPerm?: string[];
}

const NAV: NavDef[] = [
  { key: 'dashboard', label: 'Dashboard', icon: 'dashboard', perm: 'ACCOUNTS_VIEW' },
  { key: 'myaccounts', label: 'My Accounts', icon: 'accounts', perm: 'ACCOUNTS_OWN_MANAGE' },
  { key: 'mystats', label: 'My Stats', icon: 'mystats', perm: 'STATS_VIEW_OWN' },
  { key: 'stats', label: 'Statistics', icon: 'stats', anyPerm: ['STATS_VIEW', 'STATS_VIEW_RECENT', 'STATS_VIEW_ACCOUNTS'] },
  { key: 'tokens', label: 'Proxy Tokens', icon: 'tokens', perm: 'PROXY_USE' },
  { key: 'pricing', label: 'Model Pricing', icon: 'pricing', perm: 'ADMIN' },
  { key: 'users', label: 'Users & Roles', icon: 'users', perm: 'USERS_MANAGE' },
];

export function App() {
  const [user, setUser] = useState<UserDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [view, setView] = useState<View>('dashboard');
  const [collapsed, setCollapsed] = useState(() => {
    try { return localStorage.getItem('cp-sidebar-collapsed') === '1'; } catch { return false; }
  });
  const [drawerOpen, setDrawerOpen] = useState(false);

  useEffect(() => {
    api.me().then(setUser).catch(() => setUser(null)).finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setDrawerOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  function toggleCollapsed() {
    setCollapsed((c) => {
      const n = !c;
      try { localStorage.setItem('cp-sidebar-collapsed', n ? '1' : '0'); } catch { /* ignore */ }
      return n;
    });
  }

  if (loading) return <div className="login-wrap">Loading…</div>;
  if (!user) return <Login onLogin={(u) => { setUser(u); setView(firstAllowed(u)); }} />;

  const navAllowed = (n: NavDef) =>
    (!n.perm || has(user, n.perm)) && (!n.anyPerm || n.anyPerm.some((p) => has(user, p)));
  const allowed = NAV.filter(navAllowed);
  const activeView = allowed.some((n) => n.key === view) ? view : (allowed[0]?.key ?? 'tokens');
  const activeLabel = allowed.find((n) => n.key === activeView)?.label ?? 'claude-proxy';

  async function logout() {
    await api.logout().catch(() => {});
    setUser(null);
  }

  function go(key: View) { setView(key); setDrawerOpen(false); }

  return (
    <div className={'app' + (collapsed ? ' collapsed' : '') + (drawerOpen ? ' drawer-open' : '')}>
      <div className="scrim" onClick={() => setDrawerOpen(false)} />

      <aside className="sidebar">
        <div className="brand">
          <span className="dot" style={{ color: '#fdf3ea' }}><Icon name="sparkle" size={13} /></span>
          <span className="name">claude-proxy</span>
        </div>
        <nav>
          {allowed.map((n) => (
            <button
              key={n.key}
              type="button"
              className={'navitem' + (n.key === activeView ? ' active' : '')}
              onClick={() => go(n.key)}
              title={collapsed ? n.label : undefined}
              aria-current={n.key === activeView ? 'page' : undefined}
            >
              <Icon name={n.icon} />
              <span className="label">{n.label}</span>
            </button>
          ))}
        </nav>
        <div className="spacer" />
        <div className="sidebar-foot">
          <ThemeToggle />
          <div className="userbox">
            Signed in as <b>{user.username}</b>
            <br />
            <span>{user.roles.join(', ') || 'no roles'}</span>
          </div>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <button className="ghost sm" onClick={logout}>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
                <Icon name="logout" size={15} /><span className="logout-label">Log out</span>
              </span>
            </button>
            <IconButton className="collapse-btn" icon={collapsed ? 'expand' : 'collapse'}
              onClick={toggleCollapsed} label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} />
          </div>
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <IconButton icon="menu" onClick={() => setDrawerOpen(true)} label="Open menu" />
          <span className="tb-brand">{activeLabel}</span>
          <div className="right"><ThemeToggle /></div>
        </header>
        <div className="main-scroll">
          {activeView === 'dashboard' && <Dashboard user={user} />}
          {activeView === 'myaccounts' && <MyAccounts />}
          {activeView === 'mystats' && <MyStats canReset={has(user, 'STATS_RESET_OWN')} />}
          {activeView === 'stats' && <Stats user={user} />}
          {activeView === 'tokens' && <Tokens />}
          {activeView === 'pricing' && <ModelPricing />}
          {activeView === 'users' && <Users isAdmin={has(user, 'ADMIN')} />}
        </div>
      </div>
    </div>
  );
}

function firstAllowed(u: UserDto): View {
  const first = NAV.find((n) => (!n.perm || has(u, n.perm)) && (!n.anyPerm || n.anyPerm.some((p) => has(u, p))));
  return (first?.key ?? 'tokens') as View;
}

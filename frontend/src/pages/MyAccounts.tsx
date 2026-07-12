import { useEffect, useState } from 'react';
import { AccountDto, api, has, PoolStats, UserDto } from '../api';
import { AccountEditModal, AccountsTable, AddAccountModal, PoolCards, personalAccountApi } from '../accounts';
import { Segmented } from '../ui';

export function MyAccounts({ user, onUserChange }: { user: UserDto; onUserChange: (u: UserDto) => void }) {
  const [stats, setStats] = useState<PoolStats | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [editing, setEditing] = useState<AccountDto | null>(null);
  const [adding, setAdding] = useState(false);

  const canToggleOrder = has(user, 'ACCOUNTS_ORDER_TOGGLE');

  async function load() {
    try { setStats(await api.myAccounts()); } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); }, []);

  async function refreshAll() {
    setRefreshing(true);
    try { setStats(await api.refreshMyAll()); } catch (e: any) { setErr(e.message); } finally { setRefreshing(false); }
  }
  async function setOrder(preferGlobal: boolean) {
    try { onUserChange(await api.setAccountOrder(preferGlobal)); } catch (e: any) { setErr(e.message); }
  }
  async function toggle(a: AccountDto, v: boolean) { setStats(await api.updateMyAccount(a.id, { enabled: v })); }
  async function del(a: AccountDto) { if (confirm(`Delete account "${a.name}"?`)) { await api.deleteMyAccount(a.id); setStats(await api.myAccounts()); } }
  async function refreshOne(a: AccountDto) { setStats(await api.refreshMyOne(a.id)); }

  if (err) return <div className="err">{err}</div>;

  return (
    <div className="main-inner">
      <div className="section-head" style={{ marginTop: 0 }}>
        <div>
          <h1>My Accounts</h1>
          <p className="sub" style={{ margin: 0 }}>
            Your personal accounts — {user.preferGlobalPool ? 'used after the shared pool' : 'tried before the shared pool'}, and kept out of global statistics.
          </p>
        </div>
        <div className="row">
          <label className="order-toggle" title={canToggleOrder ? undefined : 'Routing order is fixed for your role'}>
            <span className="hint">Try first</span>
            <Segmented<'personal' | 'global'>
              disabled={!canToggleOrder}
              value={user.preferGlobalPool ? 'global' : 'personal'}
              onChange={(v) => setOrder(v === 'global')}
              options={[{ value: 'personal', label: 'Personal' }, { value: 'global', label: 'Global pool' }]} />
          </label>
          <button onClick={() => setAdding(true)}>+ Add account</button>
          <button className="ghost" disabled={refreshing} onClick={refreshAll}>{refreshing ? 'Refreshing…' : '↻ Refresh limits'}</button>
        </div>
      </div>

      {!stats ? (<div className="hint">Loading…</div>) : (
        <>
          <PoolCards stats={stats} scope="personal" />
          <h2>Accounts by priority</h2>
          <AccountsTable stats={stats} groups={[]} showGroup={false} canManage
            onEdit={setEditing} onToggle={toggle} onDelete={del} onRefreshOne={refreshOne} />
        </>
      )}

      {editing && (
        <AccountEditModal a={editing} groups={[]} scope="personal" update={api.updateMyAccount}
          onClose={() => setEditing(null)} onSaved={(s) => { setStats(s); setEditing(null); }} />
      )}
      {adding && (
        <AddAccountModal scope="personal" groups={[]} accountApi={personalAccountApi}
          onClose={() => setAdding(false)} onDone={setStats} />
      )}
    </div>
  );
}

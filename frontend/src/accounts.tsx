// Shared account UI: the rich pool table, summary cards, add/edit modals and group
// management. Used by the Dashboard (global pool), My Accounts (personal) and the
// admin oversight view on the Users page.
import { useState } from 'react';
import { AccountDto, api, fmtReset, fmtTokens, fmtUsd, GroupDto, PoolStats, WindowLimitDto } from './api';
import { Modal, Segmented, Switch } from './ui';

/** The scope-specific API calls a table/modal needs. Bound to the global or personal endpoints. */
export interface AccountApi {
  create: (b: unknown) => Promise<PoolStats>;
  update: (id: number, b: unknown) => Promise<PoolStats>;
  remove: (id: number) => Promise<unknown>;
  refreshOne: (id: number) => Promise<PoolStats>;
  refreshAll: () => Promise<PoolStats>;
  oauthStart: () => Promise<{ authorizeUrl: string; state: string }>;
  oauthComplete: (b: unknown) => Promise<PoolStats>;
}

export const globalAccountApi: AccountApi = {
  create: api.createAccount, update: api.updateAccount, remove: api.deleteAccount,
  refreshOne: api.refreshOne, refreshAll: api.refreshAll, oauthStart: api.oauthStart, oauthComplete: api.oauthComplete,
};

export const personalAccountApi: AccountApi = {
  create: api.createMyAccount, update: api.updateMyAccount, remove: api.deleteMyAccount,
  refreshOne: api.refreshMyOne, refreshAll: api.refreshMyAll, oauthStart: api.myOauthStart, oauthComplete: api.myOauthComplete,
};

export type Scope = 'global' | 'personal';

/* ---------------------------------------------------------------- cells */

function WindowCell({ w, isApi }: { w: WindowLimitDto | null; isApi: boolean }) {
  if (isApi) return <span className="hint">n/a</span>;
  if (!w || (w.usageFraction == null && w.resetAt == null && w.status !== 'REJECTED')) {
    return <span className="hint">—</span>;
  }
  const hasPct = w.usageFraction != null;
  const frac = w.usageFraction ?? (w.status === 'REJECTED' ? 1 : 0);
  return (
    <div className="win">
      <div className="win-top">
        <span>{hasPct ? `${Math.round(frac * 100)}%` : (w.status === 'REJECTED' ? 'limited' : '—')}</span>
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

/* ---------------------------------------------------------------- summary cards */

export function PoolCards({ stats, scope }: { stats: PoolStats; scope: Scope }) {
  const capPct = stats.totalEffectiveCapacity > 0 ? stats.totalEffectiveRemaining / stats.totalEffectiveCapacity : 0;
  const activeName = stats.activeAccountId ? stats.accounts.find((a) => a.id === stats.activeAccountId)?.name ?? `#${stats.activeAccountId}` : '—';
  return (
    <div className="cards" style={{ marginTop: 18 }}>
      <div className="card"><div className="label">{scope === 'personal' ? 'My accounts healthy' : 'Accounts healthy'}</div><div className="value">{stats.healthyAccounts}/{stats.totalAccounts}</div></div>
      <div className="card"><div className="label">Active now</div><div className="value" style={{ fontSize: 20 }}>{activeName}</div></div>
      <div className="card">
        <div className="label">Capacity left</div>
        <div className="value">{Math.round(capPct * 100)}%</div>
        <div className="hint">{stats.totalEffectiveRemaining.toFixed(2)} / {stats.totalEffectiveCapacity.toFixed(2)} weighted</div>
      </div>
      <div className="card"><div className="label">Total requests</div><div className="value">{stats.totalRequests.toLocaleString()}</div></div>
      <div className="card">
        <div className="label">Total cost</div>
        <div className="value">{fmtUsd(stats.totalCost)}</div>
        <div className="hint">{fmtTokens(stats.totalInputTokens)} in / {fmtTokens(stats.totalOutputTokens)} out</div>
        <div className="hint">{fmtTokens(stats.totalCacheReadTokens)} cache-r / {fmtTokens(stats.totalCacheWriteTokens)} cache-w</div>
      </div>
      <div className="card">
        <div className="label">Next reset</div>
        <div className="value" style={{ fontSize: 18 }}>5h: {fmtReset(stats.nextFiveHourReset)}</div>
        <div className="hint">weekly: {fmtReset(stats.nextWeeklyReset)}</div>
      </div>
    </div>
  );
}

/* ---------------------------------------------------------------- rich table */

export function AccountsTable({ stats, groups, showGroup, canManage, onEdit, onToggle, onDelete, onRefreshOne }: {
  stats: PoolStats;
  groups: GroupDto[];
  showGroup: boolean;
  canManage: boolean;
  onEdit: (a: AccountDto) => void;
  onToggle: (a: AccountDto, v: boolean) => void;
  onDelete: (a: AccountDto) => void;
  onRefreshOne: (a: AccountDto) => void;
}) {
  const groupName = (id: number | null) => groups.find((g) => g.id === id)?.name;
  const cols = 10 + (showGroup ? 1 : 0) + (canManage ? 1 : 0);
  return (
    <div className="tablewrap">
      <table>
        <thead>
          <tr>
            <th>Prio</th><th>Name</th>{showGroup && <th>Group</th>}<th>Type</th>
            <th>5-hour</th><th>Weekly</th><th>Coef</th><th>Eff. left</th><th>Cost</th>
            <th>Tokens in/out · cache r/w</th><th>Status</th>{canManage && <th></th>}
          </tr>
        </thead>
        <tbody>
          {stats.accounts.map((a) => (
            <tr key={a.id}>
              <td className="num">{a.priority}</td>
              <td>{a.name} {a.id === stats.activeAccountId && <span className="badge active">active</span>}</td>
              {showGroup && <td>{a.groupId ? <span className="grouptag">{groupName(a.groupId) ?? `#${a.groupId}`}</span> : <span className="hint">—</span>}</td>}
              <td><span className="badge muted">{a.type.toLowerCase()}</span></td>
              <td><WindowCell w={a.fiveHour} isApi={a.type === 'API_KEY'} /></td>
              <td><WindowCell w={a.weekly} isApi={a.type === 'API_KEY'} /></td>
              <td className="num">×{a.coefficient}</td>
              <td className="num">{a.effectiveRemaining == null ? '—' : a.effectiveRemaining.toFixed(2)}</td>
              <td className="num">{fmtUsd(a.totalCost)}</td>
              <td className="num">
                {fmtTokens(a.totalInputTokens)} / {fmtTokens(a.totalOutputTokens)}
                <div className="hint">cache {fmtTokens(a.totalCacheReadTokens)} / {fmtTokens(a.totalCacheWriteTokens)}</div>
              </td>
              <td>{healthBadge(a)}</td>
              {canManage && (
                <td>
                  <div className="row" style={{ alignItems: 'center' }}>
                    <Switch checked={a.enabled} onChange={(v) => onToggle(a, v)} />
                    <button className="sm ghost" onClick={() => onEdit(a)}>Edit</button>
                    <button className="sm ghost" onClick={() => onRefreshOne(a)} title="Refresh limits">↻</button>
                    <button className="sm danger" onClick={() => onDelete(a)}>Delete</button>
                  </div>
                </td>
              )}
            </tr>
          ))}
          {stats.accounts.length === 0 && <tr><td colSpan={cols} className="hint">No accounts yet.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

/* ---------------------------------------------------------------- groups (global only) */

export function GroupSelect({ value, groups, onChange }: { value: number | null; groups: GroupDto[]; onChange: (v: number | null) => void }) {
  return (
    <select value={value ?? ''} onChange={(e) => onChange(e.target.value ? +e.target.value : null)}>
      <option value="">— ungrouped —</option>
      {groups.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
    </select>
  );
}

export function Groups({ groups, onChange, onAccountsChange }: { groups: GroupDto[]; onChange: (g: GroupDto[]) => void; onAccountsChange: (s: PoolStats) => void }) {
  const [name, setName] = useState('');
  async function create() { if (!name.trim()) return; onChange(await api.createGroup(name.trim())); setName(''); }
  async function del(id: number) {
    if (!confirm('Delete group? Accounts in it become ungrouped.')) return;
    await api.deleteGroup(id); onChange(await api.groups()); onAccountsChange(await api.accounts());
  }
  async function rename(id: number, cur: string) {
    const n = prompt('Rename group', cur); if (n && n.trim()) onChange(await api.renameGroup(id, n.trim()));
  }
  return (
    <div className="panel narrow">
      <h2 style={{ marginTop: 0 }}>Account groups</h2>
      <p className="hint" style={{ marginTop: -4 }}>Group accounts to grant users access to a subset. Ungrouped accounts are usable by everyone.</p>
      <div className="pillrow" style={{ marginBottom: 12 }}>
        {groups.map((g) => (
          <span key={g.id} className="grouptag" style={{ padding: '5px 10px', display: 'inline-flex', gap: 8, alignItems: 'center' }}>
            {g.name} <span className="hint">({g.accountCount})</span>
            <a onClick={() => rename(g.id, g.name)} style={{ cursor: 'pointer' }}>✎</a>
            <a onClick={() => del(g.id)} style={{ cursor: 'pointer', color: 'var(--bad)' }}>×</a>
          </span>
        ))}
        {groups.length === 0 && <span className="hint">No groups yet.</span>}
      </div>
      <div className="row">
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="new group name" onKeyDown={(e) => e.key === 'Enter' && create()} />
        <button onClick={create}>Add group</button>
      </div>
    </div>
  );
}

/* ---------------------------------------------------------------- edit modal */

export function AccountEditModal({ a, groups, scope, update, onClose, onSaved }: {
  a: AccountDto; groups: GroupDto[]; scope: Scope; update: AccountApi['update'];
  onClose: () => void; onSaved: (s: PoolStats) => void;
}) {
  const [name, setName] = useState(a.name);
  const [prio, setPrio] = useState(a.priority);
  const [thr, setThr] = useState(a.threshold);
  const [coef, setCoef] = useState(a.coefficient);
  const [group, setGroup] = useState<number | null>(a.groupId);
  const [clientId, setClientId] = useState(a.clientId ?? '');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const isGlobal = scope === 'global';

  async function save() {
    setBusy(true); setErr(null);
    try {
      const body: any = { name, priority: prio, threshold: thr, coefficient: coef, clientId: clientId.trim() || undefined };
      if (isGlobal) { body.groupId = group; body.clearGroup = group == null; }
      onSaved(await update(a.id, body));
    } catch (e: any) { setErr(e.message); setBusy(false); }
  }
  function regen() { setClientId(crypto.randomUUID()); }

  return (
    <Modal title={`Edit ${a.name}`} onClose={onClose}
      footer={<><button className="ghost" onClick={onClose}>Cancel</button><button disabled={busy} onClick={save}>{busy ? '…' : 'Save'}</button></>}>
      <label className="field"><span>Name</span><input value={name} onChange={(e) => setName(e.target.value)} /></label>
      <div className="grid2">
        <label className="field"><span>Priority (lower = used first)</span><input type="number" value={prio} onChange={(e) => setPrio(Math.trunc(+e.target.value))} /></label>
        {isGlobal && <label className="field"><span>Group</span><GroupSelect value={group} groups={groups} onChange={setGroup} /></label>}
        <label className="field"><span>Threshold (0–1)</span><input type="number" step="0.05" min="0" max="1" value={thr} onChange={(e) => setThr(+e.target.value)} /></label>
        <label className="field"><span>Coefficient (×1 / ×5 / ×20)</span><input type="number" step="0.5" min="0" value={coef} onChange={(e) => setCoef(+e.target.value)} /></label>
      </div>
      <label className="field"><span>Client ID (per-account device identity sent upstream)</span>
        <div className="row"><input className="mono" value={clientId} onChange={(e) => setClientId(e.target.value)} placeholder="uuid" /><button className="ghost sm" onClick={regen} type="button">Regenerate</button></div>
      </label>
      <p className="hint">Type <b>{a.type.toLowerCase()}</b> · created {new Date(a.createdAt).toLocaleString()}</p>
      {err && <div className="err">{err}</div>}
    </Modal>
  );
}

/* ---------------------------------------------------------------- add modal (unified) */

export function AddAccountModal({ scope, groups, accountApi, onClose, onDone }: {
  scope: Scope; groups: GroupDto[]; accountApi: AccountApi; onClose: () => void; onDone: (s: PoolStats) => void;
}) {
  const isGlobal = scope === 'global';
  const [method, setMethod] = useState<'cred' | 'oauth'>('cred');
  // common config
  const [name, setName] = useState('');
  const [groupId, setGroupId] = useState<number | null>(null);
  const [priority, setPriority] = useState(100);
  const [threshold, setThreshold] = useState(0.9);
  const [coefficient, setCoefficient] = useState(1);
  // credential fields
  const [type, setType] = useState('API_KEY');
  const [apiKey, setApiKey] = useState('');
  const [accessToken, setAccessToken] = useState('');
  const [refreshToken, setRefreshToken] = useState('');
  // oauth flow
  const [oauthState, setOauthState] = useState<string | null>(null);
  const [oauthUrl, setOauthUrl] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const groupBody = () => (isGlobal ? { groupId } : {});

  async function submitCred() {
    setBusy(true); setErr(null);
    try {
      const body: any = { name, type, priority, threshold, coefficient, ...groupBody() };
      if (type === 'API_KEY') body.apiKey = apiKey;
      else { body.accessToken = accessToken; if (refreshToken) body.refreshToken = refreshToken; }
      onDone(await accountApi.create(body));
      onClose();
    } catch (e: any) { setErr(e.message); setBusy(false); }
  }
  async function startOauth() {
    setErr(null);
    try { const r = await accountApi.oauthStart(); setOauthState(r.state); setOauthUrl(r.authorizeUrl); window.open(r.authorizeUrl, '_blank'); }
    catch (e: any) { setErr(e.message); }
  }
  async function completeOauth() {
    if (!oauthState) return; setBusy(true); setErr(null);
    try {
      onDone(await accountApi.oauthComplete({ state: oauthState, code, name, priority, threshold, coefficient, ...groupBody() }));
      onClose();
    } catch (e: any) { setErr(e.message); setBusy(false); }
  }

  const footer = method === 'cred'
    ? <><button className="ghost" onClick={onClose}>Cancel</button><button disabled={busy} onClick={submitCred}>{busy ? '…' : 'Add account'}</button></>
    : !oauthState
      ? <><button className="ghost" onClick={onClose}>Cancel</button><button onClick={startOauth}>Start OAuth login</button></>
      : <><button className="ghost" onClick={onClose}>Cancel</button><button disabled={busy} onClick={completeOauth}>{busy ? '…' : 'Complete & add'}</button></>;

  return (
    <Modal title={isGlobal ? 'Add account' : 'Add my account'} onClose={onClose} footer={footer} width={560}>
      <div style={{ marginBottom: 14 }}>
        <Segmented<'cred' | 'oauth'> value={method} onChange={(m) => { setMethod(m); setErr(null); }} options={[
          { value: 'cred', label: 'By credential' },
          { value: 'oauth', label: 'Login with Claude' },
        ]} />
      </div>

      {method === 'cred' ? (
        <>
          <label className="field"><span>Name</span><input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. max-account-1" /></label>
          <div className="grid2">
            <label className="field"><span>Type</span>
              <select value={type} onChange={(e) => setType(e.target.value)}>
                <option value="API_KEY">API key (x-api-key)</option>
                <option value="OAUTH">OAuth (access + refresh)</option>
                <option value="OAUTH_STATIC">OAuth (access only)</option>
              </select>
            </label>
            {isGlobal && <label className="field"><span>Group</span><GroupSelect value={groupId} groups={groups} onChange={setGroupId} /></label>}
            <label className="field"><span>Priority (lower = first)</span><input type="number" value={priority} onChange={(e) => setPriority(+e.target.value)} /></label>
            <label className="field"><span>Threshold (0–1)</span><input type="number" step="0.05" value={threshold} onChange={(e) => setThreshold(+e.target.value)} /></label>
            <label className="field"><span>Coefficient (×1 / ×5 / ×20)</span><input type="number" step="0.5" value={coefficient} onChange={(e) => setCoefficient(+e.target.value)} /></label>
          </div>
          {type === 'API_KEY'
            ? <label className="field"><span>API key</span><input value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="sk-ant-api03-…" /></label>
            : <>
                <label className="field"><span>Access token</span><input value={accessToken} onChange={(e) => setAccessToken(e.target.value)} placeholder="sk-ant-oat01-…" /></label>
                {type === 'OAUTH' && <label className="field"><span>Refresh token</span><input value={refreshToken} onChange={(e) => setRefreshToken(e.target.value)} placeholder="sk-ant-ort01-…" /></label>}
              </>}
        </>
      ) : (
        <>
          {!oauthState ? (
            <p className="hint">Starts the same OAuth flow the Claude Code client uses. A tab opens; authorize, then paste the code back here.</p>
          ) : (
            <p className="hint">If the tab didn't open: <a href={oauthUrl!} target="_blank" rel="noreferrer">open authorize URL</a>. After approving, paste the returned code.</p>
          )}
          <label className="field"><span>Account name</span><input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. max-oauth-1" /></label>
          <div className="grid2">
            {isGlobal && <label className="field"><span>Group</span><GroupSelect value={groupId} groups={groups} onChange={setGroupId} /></label>}
            <label className="field"><span>Priority</span><input type="number" value={priority} onChange={(e) => setPriority(+e.target.value)} /></label>
            <label className="field"><span>Coefficient</span><input type="number" step="0.5" value={coefficient} onChange={(e) => setCoefficient(+e.target.value)} /></label>
          </div>
          {oauthState && <label className="field"><span>Authorization code</span><input value={code} onChange={(e) => setCode(e.target.value)} placeholder="paste code (or code#state)" /></label>}
        </>
      )}
      {err && <div className="err">{err}</div>}
    </Modal>
  );
}

// Shared account UI: the rich pool table, summary cards, add/edit modals and group
// management. Used by the Dashboard (global pool), My Accounts (personal) and the
// admin oversight view on the Users page.
import { useState } from 'react';
import { AccountDto, api, fmtReset, fmtTokens, fmtUsd, GroupDto, PoolStats, WindowLimitDto } from './api';
import { Modal, NumberInput, Segmented, Switch } from './ui';

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
        <div className="hint">{stats.totalEffectiveRemaining.toFixed(2)} / {stats.totalEffectiveCapacity.toFixed(2)} weighted · 5-hour</div>
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

interface HoverState { a: AccountDto; x: number; y: number; }

/** Cursor-following breakdown for the "Total" tokens column (in/out/cache + cost + requests). */
function TokenTooltip({ h }: { h: HoverState }) {
  const { a } = h;
  const total = a.totalInputTokens + a.totalOutputTokens + a.totalCacheReadTokens + a.totalCacheWriteTokens;
  // clamp within the viewport so it never spills off the edges
  const left = Math.min(h.x + 16, (typeof window !== 'undefined' ? window.innerWidth : 1200) - 210);
  const above = typeof window !== 'undefined' && h.y > window.innerHeight - 200;
  const top = above ? h.y - 200 : h.y + 16;
  const row = (label: string, val: string, cls = '') => (
    <div className={`tp-row ${cls}`.trim()}><span>{label}</span><b>{val}</b></div>
  );
  return (
    <div className="tokpop" style={{ left, top }}>
      {row('Input', a.totalInputTokens.toLocaleString())}
      {row('Output', a.totalOutputTokens.toLocaleString())}
      {row('Cache read', a.totalCacheReadTokens.toLocaleString())}
      {row('Cache write', a.totalCacheWriteTokens.toLocaleString())}
      <div className="tp-div" />
      {row('Total tokens', total.toLocaleString())}
      {row('Requests', a.totalRequests.toLocaleString())}
      {row('Cost', fmtUsd(a.totalCost), 'cost')}
    </div>
  );
}

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
  const [hover, setHover] = useState<HoverState | null>(null);
  const cols = 8 + (showGroup ? 1 : 0) + (canManage ? 1 : 0);
  return (
    <div className="tablewrap">
      <table>
        <thead>
          <tr>
            <th>Prio</th><th>Name</th>{showGroup && <th>Group</th>}<th>Type</th>
            <th>5-hour</th><th>Weekly</th><th>Coef</th>
            <th className="num">Total</th><th>Status</th>{canManage && <th></th>}
          </tr>
        </thead>
        <tbody>
          {stats.accounts.map((a) => {
            const total = a.totalInputTokens + a.totalOutputTokens + a.totalCacheReadTokens + a.totalCacheWriteTokens;
            return (
            <tr key={a.id}>
              <td className="num">{a.priority}</td>
              <td>{a.name} {a.id === stats.activeAccountId && <span className="badge active">active</span>}</td>
              {showGroup && <td>{a.groupId ? <span className="grouptag">{groupName(a.groupId) ?? `#${a.groupId}`}</span> : <span className="hint">—</span>}</td>}
              <td><span className="badge muted">{a.type.toLowerCase()}</span></td>
              <td><WindowCell w={a.fiveHour} isApi={a.type === 'API_KEY'} /></td>
              <td><WindowCell w={a.weekly} isApi={a.type === 'API_KEY'} /></td>
              <td className="num">×{a.coefficient}</td>
              <td className="num totalcell"
                onMouseEnter={(e) => setHover({ a, x: e.clientX, y: e.clientY })}
                onMouseMove={(e) => setHover({ a, x: e.clientX, y: e.clientY })}
                onMouseLeave={() => setHover((h) => (h?.a.id === a.id ? null : h))}>
                {fmtTokens(total)}
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
          ); })}
          {stats.accounts.length === 0 && <tr><td colSpan={cols} className="hint">No accounts yet.</td></tr>}
        </tbody>
      </table>
      {hover && <TokenTooltip h={hover} />}
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

/** One editable group row inside the modal: inline rename + delete. */
function GroupRow({ g, onChange, onAccountsChange }: { g: GroupDto; onChange: (g: GroupDto[]) => void; onAccountsChange: (s: PoolStats) => void }) {
  const [name, setName] = useState(g.name);
  const dirty = name.trim() !== g.name && name.trim() !== '';
  async function save() { if (dirty) onChange(await api.renameGroup(g.id, name.trim())); }
  async function del() {
    if (!confirm(`Delete group "${g.name}"? Accounts in it become ungrouped.`)) return;
    await api.deleteGroup(g.id); onChange(await api.groups()); onAccountsChange(await api.accounts());
  }
  return (
    <div className="grouprow">
      <input value={name} onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && save()} />
      <span className="gr-count">{g.accountCount} acct{g.accountCount === 1 ? '' : 's'}</span>
      {dirty && <button className="sm" onClick={save}>Save</button>}
      <button className="sm danger" onClick={del}>Delete</button>
    </div>
  );
}

/** Modal to manage account groups: list current (rename/delete) + create new. */
export function GroupsModal({ groups, onChange, onAccountsChange, onClose }: {
  groups: GroupDto[]; onChange: (g: GroupDto[]) => void; onAccountsChange: (s: PoolStats) => void; onClose: () => void;
}) {
  const [name, setName] = useState('');
  async function create() { if (!name.trim()) return; onChange(await api.createGroup(name.trim())); setName(''); }
  return (
    <Modal title="Account groups" onClose={onClose} footer={<button className="ghost" onClick={onClose}>Close</button>}>
      <p className="hint" style={{ marginTop: 0 }}>Group accounts to grant users access to a subset. Ungrouped accounts are usable by everyone.</p>
      <div style={{ marginBottom: 16 }}>
        {groups.length === 0 && <p className="hint">No groups yet.</p>}
        {groups.map((g) => <GroupRow key={g.id} g={g} onChange={onChange} onAccountsChange={onAccountsChange} />)}
      </div>
      <label className="field" style={{ marginBottom: 0 }}><span>New group</span>
        <div className="row">
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="group name" onKeyDown={(e) => e.key === 'Enter' && create()} />
          <button onClick={create}>Add</button>
        </div>
      </label>
    </Modal>
  );
}

/* ---------------------------------------------------------------- edit modal */

export function AccountEditModal({ a, groups, scope, update, onClose, onSaved }: {
  a: AccountDto; groups: GroupDto[]; scope: Scope; update: AccountApi['update'];
  onClose: () => void; onSaved: (s: PoolStats) => void;
}) {
  const [name, setName] = useState(a.name);
  const [prio, setPrio] = useState(a.priority);
  const [thrPct, setThrPct] = useState(Math.round(a.threshold * 100));
  const [coef, setCoef] = useState(a.coefficient);
  const [overThreshold, setOverThreshold] = useState(a.overThreshold);
  const [group, setGroup] = useState<number | null>(a.groupId);
  const [deviceId, setDeviceId] = useState(a.deviceId ?? '');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const isGlobal = scope === 'global';

  async function save() {
    setBusy(true); setErr(null);
    try {
      const body: any = { name, priority: prio, threshold: thrPct / 100, coefficient: coef, overThreshold, deviceId: deviceId.trim() || undefined };
      if (isGlobal) { body.groupId = group; body.clearGroup = group == null; }
      onSaved(await update(a.id, body));
    } catch (e: any) { setErr(e.message); setBusy(false); }
  }
  // 64-hex, shaped like a genuine Claude Code device id.
  function regen() {
    const b = new Uint8Array(32); crypto.getRandomValues(b);
    setDeviceId(Array.from(b, (x) => x.toString(16).padStart(2, '0')).join(''));
  }

  return (
    <Modal title={`Edit ${a.name}`} onClose={onClose}
      footer={<><button className="ghost" onClick={onClose}>Cancel</button><button disabled={busy} onClick={save}>{busy ? '…' : 'Save'}</button></>}>
      <label className="field"><span>Name</span><input value={name} onChange={(e) => setName(e.target.value)} /></label>
      <div className="grid2">
        <label className="field"><span>Priority (lower = used first)</span><NumberInput value={prio} onChange={(v) => setPrio(Math.trunc(v))} allowNegative /></label>
        {isGlobal && <label className="field"><span>Group</span><GroupSelect value={group} groups={groups} onChange={setGroup} /></label>}
        <label className="field"><span>Threshold (%)</span><NumberInput value={thrPct} onChange={setThrPct} min={0} max={100} /></label>
        <label className="field"><span>Coefficient (×1 / ×5 / ×20)</span><NumberInput value={coef} onChange={setCoef} min={0} step={0.5} /></label>
      </div>
      <label className="field switch-field">
        <span>Over-threshold fallback<br /><small className="hint">Keep using this account past its threshold when every account is saturated. Off by default.</small></span>
        <Switch checked={overThreshold} onChange={setOverThreshold} />
      </label>
      <label className="field"><span>Device ID (per-account fingerprint sent in request body)</span>
        <div className="row"><input className="mono" value={deviceId} onChange={(e) => setDeviceId(e.target.value)} placeholder="64 hex chars" /><button className="ghost sm" onClick={regen} type="button">Regenerate</button></div>
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
  const [thresholdPct, setThresholdPct] = useState(90);
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
      const body: any = { name, type, priority, threshold: thresholdPct / 100, coefficient, ...groupBody() };
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
      onDone(await accountApi.oauthComplete({ state: oauthState, code, name, priority, threshold: thresholdPct / 100, coefficient, ...groupBody() }));
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
            <label className="field"><span>Priority (lower = first)</span><NumberInput value={priority} onChange={(v) => setPriority(Math.trunc(v))} allowNegative /></label>
            <label className="field"><span>Threshold (%)</span><NumberInput value={thresholdPct} onChange={setThresholdPct} min={0} max={100} /></label>
            <label className="field"><span>Coefficient (×1 / ×5 / ×20)</span><NumberInput value={coefficient} onChange={setCoefficient} min={0} step={0.5} /></label>
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
            <label className="field"><span>Priority</span><NumberInput value={priority} onChange={(v) => setPriority(Math.trunc(v))} allowNegative /></label>
            <label className="field"><span>Coefficient</span><NumberInput value={coefficient} onChange={setCoefficient} min={0} step={0.5} /></label>
          </div>
          {oauthState && <label className="field"><span>Authorization code</span><input value={code} onChange={(e) => setCode(e.target.value)} placeholder="paste code (or code#state)" /></label>}
        </>
      )}
      {err && <div className="err">{err}</div>}
    </Modal>
  );
}

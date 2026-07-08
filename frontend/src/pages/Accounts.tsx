import { useEffect, useState } from 'react';
import { AccountDto, api, GroupDto, has, PoolStats, UserDto } from '../api';
import { Modal, Switch } from '../ui';

export function Accounts({ user }: { user: UserDto }) {
  const [stats, setStats] = useState<PoolStats | null>(null);
  const [groups, setGroups] = useState<GroupDto[]>([]);
  const [editing, setEditing] = useState<AccountDto | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const canManage = has(user, 'ACCOUNTS_MANAGE');

  async function load() {
    try {
      setStats(await api.accounts());
      setGroups(await api.groups());
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); }, []);

  if (err) return <div className="err">{err}</div>;
  if (!stats) return <div className="hint">Loading…</div>;

  return (
    <div className="main-inner">
      <h1>Accounts</h1>
      <p className="sub">Upstream Anthropic accounts, rotated by priority &amp; threshold.</p>

      {canManage && <Groups groups={groups} onChange={setGroups} onAccountsChange={setStats} />}
      {canManage && <AddByKey groups={groups} onDone={setStats} />}
      {canManage && <AddByOAuth groups={groups} onDone={setStats} />}

      <div className="section-head">
        <h2>Pool</h2>
        <button className="ghost sm" onClick={async () => setStats(await api.refreshAll())}>↻ Refresh all limits</button>
      </div>
      <div className="tablewrap">
        <table>
          <thead>
            <tr>
              <th>Prio</th><th>Name</th><th>Group</th><th>Type</th><th>Threshold</th><th>Coef</th>
              <th>On</th><th>Health</th>{canManage && <th></th>}
            </tr>
          </thead>
          <tbody>
            {stats.accounts.map((a) => (
              <AccountRow key={a.id} a={a} groups={groups} canManage={canManage} onChange={setStats} onEdit={() => setEditing(a)} />
            ))}
            {stats.accounts.length === 0 && <tr><td colSpan={9} className="hint">No accounts yet.</td></tr>}
          </tbody>
        </table>
      </div>

      {editing && (
        <AccountModal a={editing} groups={groups} onClose={() => setEditing(null)}
          onSaved={(s) => { setStats(s); setEditing(null); }} />
      )}
    </div>
  );
}

function AccountModal({ a, groups, onClose, onSaved }: { a: AccountDto; groups: GroupDto[]; onClose: () => void; onSaved: (s: PoolStats) => void }) {
  const [name, setName] = useState(a.name);
  const [prio, setPrio] = useState(a.priority);
  const [thr, setThr] = useState(a.threshold);
  const [coef, setCoef] = useState(a.coefficient);
  const [group, setGroup] = useState<number | null>(a.groupId);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function save() {
    setBusy(true); setErr(null);
    try {
      onSaved(await api.updateAccount(a.id, { name, priority: prio, threshold: thr, coefficient: coef, groupId: group, clearGroup: group == null }));
    } catch (e: any) { setErr(e.message); setBusy(false); }
  }

  return (
    <Modal title={`Edit ${a.name}`} onClose={onClose}
      footer={<><button className="ghost" onClick={onClose}>Cancel</button><button disabled={busy} onClick={save}>{busy ? '…' : 'Save'}</button></>}>
      <label className="field"><span>Name</span><input value={name} onChange={(e) => setName(e.target.value)} /></label>
      <div className="grid2">
        <label className="field"><span>Priority (lower = used first)</span><input type="number" value={prio} onChange={(e) => setPrio(Math.trunc(+e.target.value))} /></label>
        <label className="field"><span>Group</span><GroupSelect value={group} groups={groups} onChange={setGroup} /></label>
        <label className="field"><span>Threshold (0–1)</span><input type="number" step="0.05" min="0" max="1" value={thr} onChange={(e) => setThr(+e.target.value)} /></label>
        <label className="field"><span>Coefficient (×1 / ×5 / ×20)</span><input type="number" step="0.5" min="0" value={coef} onChange={(e) => setCoef(+e.target.value)} /></label>
      </div>
      <p className="hint">Type <b>{a.type.toLowerCase()}</b> · created {new Date(a.createdAt).toLocaleString()}</p>
      {err && <div className="err">{err}</div>}
    </Modal>
  );
}

function Groups({ groups, onChange, onAccountsChange }: { groups: GroupDto[]; onChange: (g: GroupDto[]) => void; onAccountsChange: (s: PoolStats) => void }) {
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

function GroupSelect({ value, groups, onChange }: { value: number | null; groups: GroupDto[]; onChange: (v: number | null) => void }) {
  return (
    <select value={value ?? ''} onChange={(e) => onChange(e.target.value ? +e.target.value : null)}>
      <option value="">— ungrouped —</option>
      {groups.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
    </select>
  );
}

function AccountRow({ a, groups, canManage, onChange, onEdit }: { a: AccountDto; groups: GroupDto[]; canManage: boolean; onChange: (s: PoolStats) => void; onEdit: () => void }) {
  async function toggle(v: boolean) { onChange(await api.updateAccount(a.id, { enabled: v })); }
  async function refresh() { onChange(await api.refreshOne(a.id)); }
  async function del() { if (confirm(`Delete account "${a.name}"?`)) { await api.deleteAccount(a.id); onChange(await api.accounts()); } }

  const gname = groups.find((g) => g.id === a.groupId)?.name;
  return (
    <tr>
      <td className="num" style={{ width: 56 }}>{a.priority}</td>
      <td><b>{a.name}</b></td>
      <td>{a.groupId ? <span className="grouptag">{gname ?? `#${a.groupId}`}</span> : <span className="hint">—</span>}</td>
      <td><span className="badge muted">{a.type.toLowerCase()}</span></td>
      <td className="num">{Math.round(a.threshold * 100)}%</td>
      <td className="num">×{a.coefficient}</td>
      <td>{canManage ? <Switch checked={a.enabled} onChange={toggle} /> : <span className={`badge ${a.enabled ? 'ok' : 'muted'}`}>{a.enabled ? 'on' : 'off'}</span>}</td>
      <td><span className={`badge ${a.health === 'OK' ? 'ok' : a.health === 'DEAD' ? 'bad' : 'warn'}`}>{a.health.toLowerCase().replace('_', ' ')}</span></td>
      {canManage && (
        <td>
          <div className="row">
            <button className="sm ghost" onClick={onEdit}>Edit</button>
            <button className="sm ghost" onClick={refresh} title="Refresh limits">↻</button>
            <button className="sm danger" onClick={del}>Delete</button>
          </div>
        </td>
      )}
    </tr>
  );
}

function AddByKey({ groups, onDone }: { groups: GroupDto[]; onDone: (s: PoolStats) => void }) {
  const [name, setName] = useState('');
  const [type, setType] = useState('API_KEY');
  const [groupId, setGroupId] = useState<number | null>(null);
  const [priority, setPriority] = useState(100);
  const [threshold, setThreshold] = useState(0.9);
  const [coefficient, setCoefficient] = useState(1);
  const [apiKey, setApiKey] = useState('');
  const [accessToken, setAccessToken] = useState('');
  const [refreshToken, setRefreshToken] = useState('');
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    setErr(null);
    try {
      const body: any = { name, type, groupId, priority, threshold, coefficient };
      if (type === 'API_KEY') body.apiKey = apiKey;
      else { body.accessToken = accessToken; if (refreshToken) body.refreshToken = refreshToken; }
      onDone(await api.createAccount(body));
      setName(''); setApiKey(''); setAccessToken(''); setRefreshToken('');
    } catch (e: any) { setErr(e.message); }
  }

  return (
    <div className="panel narrow">
      <h2 style={{ marginTop: 0 }}>Add account — by credential</h2>
      <label className="field"><span>Name</span><input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. max-account-1" /></label>
      <div className="grid2">
        <label className="field"><span>Type</span>
          <select value={type} onChange={(e) => setType(e.target.value)}>
            <option value="API_KEY">API key (x-api-key)</option>
            <option value="OAUTH">OAuth (access + refresh)</option>
            <option value="OAUTH_STATIC">OAuth (access only)</option>
          </select>
        </label>
        <label className="field"><span>Group</span><GroupSelect value={groupId} groups={groups} onChange={setGroupId} /></label>
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
      {err && <div className="err">{err}</div>}
      <button onClick={submit}>Add account</button>
    </div>
  );
}

function AddByOAuth({ groups, onDone }: { groups: GroupDto[]; onDone: (s: PoolStats) => void }) {
  const [state, setState] = useState<string | null>(null);
  const [url, setUrl] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [groupId, setGroupId] = useState<number | null>(null);
  const [priority, setPriority] = useState(100);
  const [coefficient, setCoefficient] = useState(5);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function start() {
    setErr(null);
    try { const r = await api.oauthStart(); setState(r.state); setUrl(r.authorizeUrl); window.open(r.authorizeUrl, '_blank'); }
    catch (e: any) { setErr(e.message); }
  }
  async function complete() {
    if (!state) return; setErr(null); setBusy(true);
    try { onDone(await api.oauthComplete({ state, code, name, groupId, priority, coefficient })); setState(null); setUrl(null); setCode(''); setName(''); }
    catch (e: any) { setErr(e.message); } finally { setBusy(false); }
  }

  return (
    <div className="panel narrow">
      <h2 style={{ marginTop: 0 }}>Add account — Login with Claude (OAuth)</h2>
      {!state ? (
        <>
          <p className="hint">Starts the same OAuth flow the Claude Code client uses. A tab opens; authorize, then paste the code back here.</p>
          <button onClick={start}>Start OAuth login</button>
          {err && <div className="err">{err}</div>}
        </>
      ) : (
        <>
          <p className="hint">If the tab didn't open: <a href={url!} target="_blank" rel="noreferrer">open authorize URL</a>. After approving, paste the returned code.</p>
          <label className="field"><span>Account name</span><input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. max-oauth-1" /></label>
          <div className="grid2">
            <label className="field"><span>Group</span><GroupSelect value={groupId} groups={groups} onChange={setGroupId} /></label>
            <label className="field"><span>Priority</span><input type="number" value={priority} onChange={(e) => setPriority(+e.target.value)} /></label>
            <label className="field"><span>Coefficient</span><input type="number" step="0.5" value={coefficient} onChange={(e) => setCoefficient(+e.target.value)} /></label>
          </div>
          <label className="field"><span>Authorization code</span><input value={code} onChange={(e) => setCode(e.target.value)} placeholder="paste code (or code#state)" /></label>
          {err && <div className="err">{err}</div>}
          <div className="row"><button disabled={busy} onClick={complete}>{busy ? '…' : 'Complete & add'}</button><button className="ghost" onClick={() => { setState(null); setUrl(null); }}>Cancel</button></div>
        </>
      )}
    </div>
  );
}

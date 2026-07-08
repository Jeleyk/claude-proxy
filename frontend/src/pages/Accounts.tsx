import { useEffect, useState } from 'react';
import { api, AccountDto, has, PoolStats, UserDto } from '../api';

export function Accounts({ user }: { user: UserDto }) {
  const [stats, setStats] = useState<PoolStats | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const canManage = has(user, 'ACCOUNTS_MANAGE');

  async function load() {
    try { setStats(await api.accounts()); } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); }, []);

  if (err) return <div className="err">{err}</div>;
  if (!stats) return <div>Loading…</div>;

  return (
    <div>
      <h1>Accounts</h1>
      <p className="sub">Upstream Anthropic accounts, rotated by priority &amp; threshold.</p>

      {canManage && <AddByKey onDone={setStats} />}
      {canManage && <AddByOAuth onDone={setStats} />}

      <h2>Pool</h2>
      <table>
        <thead>
          <tr>
            <th>Prio</th><th>Name</th><th>Type</th><th>Threshold</th><th>Coef</th>
            <th>Enabled</th><th>Health</th>{canManage && <th></th>}
          </tr>
        </thead>
        <tbody>
          {stats.accounts.map((a) => (
            <AccountRow key={a.id} a={a} canManage={canManage} onChange={setStats} />
          ))}
          {stats.accounts.length === 0 && (
            <tr><td colSpan={8} className="hint">No accounts yet.</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

function AccountRow({ a, canManage, onChange }: { a: AccountDto; canManage: boolean; onChange: (s: PoolStats) => void }) {
  const [prio, setPrio] = useState(a.priority);
  const [thr, setThr] = useState(a.threshold);
  const [coef, setCoef] = useState(a.coefficient);
  const [dirty, setDirty] = useState(false);

  async function save() {
    const s = await api.updateAccount(a.id, { priority: prio, threshold: thr, coefficient: coef });
    onChange(s); setDirty(false);
  }
  async function toggle() {
    onChange(await api.updateAccount(a.id, { enabled: !a.enabled }));
  }
  async function del() {
    if (!confirm(`Delete account "${a.name}"?`)) return;
    await api.deleteAccount(a.id);
    onChange(await api.accounts());
  }
  const upd = (fn: () => void) => { fn(); setDirty(true); };

  return (
    <tr>
      <td style={{ width: 70 }}>
        {canManage
          ? <input className="num" type="number" value={prio} onChange={(e) => upd(() => setPrio(+e.target.value))} />
          : <span className="num">{a.priority}</span>}
      </td>
      <td>{a.name}</td>
      <td><span className="badge muted">{a.type.toLowerCase()}</span></td>
      <td style={{ width: 90 }}>
        {canManage
          ? <input className="num" type="number" step="0.05" min="0" max="1" value={thr} onChange={(e) => upd(() => setThr(+e.target.value))} />
          : <span className="num">{a.threshold}</span>}
      </td>
      <td style={{ width: 80 }}>
        {canManage
          ? <input className="num" type="number" step="0.5" min="0" value={coef} onChange={(e) => upd(() => setCoef(+e.target.value))} />
          : <span className="num">×{a.coefficient}</span>}
      </td>
      <td>
        <span className={`badge ${a.enabled ? 'ok' : 'muted'}`}>{a.enabled ? 'on' : 'off'}</span>
      </td>
      <td><span className={`badge ${a.health === 'OK' ? 'ok' : a.health === 'DEAD' ? 'bad' : 'warn'}`}>{a.health.toLowerCase().replace('_', ' ')}</span></td>
      {canManage && (
        <td>
          <div className="row">
            {dirty && <button className="sm" onClick={save}>Save</button>}
            <button className="sm ghost" onClick={toggle}>{a.enabled ? 'Disable' : 'Enable'}</button>
            <button className="sm danger" onClick={del}>Delete</button>
          </div>
        </td>
      )}
    </tr>
  );
}

function AddByKey({ onDone }: { onDone: (s: PoolStats) => void }) {
  const [name, setName] = useState('');
  const [type, setType] = useState('API_KEY');
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
      const body: any = { name, type, priority, threshold, coefficient };
      if (type === 'API_KEY') body.apiKey = apiKey;
      else { body.accessToken = accessToken; if (refreshToken) body.refreshToken = refreshToken; }
      onDone(await api.createAccount(body));
      setName(''); setApiKey(''); setAccessToken(''); setRefreshToken('');
    } catch (e: any) { setErr(e.message); }
  }

  return (
    <div className="panel">
      <h2 style={{ marginTop: 0 }}>Add account — by credential</h2>
      <label className="field"><span>Name</span>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. max-account-1" /></label>
      <div className="grid2">
        <label className="field"><span>Type</span>
          <select value={type} onChange={(e) => setType(e.target.value)}>
            <option value="API_KEY">API key (x-api-key)</option>
            <option value="OAUTH">OAuth (access + refresh)</option>
            <option value="OAUTH_STATIC">OAuth (access only)</option>
          </select>
        </label>
        <label className="field"><span>Priority (lower = first)</span>
          <input type="number" value={priority} onChange={(e) => setPriority(+e.target.value)} /></label>
        <label className="field"><span>Threshold (0–1)</span>
          <input type="number" step="0.05" value={threshold} onChange={(e) => setThreshold(+e.target.value)} /></label>
        <label className="field"><span>Coefficient (×1 / ×5 / ×20)</span>
          <input type="number" step="0.5" value={coefficient} onChange={(e) => setCoefficient(+e.target.value)} /></label>
      </div>
      {type === 'API_KEY'
        ? <label className="field"><span>API key</span>
            <input value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="sk-ant-api03-…" /></label>
        : <>
            <label className="field"><span>Access token</span>
              <input value={accessToken} onChange={(e) => setAccessToken(e.target.value)} placeholder="sk-ant-oat01-…" /></label>
            {type === 'OAUTH' && <label className="field"><span>Refresh token</span>
              <input value={refreshToken} onChange={(e) => setRefreshToken(e.target.value)} placeholder="sk-ant-ort01-…" /></label>}
          </>}
      {err && <div className="err">{err}</div>}
      <button onClick={submit}>Add account</button>
    </div>
  );
}

function AddByOAuth({ onDone }: { onDone: (s: PoolStats) => void }) {
  const [state, setState] = useState<string | null>(null);
  const [url, setUrl] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [priority, setPriority] = useState(100);
  const [coefficient, setCoefficient] = useState(5);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function start() {
    setErr(null);
    try {
      const r = await api.oauthStart();
      setState(r.state); setUrl(r.authorizeUrl);
      window.open(r.authorizeUrl, '_blank');
    } catch (e: any) { setErr(e.message); }
  }
  async function complete() {
    if (!state) return;
    setErr(null); setBusy(true);
    try {
      onDone(await api.oauthComplete({ state, code, name, priority, coefficient }));
      setState(null); setUrl(null); setCode(''); setName('');
    } catch (e: any) { setErr(e.message); } finally { setBusy(false); }
  }

  return (
    <div className="panel">
      <h2 style={{ marginTop: 0 }}>Add account — Login with Claude (OAuth)</h2>
      {!state ? (
        <>
          <p className="hint">Starts the same OAuth flow the Claude Code client uses. A browser tab opens; authorize, then paste the code back here.</p>
          <button onClick={start}>Start OAuth login</button>
        </>
      ) : (
        <>
          <p className="hint">If the tab didn't open: <a href={url!} target="_blank" rel="noreferrer">open authorize URL</a>. After approving, paste the returned code below.</p>
          <label className="field"><span>Account name</span>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. max-oauth-1" /></label>
          <div className="grid2">
            <label className="field"><span>Priority</span>
              <input type="number" value={priority} onChange={(e) => setPriority(+e.target.value)} /></label>
            <label className="field"><span>Coefficient</span>
              <input type="number" step="0.5" value={coefficient} onChange={(e) => setCoefficient(+e.target.value)} /></label>
          </div>
          <label className="field"><span>Authorization code</span>
            <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="paste code (or code#state)" /></label>
          {err && <div className="err">{err}</div>}
          <div className="row">
            <button disabled={busy} onClick={complete}>{busy ? '…' : 'Complete & add'}</button>
            <button className="ghost" onClick={() => { setState(null); setUrl(null); }}>Cancel</button>
          </div>
        </>
      )}
      {!state && err && <div className="err">{err}</div>}
    </div>
  );
}

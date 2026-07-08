import { useEffect, useState } from 'react';
import { api, ProxyTokenDto } from '../api';

export function Tokens() {
  const [tokens, setTokens] = useState<ProxyTokenDto[]>([]);
  const [name, setName] = useState('');
  const [revealed, setRevealed] = useState<ProxyTokenDto | null>(null);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    try { setTokens(await api.tokens()); } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); }, []);

  async function create() {
    setErr(null);
    try {
      const t = await api.createToken(name || 'token');
      setRevealed(t); setName(''); load();
    } catch (e: any) { setErr(e.message); }
  }
  async function del(id: number) {
    if (!confirm('Delete this token? Clients using it will stop working.')) return;
    await api.deleteToken(id); load();
  }

  const base = window.location.origin;

  return (
    <div>
      <h1>Proxy Tokens</h1>
      <p className="sub">Tokens you put into Claude Code to route through this proxy.</p>

      <div className="panel">
        <h2 style={{ marginTop: 0 }}>Connect Claude Code</h2>
        <p className="hint">Set these environment variables, then run <span className="mono">claude</span>:</p>
        <div className="tokenreveal mono">
          export ANTHROPIC_BASE_URL={base}<br />
          export ANTHROPIC_AUTH_TOKEN=&lt;your-proxy-token&gt;
        </div>
      </div>

      <div className="panel">
        <h2 style={{ marginTop: 0 }}>Create token</h2>
        <div className="row">
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="token name (e.g. laptop)" />
          <button onClick={create}>Create</button>
        </div>
        {err && <div className="err">{err}</div>}
        {revealed?.token && (
          <div className="tokenreveal">
            <div className="hint">Copy it now — it won't be shown again:</div>
            <div className="mono"><b>{revealed.token}</b></div>
          </div>
        )}
      </div>

      <h2>Your tokens</h2>
      <table>
        <thead><tr><th>Name</th><th>Created</th><th>Last used</th><th></th></tr></thead>
        <tbody>
          {tokens.map((t) => (
            <tr key={t.id}>
              <td>{t.name}</td>
              <td className="hint">{new Date(t.createdAt).toLocaleString()}</td>
              <td className="hint">{t.lastUsedAt ? new Date(t.lastUsedAt).toLocaleString() : 'never'}</td>
              <td><button className="sm danger" onClick={() => del(t.id)}>Delete</button></td>
            </tr>
          ))}
          {tokens.length === 0 && <tr><td colSpan={4} className="hint">No tokens yet.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

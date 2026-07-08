import { useEffect, useState } from 'react';
import { api, fmtTokens, ProxyTokenDto, UserDto } from '../api';
import { CodeBlock, Copy } from '../ui';

export function Tokens() {
  const [tokens, setTokens] = useState<ProxyTokenDto[]>([]);
  const [name, setName] = useState('');
  const [revealed, setRevealed] = useState<ProxyTokenDto | null>(null);
  const [base, setBase] = useState(window.location.origin);
  const [me, setMe] = useState<UserDto | null>(null);
  const [tpp, setTpp] = useState(10000);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    try {
      setTokens(await api.tokens());
      const c = await api.config().catch(() => ({ publicBaseUrl: '', tokensPerWindowPercent: 10000 }));
      if (c.publicBaseUrl) setBase(c.publicBaseUrl);
      setTpp(c.tokensPerWindowPercent);
      setMe(await api.me().catch(() => null));
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); const t = setInterval(load, 10000); return () => clearInterval(t); }, []);

  async function create() {
    setErr(null);
    try { const t = await api.createToken(name || 'token'); setRevealed(t); setName(''); load(); }
    catch (e: any) { setErr(e.message); }
  }
  async function del(id: number) {
    if (!confirm('Delete this token? Clients using it stop working.')) return;
    await api.deleteToken(id); load();
  }

  const tok = revealed?.token;
  const exportBase = `export ANTHROPIC_BASE_URL=${base}`;
  const exportTok = (t: string) => `export ANTHROPIC_AUTH_TOKEN=${t}`;
  const both = (t: string) => `${exportBase}\n${exportTok(t)}`;
  const wrapper = (t: string) =>
    `mkdir -p ~/.local/bin && cat > ~/.local/bin/claude-proxy <<'EOF'\n` +
    `#!/usr/bin/env bash\n` +
    `ANTHROPIC_BASE_URL="${base}" ANTHROPIC_AUTH_TOKEN="${t}" exec claude "$@"\n` +
    `EOF\n` +
    `chmod +x ~/.local/bin/claude-proxy && echo 'Installed. Run: claude-proxy [claude args]'`;

  return (
    <div className="main-inner">
      <h1>Proxy Tokens</h1>
      <p className="sub">Tokens you put into Claude Code to route through this proxy.</p>

      {me && (
        <div className="panel narrow">
          <h2 style={{ marginTop: 0 }}>Your usage today</h2>
          {me.dailyTokenLimit == null ? (
            <p style={{ margin: 0 }}>
              <b>{fmtTokens(me.todayTokens)}</b> tokens today <span className="hint">≈ {(me.todayTokens / tpp).toFixed(1)}% of a normal window · no daily limit</span>
            </p>
          ) : (
            <>
              <div className="row" style={{ justifyContent: 'space-between', marginBottom: 6 }}>
                <span><b>{fmtTokens(me.todayTokens)}</b> / {fmtTokens(me.dailyTokenLimit)} tokens</span>
                <span className="hint">≈ {(me.todayTokens / tpp).toFixed(1)}% / {(me.dailyTokenLimit / tpp).toFixed(1)}% window · resets 00:00 UTC</span>
              </div>
              <div className="bar"><span style={{ width: `${Math.min(100, Math.round((me.todayTokens / me.dailyTokenLimit) * 100))}%` }} /></div>
            </>
          )}
        </div>
      )}

      <div className="panel narrow">
        <h2 style={{ marginTop: 0 }}>Connect Claude Code</h2>
        <p className="hint">Proxy base URL:</p>
        <CodeBlock text={exportBase} />
        <p className="hint">Then set your token and run <span className="mono">claude</span>:</p>
        <CodeBlock text={`export ANTHROPIC_AUTH_TOKEN=<your-proxy-token>\nclaude`} />
      </div>

      <div className="panel narrow">
        <h2 style={{ marginTop: 0 }}>Create token</h2>
        <div className="row">
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="token name (e.g. laptop)" onKeyDown={(e) => e.key === 'Enter' && create()} />
          <button onClick={create}>Create</button>
        </div>
        {err && <div className="err">{err}</div>}
        {tok && (
          <div className="tokenreveal">
            <div className="row" style={{ justifyContent: 'space-between' }}>
              <b>New token — copy it now, it won't be shown again</b>
              <Copy text={tok} label="Copy token" />
            </div>
            <div className="mono" style={{ margin: '8px 0', wordBreak: 'break-all' }}>{tok}</div>

            <p className="hint" style={{ marginBottom: 4 }}>Ready-to-paste export (both lines):</p>
            <CodeBlock text={both(tok)} />

            <p className="hint" style={{ marginBottom: 4 }}>Or install a <span className="mono">claude-proxy</span> wrapper command (paste once, then run <span className="mono">claude-proxy [args]</span>):</p>
            <CodeBlock text={wrapper(tok)} />
          </div>
        )}
      </div>

      <h2>Your tokens</h2>
      <div className="tablewrap">
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
    </div>
  );
}

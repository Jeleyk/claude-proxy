import { useEffect, useState } from 'react';
import { api, fmtTokens, fmtUsd, ProxyTokenDto, UserDto } from '../api';
import { ConnectScripts, Copy } from '../ui';
import { TokenUsageSection } from './tokenUsage';

export function Tokens() {
  const [tokens, setTokens] = useState<ProxyTokenDto[]>([]);
  const [name, setName] = useState('');
  const [revealed, setRevealed] = useState<ProxyTokenDto | null>(null);
  // The datapath is fronted by nginx under /gateway (Claude Code appends /v1/...).
  const [base, setBase] = useState(window.location.origin + '/gateway');
  const [me, setMe] = useState<UserDto | null>(null);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    try {
      setTokens(await api.tokens());
      const c = await api.config().catch(() => ({ publicBaseUrl: '' }));
      if (c.publicBaseUrl) setBase(c.publicBaseUrl + '/gateway');
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
  async function toggle(id: number, enabled: boolean) {
    setErr(null);
    try { setTokens(await api.setTokenEnabled(id, enabled)); }
    catch (e: any) { setErr(e.message); }
  }

  const tok = revealed?.token;

  return (
    <div className="main-inner">
      <h1>Proxy Tokens</h1>
      <p className="sub">Tokens you put into Claude Code to route through this proxy.</p>

      {me && (
        <div className="panel narrow">
          <h2 style={{ marginTop: 0 }}>Your usage today</h2>
          <p className="hint" style={{ marginTop: -6 }}>{fmtTokens(me.todayInputTokens)} in / {fmtTokens(me.todayOutputTokens)} out</p>
          {me.dailyCostLimit == null ? (
            <p style={{ margin: 0 }}><b>{fmtUsd(me.todayCost)}</b> spent today <span className="hint">· no daily limit</span></p>
          ) : (
            <>
              <div className="row" style={{ justifyContent: 'space-between', marginBottom: 6 }}>
                <span><b>{fmtUsd(me.todayCost)}</b> / {fmtUsd(me.dailyCostLimit)} spent today</span>
                <span className="hint">resets 00:00 UTC</span>
              </div>
              <div className="bar"><span style={{ width: `${Math.min(100, Math.round((me.todayCost / me.dailyCostLimit) * 100))}%` }} /></div>
            </>
          )}
        </div>
      )}

      {/* Create + connect in one card: the snippets below already carry the token you just made,
          so there is nothing to swap in by hand. Before that they show a placeholder. */}
      <div className="panel narrow">
        <h2 style={{ marginTop: 0 }}>Connect Claude Code</h2>
        <p className="hint" style={{ marginTop: -6 }}>
          Create a token, then paste one of the snippets below — they're filled in with it automatically.
        </p>
        <div className="row">
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="token name (e.g. laptop)" onKeyDown={(e) => e.key === 'Enter' && create()} />
          <button onClick={create}>Create token</button>
        </div>
        {err && <div className="err">{err}</div>}
        {tok && (
          <div className="tokenreveal">
            <div className="row" style={{ justifyContent: 'space-between' }}>
              <b>New token — copy it now, it won't be shown again</b>
              <Copy text={tok} label="Copy token" />
            </div>
            <div className="mono" style={{ marginTop: 8, wordBreak: 'break-all' }}>{tok}</div>
          </div>
        )}
        <div style={{ marginTop: 14 }}>
          <ConnectScripts base={base} token={tok ?? '<your-proxy-token>'} wrapper />
        </div>
      </div>

      <TokenUsageSection source="proxy" tokens={tokens} onDelete={del} onToggle={toggle} emptyHint="No tokens yet." />
    </div>
  );
}

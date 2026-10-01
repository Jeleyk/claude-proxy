import { useEffect, useState } from 'react';
import { api, fmtTokens, fmtUntilUtcMidnight, fmtUsd, ProxyTokenDto, UserDto } from '../api';
import { ConnectScripts, Copy, Modal, Switch } from '../ui';
import { TokenUsageSection } from './tokenUsage';

export function Tokens() {
  const [tokens, setTokens] = useState<ProxyTokenDto[]>([]);
  const [name, setName] = useState('');
  const [revealed, setRevealed] = useState<ProxyTokenDto | null>(null);
  const [editing, setEditing] = useState<ProxyTokenDto | null>(null);
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
                {/* the limit runs on UTC days regardless of your clock — say when it lifts */}
                <span className="hint">resets in {fmtUntilUtcMidnight()} · 00:00 UTC</span>
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

      <TokenUsageSection source="proxy" tokens={tokens} onDelete={del} onToggle={toggle}
        onEdit={setEditing} emptyHint="No tokens yet." />

      {editing && (
        <ProxyTokenModal token={tokens.find((t) => t.id === editing.id) ?? editing}
          onClose={() => setEditing(null)} onChanged={load} />
      )}
    </div>
  );
}

// Suggestions only — the field takes any model id, so a new model needs no release.
const MODEL_SUGGESTIONS = [
  'claude-opus-5-5[1m]', 'claude-opus-5-5', 'claude-opus-5[1m]', 'claude-opus-5',
  'claude-fable-5-1', 'claude-sonnet-5[1m]', 'claude-sonnet-5', 'claude-haiku-4-5-20251001',
];

/**
 * One proxy token's settings: the on/off switch and the default model. The model is written over
 * whatever the client asks for on every request — Claude Code's background Haiku calls included —
 * and takes Claude Code's own "[1m]" suffix to switch on the 1M-context window.
 */
function ProxyTokenModal({ token, onClose, onChanged }: {
  token: ProxyTokenDto; onClose: () => void; onChanged: () => void;
}) {
  const [model, setModel] = useState(token.defaultModel ?? '');
  const [dirty, setDirty] = useState(false);
  const [saved, setSaved] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  // A background refresh may bring a newer value; never clobber an edit in progress.
  useEffect(() => { if (!dirty) setModel(token.defaultModel ?? ''); }, [token.defaultModel, dirty]);

  async function save(value: string | null) {
    setErr(null);
    try {
      await api.updateTokenModel(token.id, value);
      setDirty(false); setSaved(true); setTimeout(() => setSaved(false), 1500);
      onChanged();
    } catch (e: any) { setErr(e.message); }
  }
  async function setEnabled(v: boolean) {
    setErr(null);
    try { await api.setTokenEnabled(token.id, v); onChanged(); }
    catch (e: any) { setErr(e.message); }
  }

  return (
    <Modal title={`Token · ${token.name}`} onClose={onClose} width={560}
      footer={
        <>
          {saved && <span className="hint" style={{ marginRight: 'auto' }}>Saved ✓</span>}
          {token.defaultModel && <button className="ghost" onClick={() => { setModel(''); save(null); }}>Clear model</button>}
          <button onClick={() => save(model.trim() || null)} disabled={!dirty}>Save</button>
          <button className="ghost" onClick={onClose}>Close</button>
        </>
      }>
      {err && <div className="err">{err}</div>}

      <div className="row" style={{ justifyContent: 'space-between' }}>
        <div>
          <b>Enabled</b>
          <p className="hint" style={{ margin: '2px 0 0' }}>
            Off = clients using this token get 401 immediately, without revoking it.
          </p>
        </div>
        <Switch checked={token.enabled} onChange={setEnabled} />
      </div>

      <h3 style={{ margin: '20px 0 4px', fontSize: 14 }}>Default model</h3>
      <p className="hint" style={{ margin: '0 0 8px' }}>
        Replaces the model of every request made with this token, whatever the client picked.
        Add <span className="mono">[1m]</span> for the 1M-token context window. Empty = the client decides.
      </p>
      <input
        className="mono" list="proxy-token-models" value={model} style={{ width: '100%' }}
        onChange={(e) => { setModel(e.target.value); setDirty(true); }}
        onKeyDown={(e) => e.key === 'Enter' && dirty && save(model.trim() || null)}
        placeholder="e.g. claude-opus-5-5[1m]"
      />
      <datalist id="proxy-token-models">
        {MODEL_SUGGESTIONS.map((m) => <option key={m} value={m} />)}
      </datalist>
    </Modal>
  );
}

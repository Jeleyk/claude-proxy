import { useEffect, useState } from 'react';
import { api, fmtUsd, ProxyTokenDto, UserDto } from '../api';
import { CodeBlock, Copy, Modal, Segmented, Switch } from '../ui';
import { TokenUsageSection } from './tokenUsage';

// The routing gateways are fronted by nginx: OpenAI at /routing/openai (SDK appends /v1/...),
// Anthropic at /routing/anthropic (SDK appends /v1/messages).
function bases(origin: string) {
  return {
    openai: origin + '/routing/openai/v1',
    anthropic: origin + '/routing/anthropic',
  };
}

const MODEL = 'claude-sonnet-5';

function openaiPython(base: string, token: string) {
  return `from openai import OpenAI\n` +
    `client = OpenAI(base_url="${base}", api_key="${token}")\n` +
    `resp = client.chat.completions.create(\n` +
    `    model="${MODEL}",\n` +
    `    messages=[{"role": "user", "content": "Hello"}],\n` +
    `)\n` +
    `print(resp.choices[0].message.content)`;
}
function openaiCurl(base: string, token: string) {
  return `curl ${base}/chat/completions \\\n` +
    `  -H "Authorization: Bearer ${token}" \\\n` +
    `  -H "Content-Type: application/json" \\\n` +
    `  -d '{"model":"${MODEL}","messages":[{"role":"user","content":"Hello"}]}'`;
}
function anthropicPython(base: string, token: string) {
  return `from anthropic import Anthropic\n` +
    `client = Anthropic(base_url="${base}", api_key="${token}")\n` +
    `msg = client.messages.create(\n` +
    `    model="${MODEL}", max_tokens=256,\n` +
    `    messages=[{"role": "user", "content": "Hello"}],\n` +
    `)\n` +
    `print(msg.content[0].text)`;
}
function anthropicCurl(base: string, token: string) {
  return `curl ${base}/v1/messages \\\n` +
    `  -H "x-api-key: ${token}" \\\n` +
    `  -H "anthropic-version: 2023-06-01" \\\n` +
    `  -H "content-type: application/json" \\\n` +
    `  -d '{"model":"${MODEL}","max_tokens":256,"messages":[{"role":"user","content":"Hello"}]}'`;
}

export function RoutingTokens() {
  const [tokens, setTokens] = useState<ProxyTokenDto[]>([]);
  const [name, setName] = useState('');
  const [revealed, setRevealed] = useState<ProxyTokenDto | null>(null);
  const [editing, setEditing] = useState<ProxyTokenDto | null>(null);
  const [origin, setOrigin] = useState(window.location.origin);
  const [provider, setProvider] = useState<'openai' | 'anthropic'>('openai');
  const [lang, setLang] = useState<'python' | 'curl'>('python');
  const [me, setMe] = useState<UserDto | null>(null);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    try {
      setTokens(await api.routingTokens());
      const c = await api.config().catch(() => ({ publicBaseUrl: '' }));
      if (c.publicBaseUrl) setOrigin(c.publicBaseUrl);
      setMe(await api.me().catch(() => null));
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); const t = setInterval(load, 10000); return () => clearInterval(t); }, []);

  // Creation only takes a name — the static system prompt is set afterwards, in the token's
  // Edit dialog, so the create box stays a one-liner.
  async function create() {
    setErr(null);
    try {
      const t = await api.createRoutingToken(name || 'token');
      setRevealed(t); setName(''); load();
    } catch (e: any) { setErr(e.message); }
  }
  async function del(id: number) {
    if (!confirm('Delete this routing token? Clients using it stop working.')) return;
    await api.deleteRoutingToken(id); load();
  }
  async function toggle(id: number, enabled: boolean) {
    setErr(null);
    try { setTokens(await api.setRoutingTokenEnabled(id, enabled)); }
    catch (e: any) { setErr(e.message); }
  }

  const b = bases(origin);
  const base = provider === 'openai' ? b.openai : b.anthropic;
  const tok = revealed?.token;
  const snippet = (t: string) =>
    provider === 'openai'
      ? (lang === 'python' ? openaiPython(base, t) : openaiCurl(base, t))
      : (lang === 'python' ? anthropicPython(base, t) : anthropicCurl(base, t));

  return (
    <div className="main-inner">
      <h1>API Routing</h1>
      <p className="sub">Use the OpenAI &amp; Anthropic APIs backed by this proxy's Claude accounts. Point any OpenAI/Anthropic SDK at the base URL below with a routing token.</p>

      {me && (
        <div className="panel narrow">
          <h2 style={{ marginTop: 0 }}>Your routing usage today</h2>
          {me.dailyRoutingCostLimit == null ? (
            <p style={{ margin: 0 }}><b>{fmtUsd(me.todayRoutingCost)}</b> spent today <span className="hint">· no daily routing limit</span></p>
          ) : (
            <>
              <div className="row" style={{ justifyContent: 'space-between', marginBottom: 6 }}>
                <span><b>{fmtUsd(me.todayRoutingCost)}</b> / {fmtUsd(me.dailyRoutingCostLimit)} spent today</span>
                <span className="hint">resets 00:00 UTC</span>
              </div>
              <div className="bar"><span style={{ width: `${Math.min(100, Math.round((me.todayRoutingCost / me.dailyRoutingCostLimit) * 100))}%` }} /></div>
            </>
          )}
        </div>
      )}

      {/* Create + connect in one card: the example below is filled in with the token you just
          created, so there is nothing to paste in by hand. */}
      <div className="panel narrow">
        <h2 style={{ marginTop: 0 }}>Endpoints</h2>
        <div className="connect-tabs">
          <Segmented<'openai' | 'anthropic'> value={provider} onChange={setProvider} options={[
            { value: 'openai', label: 'OpenAI' },
            { value: 'anthropic', label: 'Anthropic' },
          ]} />
          <Segmented<'python' | 'curl'> value={lang} onChange={setLang} options={[
            { value: 'python', label: 'Python' },
            { value: 'curl', label: 'curl' },
          ]} />
        </div>
        <div className="row" style={{ justifyContent: 'space-between', margin: '10px 0 4px' }}>
          <span className="hint">Base URL</span>
          <Copy text={base} label="Copy base URL" />
        </div>
        <div className="mono" style={{ wordBreak: 'break-all' }}>{base}</div>

        <div className="row" style={{ marginTop: 14 }}>
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="token name (e.g. my-app)" onKeyDown={(e) => e.key === 'Enter' && create()} />
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
        <p className="hint" style={{ marginBottom: 4, marginTop: 12 }}>
          {tok ? 'Ready to run — your new token is already in it:' : 'Example (a created token drops straight in here):'}
        </p>
        <CodeBlock text={snippet(tok ?? '<your-routing-token>')} />
      </div>

      <TokenUsageSection source="routing" tokens={tokens} onDelete={del} onToggle={toggle}
        onEdit={setEditing} emptyHint="No routing tokens yet." />

      {editing && (
        <RoutingTokenModal token={tokens.find((t) => t.id === editing.id) ?? editing}
          onClose={() => setEditing(null)} onChanged={load} />
      )}
    </div>
  );
}

/**
 * Everything configurable about one routing token: the on/off switch and the static system
 * prompt, which the routing gateways inject right after the mandatory Claude Code block — ahead
 * of (higher priority than) any system prompt the API request itself carries.
 *
 * The prompt saves explicitly (it's a text edit); the switch applies immediately, since it's
 * reversible and its whole point is cutting a token off fast.
 */
function RoutingTokenModal({ token, onClose, onChanged }: {
  token: ProxyTokenDto; onClose: () => void; onChanged: () => void;
}) {
  const [text, setText] = useState(token.systemPrompt ?? '');
  const [dirty, setDirty] = useState(false);
  const [saved, setSaved] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  // A background refresh may bring a newer prompt; never clobber an edit in progress.
  useEffect(() => { if (!dirty) setText(token.systemPrompt ?? ''); }, [token.systemPrompt, dirty]);

  async function save(value: string | null) {
    setErr(null);
    try {
      await api.updateRoutingTokenPrompt(token.id, value);
      setDirty(false); setSaved(true); setTimeout(() => setSaved(false), 1500);
      onChanged();
    } catch (e: any) { setErr(e.message); }
  }
  async function setEnabled(v: boolean) {
    setErr(null);
    try { await api.setRoutingTokenEnabled(token.id, v); onChanged(); }
    catch (e: any) { setErr(e.message); }
  }

  return (
    <Modal title={`Token · ${token.name}`} onClose={onClose} width={560}
      footer={
        <>
          {saved && <span className="hint" style={{ marginRight: 'auto' }}>Saved ✓</span>}
          {token.systemPrompt && <button className="ghost" onClick={() => { setText(''); save(null); }}>Clear prompt</button>}
          <button onClick={() => save(text.trim() || null)} disabled={!dirty}>Save</button>
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

      <h3 style={{ margin: '20px 0 4px', fontSize: 14 }}>Static system prompt</h3>
      <p className="hint" style={{ margin: '0 0 8px' }}>
        Injected on every request of this token, ahead of any system prompt the API request carries.
      </p>
      <textarea
        value={text} rows={6}
        onChange={(e) => { setText(e.target.value); setDirty(true); }}
        placeholder="e.g. Always answer in Russian. Never reveal internal tooling."
        style={{ width: '100%', resize: 'vertical' }}
      />
    </Modal>
  );
}

import { useEffect, useState } from 'react';
import { api, fmtUntilUtcMidnight, fmtUsd, ProxyTokenDto, UserDto } from '../api';
import { CodeBlock, Copy, Modal, Segmented, Switch } from '../ui';
import { TokenUsageSection } from './tokenUsage';

// Keep Claude's compatibility endpoint distinct from the native OpenAI account pool.
function bases(origin: string) {
  return {
    openai: origin + '/routing/openai/v1',
    anthropic: origin + '/routing/anthropic',
    native: origin + '/openai/v1',
  };
}

const MODEL = 'claude-sonnet-5';
const OPENAI_MODEL = '<openai-model>';

function nativePython(base: string, token: string) {
  return `from openai import OpenAI\n` +
    `client = OpenAI(base_url="${base}", api_key="${token}")\n` +
    `# Choose a model from client.models.list()\n` +
    `resp = client.responses.create(\n` +
    `    model="${OPENAI_MODEL}", input="Hello", store=False,\n` +
    `)\nprint(resp.output_text)`;
}
function nativeCurl(base: string, token: string) {
  return `# List models first: GET ${base}/models\n` +
    `curl ${base}/responses \\\n` +
    `  -H "Authorization: Bearer ${token}" \\\n` +
    `  -H "Content-Type: application/json" \\\n` +
    `  -d '{"model":"${OPENAI_MODEL}","input":"Hello","store":false}'`;
}
function nativeCodex(base: string) {
  return `# Set OPENAI_PROXY_TOKEN to a routing token in your shell.\n` +
    `# Add to ~/.codex/config.toml (choose a model from /models):\n` +
    `model_provider = "proxy_openai"\nmodel = "${OPENAI_MODEL}"\n\n` +
    `[model_providers.proxy_openai]\nname = "OpenAI via proxy"\n` +
    `base_url = "${base}"\nenv_key = "OPENAI_PROXY_TOKEN"\n` +
    `wire_api = "responses"\nrequires_openai_auth = false\nsupports_websockets = false`;
}

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
  const [provider, setProvider] = useState<'openai' | 'anthropic' | 'native'>('openai');
  const [lang, setLang] = useState<'python' | 'curl' | 'codex'>('python');
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
  const base = b[provider];
  const tok = revealed?.token;
  const snippet = (t: string) =>
    provider === 'native'
      ? (lang === 'codex' ? nativeCodex(base) : lang === 'python' ? nativePython(base, t) : nativeCurl(base, t))
      : provider === 'openai'
      ? (lang === 'python' ? openaiPython(base, t) : openaiCurl(base, t))
      : (lang === 'python' ? anthropicPython(base, t) : anthropicCurl(base, t));

  return (
    <div className="main-inner">
      <h1>API Routing</h1>
      <p className="sub">Connect with a routing token. Claude endpoints use Anthropic accounts; native OpenAI Responses uses OpenAI / Codex accounts.</p>

      {me && (
        <div className="panel narrow">
          <h2 style={{ marginTop: 0 }}>Your routing usage today</h2>
          {me.dailyRoutingCostLimit == null ? (
            <p style={{ margin: 0 }}><b>{fmtUsd(me.todayRoutingCost)}</b> spent today <span className="hint">· no daily routing limit</span></p>
          ) : (
            <>
              <div className="row" style={{ justifyContent: 'space-between', marginBottom: 6 }}>
                <span><b>{fmtUsd(me.todayRoutingCost)}</b> / {fmtUsd(me.dailyRoutingCostLimit)} spent today</span>
                {/* the limit runs on UTC days regardless of your clock — say when it lifts */}
                <span className="hint">resets in {fmtUntilUtcMidnight()} · 00:00 UTC</span>
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
          <Segmented<'openai' | 'anthropic' | 'native'> value={provider} onChange={(p) => { setProvider(p); if (p !== 'native' && lang === 'codex') setLang('python'); }} options={[
            { value: 'openai', label: 'Claude · OpenAI format' },
            { value: 'anthropic', label: 'Claude · Anthropic format' },
            { value: 'native', label: 'Native OpenAI / Codex' },
          ]} />
          <Segmented<'python' | 'curl' | 'codex'> value={lang} onChange={setLang} options={[
            { value: 'python', label: 'Python' },
            { value: 'curl', label: 'curl' },
            ...(provider === 'native' ? [{ value: 'codex' as const, label: 'Codex CLI' }] : []),
          ]} />
        </div>
        <div className="row" style={{ justifyContent: 'space-between', margin: '10px 0 4px' }}>
          <span className="hint">Base URL</span>
          <Copy text={base} label="Copy base URL" />
        </div>
        <div className="mono" style={{ wordBreak: 'break-all' }}>{base}</div>
        {provider === 'native' && <>
          <p className="hint">Responses API over HTTP / SSE. Send the full input history on each request. Stored responses, previous_response_id, background mode and WebSockets are not supported.</p>
          <p className="hint">Encrypted history stays on its original account. Codex supplies a session ID automatically; SDK clients should send a stable X-Proxy-Session-ID from the first request. After a gateway restart, start a new conversation.</p>
          <p className="hint">Current account limits: <code>{origin}/gateway/v1/usage?provider=OPENAI&amp;model={OPENAI_MODEL}</code>, using the same routing token.</p>
        </>}

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

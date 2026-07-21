import { useEffect, useState } from 'react';
import { api, fmtUsd, ProxyTokenDto, UserDto } from '../api';
import { CodeBlock, Copy, Segmented, Select } from '../ui';
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
  const [newPrompt, setNewPrompt] = useState('');
  const [revealed, setRevealed] = useState<ProxyTokenDto | null>(null);
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

  async function create() {
    setErr(null);
    try {
      const t = await api.createRoutingToken(name || 'token', newPrompt.trim() || undefined);
      setRevealed(t); setName(''); setNewPrompt(''); load();
    } catch (e: any) { setErr(e.message); }
  }
  async function del(id: number) {
    if (!confirm('Delete this routing token? Clients using it stop working.')) return;
    await api.deleteRoutingToken(id); load();
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
        <p className="hint" style={{ marginBottom: 4, marginTop: 12 }}>Example (swap in a token you create below):</p>
        <CodeBlock text={snippet('<your-routing-token>')} />
      </div>

      <div className="panel narrow">
        <h2 style={{ marginTop: 0 }}>Create routing token</h2>
        <div className="row">
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="token name (e.g. my-app)" onKeyDown={(e) => e.key === 'Enter' && create()} />
          <button onClick={create}>Create</button>
        </div>
        <textarea
          value={newPrompt} onChange={(e) => setNewPrompt(e.target.value)} rows={3}
          placeholder="Static system prompt (optional) — always injected ahead of whatever system prompt the API request carries"
          style={{ width: '100%', marginTop: 8, resize: 'vertical' }}
        />
        {err && <div className="err">{err}</div>}
        {tok && (
          <div className="tokenreveal">
            <div className="row" style={{ justifyContent: 'space-between' }}>
              <b>New token — copy it now, it won't be shown again</b>
              <Copy text={tok} label="Copy token" />
            </div>
            <div className="mono" style={{ margin: '8px 0', wordBreak: 'break-all' }}>{tok}</div>
            <CodeBlock text={snippet(tok)} />
          </div>
        )}
      </div>

      {tokens.length > 0 && <PromptPanel tokens={tokens} onSaved={load} />}

      <TokenUsageSection source="routing" tokens={tokens} onDelete={del} emptyHint="No routing tokens yet." />
    </div>
  );
}

/**
 * Per-token static system prompt editor. The prompt is injected by the routing gateways right
 * after the mandatory Claude Code block — ahead of (higher priority than) any system prompt the
 * API request itself carries.
 */
function PromptPanel({ tokens, onSaved }: { tokens: ProxyTokenDto[]; onSaved: () => void }) {
  const [sel, setSel] = useState<number>(tokens[0].id);
  const [text, setText] = useState('');
  const [dirty, setDirty] = useState(false);
  const [saved, setSaved] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const cur = tokens.find((t) => t.id === sel) ?? tokens[0];

  // Re-sync the editor when switching tokens or when a background refresh brings new data.
  useEffect(() => {
    if (!tokens.some((t) => t.id === sel)) setSel(tokens[0].id);
  }, [tokens, sel]);
  useEffect(() => {
    if (!dirty) setText(cur.systemPrompt ?? '');
  }, [cur.id, cur.systemPrompt, dirty]);

  async function save(value: string | null) {
    setErr(null);
    try {
      await api.updateRoutingTokenPrompt(cur.id, value);
      setDirty(false); setSaved(true); setTimeout(() => setSaved(false), 1500);
      onSaved();
    } catch (e: any) { setErr(e.message); }
  }

  return (
    <div className="panel narrow">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h2 style={{ margin: 0 }}>Static system prompt</h2>
        <Select ariaLabel="Select token" value={String(cur.id)} minWidth={160}
          onChange={(v) => { setSel(Number(v)); setDirty(false); }}
          options={tokens.map((t) => ({ value: String(t.id), label: t.systemPrompt ? `${t.name} ●` : t.name }))} />
      </div>
      <p className="hint" style={{ margin: '8px 0' }}>
        Injected on every request of this token, ahead of any system prompt the API request carries. ● = prompt set.
      </p>
      <textarea
        value={text} rows={5}
        onChange={(e) => { setText(e.target.value); setDirty(true); }}
        placeholder="e.g. Always answer in Russian. Never reveal internal tooling."
        style={{ width: '100%', resize: 'vertical' }}
      />
      <div className="row" style={{ marginTop: 8, gap: 8 }}>
        <button onClick={() => save(text.trim() || null)} disabled={!dirty}>Save</button>
        {cur.systemPrompt && <button className="ghost" onClick={() => { setText(''); save(null); }}>Clear</button>}
        {saved && <span className="hint">Saved ✓</span>}
      </div>
      {err && <div className="err">{err}</div>}
    </div>
  );
}

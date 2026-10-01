import { useEffect, useState } from 'react';
import { api, ChatMemoryDto, ChatModelDto, ChatSettingsDto, fmtUntilUtcMidnight, fmtUsd } from '../api';
import { Icon, Modal, Select, Switch } from '../ui';

/**
 * What Claude remembers about you. Facts are learned automatically after a turn, but the list is
 * fully editable — the point of showing it is that memory should never be a black box.
 */
export function MemoryModal({ onClose }: { onClose: () => void }) {
  const [items, setItems] = useState<ChatMemoryDto[]>([]);
  const [draft, setDraft] = useState('');
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => { api.memories().then(setItems).catch((e) => setErr(e.message)).finally(() => setLoading(false)); }, []);

  async function run(p: Promise<ChatMemoryDto[]>) {
    setErr(null);
    try { setItems(await p); } catch (e: any) { setErr(e.message); }
  }

  return (
    <Modal title="Memory" onClose={onClose} width={620}
      footer={<>
        {items.length > 0 && (
          <button className="danger" onClick={async () => {
            if (!confirm('Forget everything Claude has learned about you?')) return;
            await api.clearMemories().catch(() => {});
            setItems([]);
          }}>Forget all</button>
        )}
        <button className="ghost" style={{ marginLeft: 'auto' }} onClick={onClose}>Close</button>
      </>}>
      <p className="hint" style={{ marginTop: 0 }}>
        These facts are added to every conversation that has memory switched on. Turn one off to keep
        it out of the prompt without deleting it.
      </p>

      <div className="row" style={{ marginBottom: 14 }}>
        <input value={draft} onChange={(e) => setDraft(e.target.value)}
          placeholder="e.g. Works in Kotlin and React; prefers short answers"
          onKeyDown={(e) => { if (e.key === 'Enter' && draft.trim()) { run(api.addMemory(draft.trim())); setDraft(''); } }} />
        <button disabled={!draft.trim()} onClick={() => { run(api.addMemory(draft.trim())); setDraft(''); }}>Add</button>
      </div>

      {err && <div className="err">{err}</div>}
      {loading && <p className="hint">Loading…</p>}
      {!loading && items.length === 0 && (
        <p className="empty">Nothing remembered yet. Claude will pick things up as you talk.</p>
      )}

      <div className="memlist">
        {items.map((m) => (
          <div key={m.id} className={'memrow' + (m.enabled ? '' : ' off')}>
            <Switch checked={m.enabled} onChange={(v) => run(api.updateMemory(m.id, { enabled: v }))} />
            <span className="c">{m.content}</span>
            <span className={'badge ' + (m.source === 'manual' ? 'accent' : 'muted')}>{m.source}</span>
            <button className="ghost sm" onClick={() => run(api.deleteMemory(m.id))} aria-label="Delete">
              <Icon name="trash" size={14} />
            </button>
          </div>
        ))}
      </div>
    </Modal>
  );
}

/** Per-user chat preferences: memory switch, default model and the custom-instructions pair. */
export function ChatSettingsModal({ models, onClose, onSaved }: {
  models: ChatModelDto[];
  onClose: () => void;
  onSaved: (s: ChatSettingsDto) => void;
}) {
  const [s, setS] = useState<ChatSettingsDto | null>(null);
  const [about, setAbout] = useState('');
  const [style, setStyle] = useState('');
  const [dirty, setDirty] = useState(false);
  const [saved, setSaved] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    api.chatSettings().then((v) => {
      setS(v); setAbout(v.aboutYou ?? ''); setStyle(v.responseStyle ?? '');
    }).catch((e) => setErr(e.message));
  }, []);

  async function patch(body: Partial<ChatSettingsDto>) {
    setErr(null);
    try {
      const next = await api.saveChatSettings(body);
      setS(next); onSaved(next);
      setSaved(true); setDirty(false);
      setTimeout(() => setSaved(false), 1500);
    } catch (e: any) { setErr(e.message); }
  }

  return (
    <Modal title="Chat settings" onClose={onClose} width={600}
      footer={<>
        {saved && <span className="hint" style={{ marginRight: 'auto' }}>Saved ✓</span>}
        <button disabled={!dirty} onClick={() => patch({ aboutYou: about, responseStyle: style })}>Save</button>
        <button className="ghost" onClick={onClose}>Close</button>
      </>}>
      {err && <div className="err">{err}</div>}
      {!s ? <p className="hint">Loading…</p> : (
        <>
          <label className="field switch-field">
            <span>Memory <span className="hint">· learn and reuse facts about you</span></span>
            <Switch checked={s.memoryEnabled} onChange={(v) => patch({ memoryEnabled: v })} />
          </label>

          <label className="field">
            <span>Default model for new chats</span>
            <Select value={s.defaultModel ?? models[0]?.id ?? ''} minWidth={220}
              onChange={(v) => patch({ defaultModel: v })}
              options={models.map((m) => ({ value: m.id, label: m.label }))} />
          </label>

          <label className="field">
            <span>What should Claude know about you?</span>
            <textarea rows={4} value={about}
              onChange={(e) => { setAbout(e.target.value); setDirty(true); }}
              placeholder="Your role, stack, what you're working on…" />
          </label>

          <label className="field">
            <span>How should Claude respond?</span>
            <textarea rows={4} value={style}
              onChange={(e) => { setStyle(e.target.value); setDirty(true); }}
              placeholder="e.g. Be concise. Show code first. Answer in Russian." />
          </label>

          <div className="panel" style={{ margin: 0, background: 'var(--panel-2)' }}>
            {s.dailyChatCostLimit == null ? (
              <p style={{ margin: 0 }}><b>{fmtUsd(s.todayChatCost)}</b> spent on chat today
                <span className="hint"> · no daily chat limit</span></p>
            ) : (
              <>
                <div className="row" style={{ justifyContent: 'space-between', marginBottom: 6 }}>
                  <span><b>{fmtUsd(s.todayChatCost)}</b> / {fmtUsd(s.dailyChatCostLimit)} spent on chat today</span>
                  <span className="hint">resets in {fmtUntilUtcMidnight()} · 00:00 UTC</span>
                </div>
                <div className="bar">
                  <span style={{ width: `${Math.min(100, Math.round((s.todayChatCost / s.dailyChatCostLimit) * 100))}%` }} />
                </div>
              </>
            )}
          </div>
        </>
      )}
    </Modal>
  );
}

/** Import a public ChatGPT / Claude share link (or a pasted export) into a new conversation. */
export function ImportModal({ onClose, onImported }: {
  onClose: () => void;
  onImported: (chatId: number) => void;
}) {
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function go() {
    setErr(null); setBusy(true);
    try {
      const detail = await api.importChat(url.trim());
      onImported(detail.chat.id);
      onClose();
    } catch (e: any) { setErr(e.message); } finally { setBusy(false); }
  }

  return (
    <Modal title="Import a conversation" onClose={onClose} width={560}
      footer={<>
        <button className="ghost" onClick={onClose}>Cancel</button>
        <button disabled={!url.trim() || busy} onClick={go}>{busy ? 'Importing…' : 'Import'}</button>
      </>}>
      <p className="hint" style={{ marginTop: 0 }}>
        Paste a public share link — <span className="mono">chatgpt.com/share/…</span> or{' '}
        <span className="mono">claude.ai/share/…</span>. The conversation is copied into a new chat you can
        continue here. Exported JSON works too.
      </p>
      <textarea rows={3} value={url} onChange={(e) => setUrl(e.target.value)}
        placeholder="https://chatgpt.com/share/…"
        onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && url.trim()) { e.preventDefault(); go(); } }} />
      {err && <div className="err">{err}</div>}
    </Modal>
  );
}

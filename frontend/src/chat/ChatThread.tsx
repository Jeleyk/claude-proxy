import { useEffect, useRef, useState } from 'react';
import { ChatAttachmentDto, fmtUsd } from '../api';
import { Icon } from '../ui';
import { Markdown } from './Markdown';
import { useAutoGrow } from './ChatSidebar';

/** A turn as the thread renders it — stored rows and in-flight ones share this shape. */
export interface Msg {
  id: number;
  role: 'user' | 'assistant';
  content: string;
  thinking?: string | null;
  model?: string | null;
  cost?: number;
  error?: string | null;
  attachments: ChatAttachmentDto[];
  /** still streaming: shows the caret and hides the per-message actions */
  pending?: boolean;
  /** server-side tool activity for this turn (web search) */
  tools?: string[];
}

export function ChatThread({
  messages, streaming, onRetry, onEdit, onDelete, onRewind, onPickPrompt,
}: {
  messages: Msg[];
  streaming: boolean;
  onRetry: (m: Msg) => void;
  onEdit: (m: Msg, text: string) => void;
  onDelete: (m: Msg) => void;
  onRewind: (m: Msg) => void;
  onPickPrompt: (text: string) => void;
}) {
  const bottom = useRef<HTMLDivElement | null>(null);
  const scroller = useRef<HTMLDivElement | null>(null);
  const raf = useRef<number | null>(null);
  const [stick, setStick] = useState(true);

  /*
   * Follow the stream, but stop fighting the user the moment they scroll up.
   *
   * A `scrollIntoView` per token is what made the thread judder on a phone: it animates (the
   * container is `scroll-behavior: smooth`), and each new token restarts the animation from
   * wherever the last one got to. Setting `scrollTop` directly, once per frame, is instant and
   * idempotent — thirty tokens in one frame cost exactly one jump.
   */
  useEffect(() => {
    if (!stick) return;
    if (raf.current != null) return;
    raf.current = requestAnimationFrame(() => {
      raf.current = null;
      const el = scroller.current;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }, [messages, stick]);

  useEffect(() => () => { if (raf.current != null) cancelAnimationFrame(raf.current); }, []);

  function onScroll() {
    const el = scroller.current;
    if (!el) return;
    setStick(el.scrollHeight - el.scrollTop - el.clientHeight < 120);
  }

  function jumpToBottom() {
    setStick(true);
    const el = scroller.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  }

  if (messages.length === 0) return <EmptyState onPick={onPickPrompt} scrollRef={scroller} />;

  return (
    <div className="chat-scroll" ref={scroller} onScroll={onScroll}>
      <div className="chat-thread">
        {messages.map((m) => (
          <MessageRow key={m.id} m={m} streaming={streaming}
            onRetry={onRetry} onEdit={onEdit} onDelete={onDelete} onRewind={onRewind} />
        ))}
        <div ref={bottom} />
      </div>
      {!stick && (
        <button className="jump-bottom" onClick={jumpToBottom} aria-label="Jump to latest">
          <Icon name="chevron-down" size={18} />
        </button>
      )}
    </div>
  );
}

const STARTERS = [
  'Explain a tricky concept in simple terms',
  'Review this code and find the bugs',
  'Draft an email I can actually send',
  'Plan my week around three priorities',
];

function EmptyState({ onPick, scrollRef }: {
  onPick: (t: string) => void;
  scrollRef: React.RefObject<HTMLDivElement>;
}) {
  return (
    <div className="chat-scroll" ref={scrollRef}>
      <div className="chat-empty">
        <div className="halo"><Icon name="sparkle" size={26} /></div>
        <h2>What's on your mind?</h2>
        <p className="hint">Ask anything. Attach images, PDFs or code — they're read in full.</p>
        <div className="starters">
          {STARTERS.map((s) => (
            <button key={s} className="ghost" onClick={() => onPick(s)}>{s}</button>
          ))}
        </div>
      </div>
    </div>
  );
}

function MessageRow({ m, streaming, onRetry, onEdit, onDelete, onRewind }: {
  m: Msg; streaming: boolean;
  onRetry: (m: Msg) => void;
  onEdit: (m: Msg, text: string) => void;
  onDelete: (m: Msg) => void;
  onRewind: (m: Msg) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(m.content);
  const [copied, setCopied] = useState(false);
  const [showThinking, setShowThinking] = useState(false);
  const ref = useAutoGrow(draft, 320);

  async function copy() {
    try { await navigator.clipboard.writeText(m.content); } catch { /* clipboard blocked */ }
    setCopied(true);
    setTimeout(() => setCopied(false), 1400);
  }

  if (m.role === 'user') {
    return (
      <div className="msg user">
        {editing ? (
          <div className="edit-box">
            <textarea ref={ref} value={draft} onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { onEdit(m, draft); setEditing(false); }
                if (e.key === 'Escape') { setDraft(m.content); setEditing(false); }
              }} />
            <div className="row" style={{ justifyContent: 'flex-end' }}>
              <button className="ghost sm" onClick={() => { setDraft(m.content); setEditing(false); }}>Cancel</button>
              <button className="sm" onClick={() => { onEdit(m, draft); setEditing(false); }}>Send</button>
            </div>
          </div>
        ) : (
          <>
            <div className="bubble">
              <Attachments items={m.attachments} />
              <div className="user-text">{m.content}</div>
            </div>
            <div className="msg-actions">
              <button className="act" onClick={copy} title="Copy">{copied ? '✓' : <Icon name="copy" size={14} />}</button>
              {!streaming && (
                <button className="act" title="Edit and resend"
                  onClick={() => { setDraft(m.content); setEditing(true); }}>
                  <Icon name="edit" size={14} />
                </button>
              )}
              {/* Rewind hands the conversation back: everything from here on is dropped and this
                  message returns to the composer, unsent. */}
              {!streaming && (
                <button className="act" title="Rewind to here" onClick={() => onRewind(m)}>
                  <Icon name="rewind" size={14} /><span className="lbl">Rewind</span>
                </button>
              )}
            </div>
          </>
        )}
      </div>
    );
  }

  return (
    <div className="msg assistant">
      <div className="avatar"><Icon name="sparkle" size={14} /></div>
      <div className="body">
        {m.thinking && (
          <div className={'thinking' + (showThinking ? ' open' : '')}>
            <button className="thinking-head" onClick={() => setShowThinking((v) => !v)}>
              <Icon name="chevron-down" size={14} />
              <span>{m.pending && !m.content ? 'Thinking…' : 'Thought process'}</span>
            </button>
            {showThinking && <div className="thinking-body">{m.thinking}</div>}
          </div>
        )}
        {m.tools?.map((t, i) => (
          <div key={i} className="tool-chip"><Icon name="search" size={13} />{t}</div>
        ))}
        {m.content ? <Markdown text={m.content} /> : m.pending && !m.thinking ? <TypingDots /> : null}
        {m.pending && m.content && <span className="caret" />}
        {m.error && <div className="msg-error"><Icon name="warn" size={14} />{m.error}</div>}
        {!m.pending && (
          <div className="msg-actions">
            <button className="act" onClick={copy}>{copied ? '✓ Copied' : <><Icon name="copy" size={14} />Copy</>}</button>
            {!streaming && <button className="act" onClick={() => onRetry(m)}><Icon name="retry" size={14} />Retry</button>}
            {!streaming && <button className="act" onClick={() => onDelete(m)}><Icon name="trash" size={14} /></button>}
            <span className="meta">
              {m.model}{m.cost ? ` · ${fmtUsd(m.cost)}` : ''}
            </span>
          </div>
        )}
      </div>
    </div>
  );
}

function TypingDots() {
  return <div className="typing"><span /><span /><span /></div>;
}

/** Attachment strip: images preview inline, everything else shows as a file chip. */
export function Attachments({ items, onRemove }: {
  items: ChatAttachmentDto[];
  onRemove?: (a: ChatAttachmentDto) => void;
}) {
  if (items.length === 0) return null;
  return (
    <div className="attachments">
      {items.map((a) => (
        <div key={a.id} className={'att ' + a.kind}>
          {a.kind === 'image' ? (
            <a href={`/api/chat/attachments/${a.id}`} target="_blank" rel="noopener noreferrer">
              <img src={`/api/chat/attachments/${a.id}`} alt={a.name} />
            </a>
          ) : (
            <a className="file" href={`/api/chat/attachments/${a.id}`} target="_blank" rel="noopener noreferrer">
              <Icon name={a.kind === 'document' ? 'pdf' : 'file'} size={16} />
              <span className="n">{a.name}</span>
              <span className="s">{fmtSize(a.size)}</span>
            </a>
          )}
          {onRemove && <button className="rm" onClick={() => onRemove(a)} aria-label={`Remove ${a.name}`}>×</button>}
        </div>
      ))}
    </div>
  );
}

export function fmtSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

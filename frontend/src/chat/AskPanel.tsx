import { useEffect, useRef, useState } from 'react';
import { ChatTurn, streamChat } from '../api';
import { Icon } from '../ui';
import { Markdown } from './Markdown';
import { useAutoGrow } from './ChatSidebar';

/**
 * A side question about the open conversation.
 *
 * The answer is computed against the same transcript the chat would send, but as a *temporary*
 * turn — nothing is stored unless you keep it. That is the point: "what did we decide about X",
 * "summarise this for a ticket", "is that number right" are questions about the conversation, not
 * moves in it, and they shouldn't leave debris in the thread.
 */
export function AskPanel({ history, model, onClose, onAdd }: {
  history: ChatTurn[];
  model: string;
  onClose: () => void;
  onAdd: (question: string, answer: string) => Promise<void> | void;
}) {
  const [question, setQuestion] = useState('');
  const [asked, setAsked] = useState('');
  const [answer, setAnswer] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const abort = useRef<AbortController | null>(null);
  const ref = useAutoGrow(question, 160);

  useEffect(() => () => abort.current?.abort(), []);

  async function ask() {
    const q = question.trim();
    if (!q || busy) return;
    setErr(null);
    setAnswer('');
    setAsked(q);
    setBusy(true);
    const ctrl = new AbortController();
    abort.current = ctrl;
    try {
      await streamChat(
        { temporary: true, message: q, history, model },
        (ev) => {
          if (ev.type === 'delta') setAnswer((a) => a + ev.t);
          else if (ev.type === 'reset') setAnswer('');
          else if (ev.type === 'error') setErr(ev.message);
        },
        ctrl.signal,
      );
    } catch (e: any) {
      if (e?.name !== 'AbortError') setErr(e.message);
    } finally {
      setBusy(false);
      abort.current = null;
    }
  }

  return (
    <div className="askpanel">
      <div className="askpanel-head">
        <span className="t"><Icon name="ask" size={15} />Ask about this chat</span>
        <button className="x" onClick={onClose} aria-label="Close">×</button>
      </div>

      <div className="askpanel-body">
        {!asked && (
          <p className="hint" style={{ marginTop: 0 }}>
            Answered from this conversation's context, off to the side — nothing is added to the
            thread unless you say so.
          </p>
        )}
        {asked && <div className="askpanel-q">{asked}</div>}
        {err && <div className="err">{err}</div>}
        {answer
          ? <Markdown text={answer} />
          : busy ? <div className="typing"><span /><span /><span /></div> : null}
      </div>

      <div className="askpanel-foot">
        <textarea
          ref={ref} value={question} rows={1} placeholder="e.g. what did we settle on?"
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); ask(); }
          }}
        />
        <div className="row" style={{ justifyContent: 'flex-end' }}>
          {answer && !busy && (
            <button className="ghost sm" style={{ marginRight: 'auto' }}
              onClick={() => { setAsked(''); setAnswer(''); setQuestion(''); }}>
              Discard
            </button>
          )}
          {answer && !busy && (
            <button className="sm" onClick={async () => { await onAdd(asked, answer); onClose(); }}>
              Add to chat
            </button>
          )}
          {busy
            ? <button className="ghost sm" onClick={() => abort.current?.abort()}>Stop</button>
            : <button className="sm" disabled={!question.trim()} onClick={ask}>Ask</button>}
        </div>
      </div>
    </div>
  );
}

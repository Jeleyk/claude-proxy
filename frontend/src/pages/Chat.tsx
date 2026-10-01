import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  api, ChatAttachmentDto, ChatDto, ChatModelDto, ChatSettingsDto, ChatStreamBody, ChatStreamEvent,
  ChatTurn, continueChatStream, streamChat,
} from '../api';
import { AnchoredMenu, Icon } from '../ui';
import { ChatSidebar } from '../chat/ChatSidebar';
import { ChatThread, Msg } from '../chat/ChatThread';
import { Composer } from '../chat/Composer';
import { ChatSettingsModal, ImportModal, MemoryModal } from '../chat/ChatModals';
import { AskPanel } from '../chat/AskPanel';

/** Ids for optimistic rows, before the server assigns real ones. Negative, so they never clash. */
let localId = -1;
const nextLocalId = () => localId--;

export function Chat({ onOpenNav }: { onOpenNav: () => void }) {
  const { id } = useParams();
  const navigate = useNavigate();
  const activeId = id ? Number(id) : null;

  const [chats, setChats] = useState<ChatDto[]>([]);
  const [query, setQuery] = useState('');
  const [messages, setMessages] = useState<Msg[]>([]);
  const [chat, setChat] = useState<ChatDto | null>(null);
  const [temporary, setTemporary] = useState(false);
  const [models, setModels] = useState<ChatModelDto[]>([]);
  const [model, setModel] = useState<string>('');
  const [settings, setSettings] = useState<ChatSettingsDto | null>(null);

  const [draft, setDraft] = useState('');
  const [attachments, setAttachments] = useState<ChatAttachmentDto[]>([]);
  const [webSearch, setWebSearch] = useState(false);
  const [thinking, setThinking] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const [sideOpen, setSideOpen] = useState(() => window.innerWidth > 900);
  const [modal, setModal] = useState<'memory' | 'settings' | 'import' | null>(null);
  // The header menu is portal-rendered like the row menus, so it keeps a handle on its trigger.
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);
  const [asking, setAsking] = useState(false);
  const [compacting, setCompacting] = useState(false);
  const abort = useRef<AbortController | null>(null);

  /* ---- loading ---- */

  const reloadList = useCallback(async (q = query) => {
    try { setChats(await api.chats(q || undefined)); } catch (e: any) { setErr(e.message); }
  }, [query]);

  useEffect(() => {
    api.chatModels().then((m) => { setModels(m); setModel((cur) => cur || m[0]?.id || ''); }).catch(() => {});
    api.chatSettings().then(setSettings).catch(() => {});
  }, []);

  // Debounced search: the server does the matching, including message bodies.
  useEffect(() => {
    const t = setTimeout(() => { reloadList(query); }, query ? 220 : 0);
    return () => clearTimeout(t);
  }, [query, reloadList]);

  useEffect(() => {
    if (activeId == null) { setMessages([]); setChat(null); return; }
    setTemporary(false);
    let cancelled = false;
    api.chat(activeId).then((d) => {
      if (cancelled) return;
      setChat(d.chat);
      setModel(d.chat.model);
      setMessages(d.messages.map(toMsg));
    }).catch((e) => setErr(e.message));
    return () => { cancelled = true; };
    // A reload while streaming would wipe the in-flight rows; the stream owns the state until done.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeId]);

  // Close the drawer after picking a chat on a phone.
  const narrow = () => window.innerWidth <= 900;

  /* ---- sending ---- */

  /**
   * Ask for an assistant turn. [opts.base] replaces the transcript this turn builds on — a retry
   * or an edit cuts the thread first, and the state update it queues isn't visible to this call
   * yet, so the caller passes the cut list explicitly rather than racing the re-render.
   */
  async function send(text: string, opts: { fromMessageId?: number; base?: Msg[] } = {}) {
    if (streaming) return;
    setErr(null);
    const sent = text.trim();
    const prior = opts.base ?? messages;
    // A retry/edit re-sends an existing turn; whatever is staged in the composer belongs to the
    // message the user is still writing, so it stays there.
    const atts = opts.base ? [] : attachments;
    const assistant: Msg = { id: nextLocalId(), role: 'assistant', content: '', attachments: [], pending: true };
    const optimisticUser: Msg | null = sent || atts.length
      ? { id: nextLocalId(), role: 'user', content: sent, attachments: atts }
      : null;

    // History for a temporary chat has to travel with the request — nothing is stored server-side.
    const history: ChatTurn[] = temporary
      ? prior.filter((m) => !m.pending && m.content)
        .map((m) => ({ role: m.role, content: m.content, attachmentIds: m.attachments.map((a) => a.id) }))
      : [];

    setMessages([...prior, ...(optimisticUser ? [optimisticUser] : []), assistant]);
    if (!opts.base) { setDraft(''); setAttachments([]); }
    setStreaming(true);

    const body: ChatStreamBody = {
      chatId: temporary ? null : activeId,
      temporary,
      message: sent,
      attachmentIds: atts.map((a) => a.id),
      model: model || undefined,
      webSearch,
      thinking,
      history,
      fromMessageId: opts.fromMessageId,
    };

    const ctrl = new AbortController();
    abort.current = ctrl;
    let createdChatId: number | null = null;
    let broke = false;

    const patchAssistant = (fn: (m: Msg) => Msg) =>
      setMessages((prev) => prev.map((m) => (m.id === assistant.id ? fn(m) : m)));

    try {
      await streamChat(body, (ev: ChatStreamEvent) => {
        switch (ev.type) {
          case 'start':
            if (ev.chatId && !temporary) {
              createdChatId = ev.chatId;
              if (optimisticUser && ev.userMessageId) {
                setMessages((prev) => prev.map((m) => (m.id === optimisticUser.id ? { ...m, id: ev.userMessageId! } : m)));
              }
            }
            break;
          case 'delta':
            patchAssistant((m) => ({ ...m, content: m.content + ev.t }));
            break;
          case 'thinking':
            patchAssistant((m) => ({ ...m, thinking: (m.thinking ?? '') + ev.t }));
            break;
          case 'tool':
            patchAssistant((m) => ({
              ...m,
              tools: [...(m.tools ?? []), ev.query ? `Searching the web: “${ev.query}”` : `Web search · ${ev.results ?? 0} results`],
            }));
            break;
          case 'reset':
            patchAssistant((m) => ({ ...m, content: '', thinking: null, tools: undefined }));
            break;
          case 'done':
            patchAssistant((m) => ({
              ...m, pending: false, id: ev.messageId ?? m.id,
              model: ev.model, cost: ev.cost, error: ev.warning ?? null,
            }));
            break;
          case 'error':
            patchAssistant((m) => ({ ...m, pending: false, error: ev.message }));
            break;
        }
      }, ctrl.signal);
    } catch (e: any) {
      const aborted = e?.name === 'AbortError';
      broke = true;
      patchAssistant((m) => ({
        ...m, pending: false,
        error: aborted ? (m.content ? null : 'Stopped.') : e.message,
      }));
      if (!aborted) setErr(e.message);
    } finally {
      setStreaming(false);
      abort.current = null;
      if (!temporary) {
        const target = createdChatId ?? activeId;
        if (createdChatId && createdChatId !== activeId) navigate(`/chat/${createdChatId}`, { replace: true });
        // A stream that died never delivered the real row ids, so what the browser holds and what
        // the server stored have drifted apart. Re-read the thread rather than acting on guesses.
        else if (broke && target != null) {
          api.chat(target).then((d) => { setChat(d.chat); setMessages(d.messages.map(toMsg)); }).catch(() => {});
        }
        reloadList();
        // The chat is titled by a background call; give it a moment, then refresh the list.
        setTimeout(() => reloadList(), 2600);
      }
    }
  }

  function stop() {
    abort.current?.abort();
  }

  /* ---- per-message actions ---- */

  /**
   * The server-side id of a row, or undefined for one that only exists in this browser.
   *
   * Optimistic rows carry negative placeholder ids until the stream reports the real one. Sending
   * a placeholder as `fromMessageId` asks the server to cut "everything from id -3 onward" — i.e.
   * the whole conversation. That is exactly how a live chat got wiped once, when a stream died
   * before `done` arrived and the retry sent the placeholder.
   */
  const serverId = (m: Msg) => (!temporary && m.id > 0 ? m.id : undefined);

  /** Cut the thread at [m] and ask again — the answer is replaced, not branched. */
  function retry(m: Msg) {
    const base = messages.slice(0, messages.findIndex((x) => x.id === m.id));
    send('', { base, fromMessageId: serverId(m) });
  }

  /** Rewrite a user turn: the message and everything after it go, then the new text is sent. */
  function edit(m: Msg, text: string) {
    if (!text.trim()) return;
    const base = messages.slice(0, messages.findIndex((x) => x.id === m.id));
    send(text, { base, fromMessageId: serverId(m) });
  }

  /**
   * Rewind to one of your own turns: everything from it onward is dropped and its text lands back
   * in the composer, unsent. Unlike edit-and-resend, this hands the conversation back to you —
   * reword it, attach something else, switch the model, or just stop there.
   */
  async function rewind(m: Msg) {
    if (streaming) return;
    const base = messages.slice(0, messages.findIndex((x) => x.id === m.id));
    setMessages(base);
    setDraft(m.content);
    setAttachments(m.attachments);
    if (activeId != null && serverId(m) != null) {
      try {
        const d = await api.truncateChat(activeId, m.id);
        setMessages(d.messages.map(toMsg));
        reloadList();
      } catch (e: any) { setErr(e.message); }
    }
  }

  /**
   * Keep a side question: it lands in the thread as an ordinary exchange. The answer already
   * exists (and was already billed), so nothing goes upstream again.
   */
  async function addExchange(question: string, answer: string) {
    if (temporary || activeId == null) {
      setMessages((prev) => [
        ...prev,
        { id: nextLocalId(), role: 'user', content: question, attachments: [] },
        { id: nextLocalId(), role: 'assistant', content: answer, model, attachments: [] },
      ]);
      return;
    }
    try {
      const d = await api.appendExchange(activeId, { question, answer, model });
      setMessages(d.messages.map(toMsg));
      reloadList();
    } catch (e: any) { setErr(e.message); }
  }

  /** Duplicate a conversation and open the copy. */
  async function copyChat(id: number) {
    setErr(null);
    try {
      const d = await api.copyChat(id);
      await reloadList();
      navigate(`/chat/${d.chat.id}`);
    } catch (e: any) { setErr(e.message); }
  }

  /**
   * Start a fresh chat that carries this one's context. The server compacts the transcript
   * upstream, so this takes a moment — worth it on a conversation that has outgrown its window.
   */
  async function continueInNewChat() {
    if (activeId == null || temporary || compacting) return;
    setErr(null);
    setCompacting(true);
    try {
      const fresh = await continueChatStream(activeId);
      await reloadList();
      navigate(`/chat/${fresh}`);
    } catch (e: any) { setErr(e.message); } finally { setCompacting(false); }
  }

  async function removeFrom(m: Msg) {
    if (temporary || activeId == null) {
      setMessages((prev) => prev.slice(0, prev.findIndex((x) => x.id === m.id)));
      return;
    }
    try {
      const d = await api.truncateChat(activeId, m.id);
      setMessages(d.messages.map(toMsg));
      reloadList();
    } catch (e: any) { setErr(e.message); }
  }

  /* ---- chat management ---- */

  function newChat() {
    setTemporary(false);
    setMessages([]);
    setChat(null);
    navigate('/chat');
    if (narrow()) setSideOpen(false);
  }

  function toggleTemporary() {
    setTemporary(true);
    setMessages([]);
    setChat(null);
    navigate('/chat');
    if (narrow()) setSideOpen(false);
  }

  async function changeModel(next: string) {
    setModel(next);
    if (activeId != null && !temporary) {
      await api.updateChat(activeId, { model: next }).catch(() => {});
    }
  }

  const title = temporary ? 'Temporary chat' : chat?.title ?? 'New chat';

  return (
    <div className={'chatpage' + (sideOpen ? ' side-open' : '')}>
      <div className="chat-scrim" onClick={() => setSideOpen(false)} />

      <ChatSidebar
        chats={chats} activeId={activeId} temporary={temporary} query={query} onQuery={setQuery}
        onSelect={(cid) => { navigate(`/chat/${cid}`); if (narrow()) setSideOpen(false); }}
        onNew={newChat} onTemporary={toggleTemporary}
        onRename={(c, t) => { if (t.trim() && t !== c.title) api.updateChat(c.id, { title: t.trim() }).then(() => reloadList()); }}
        onPin={(c) => api.updateChat(c.id, { pinned: !c.pinned }).then(() => reloadList())}
        onArchive={(c) => api.updateChat(c.id, { archived: !c.archived }).then(() => reloadList())}
        onCopy={(c) => copyChat(c.id)}
        onDelete={async (c) => {
          if (!confirm(`Delete “${c.title}”? This can't be undone.`)) return;
          await api.deleteChat(c.id).catch(() => {});
          if (c.id === activeId) navigate('/chat');
          reloadList();
        }}
        onOpenMemory={() => setModal('memory')}
        onOpenSettings={() => setModal('settings')}
        onOpenImport={() => setModal('import')}
        onClose={() => setSideOpen(false)}
        onOpenNav={onOpenNav}
      />

      <div className="chat-main">
        <header className="chat-head">
          {!sideOpen && (
            <button className="ghost icon" onClick={() => setSideOpen(true)} aria-label="Open conversations">
              <Icon name="sidebar" size={17} />
            </button>
          )}
          <h1 className="chat-title" title={title}>
            {temporary && <Icon name="ghost" size={15} />}{title}
          </h1>
          <div className="chat-head-right">
            {/* Asking *about* the conversation only makes sense once there is one. */}
            {messages.length > 0 && (
              <button className={'ghost icon' + (asking ? ' on' : '')} onClick={() => setAsking((v) => !v)}
                aria-label="Ask about this chat" title="Ask about this chat" aria-pressed={asking}>
                <Icon name="ask" size={17} />
              </button>
            )}
            {/* Starting a temporary chat is only an option while nothing has been said yet —
                afterwards it would silently throw the conversation away. */}
            {!temporary && messages.length === 0 && (
              <button className="ghost icon" onClick={toggleTemporary}
                aria-label="Temporary chat" title="Temporary chat — nothing is saved">
                <Icon name="ghost" size={17} />
              </button>
            )}
            <button className="ghost icon" onClick={newChat} aria-label="New chat" title="New chat">
              <Icon name="plus" size={17} />
            </button>
            <button className="ghost icon" aria-label="Chat actions" aria-haspopup="menu"
              onClick={(e) => {
                const anchor = e.currentTarget;
                setMenuAnchor((cur) => (cur ? null : anchor));
              }}>
              <Icon name="dots" size={17} />
            </button>
            {menuAnchor && (
              <AnchoredMenu anchor={menuAnchor} onClose={() => setMenuAnchor(null)}>
                <button disabled={activeId == null || temporary || compacting}
                  onClick={() => { setMenuAnchor(null); continueInNewChat(); }}>
                  Continue in a new chat
                </button>
                <button disabled={activeId == null || temporary}
                  onClick={() => { setMenuAnchor(null); if (activeId != null) copyChat(activeId); }}>
                  Duplicate this chat
                </button>
                <button onClick={() => { setMenuAnchor(null); setModal('memory'); }}>Memory</button>
                <button onClick={() => { setMenuAnchor(null); setModal('import'); }}>Import a conversation</button>
                <button onClick={() => { setMenuAnchor(null); setModal('settings'); }}>Chat settings</button>
              </AnchoredMenu>
            )}
          </div>
        </header>

        {temporary && (
          <div className="temp-banner">
            <Icon name="ghost" size={14} />
            This chat isn't saved: it leaves no history, and nothing here is remembered.
          </div>
        )}
        {err && <div className="err chat-err">{err}</div>}

        {compacting && (
          <div className="temp-banner compacting">
            <Icon name="sparkle" size={14} />
            Compacting this conversation into a new chat…
          </div>
        )}

        <ChatThread
          messages={messages} streaming={streaming}
          onRetry={retry} onEdit={edit} onDelete={removeFrom} onRewind={rewind}
          onPickPrompt={(t) => setDraft(t)}
        />

        {asking && (
          <AskPanel
            model={model}
            history={messages.filter((m) => !m.pending && m.content)
              .map((m) => ({ role: m.role, content: m.content, attachmentIds: m.attachments.map((a) => a.id) }))}
            onClose={() => setAsking(false)}
            onAdd={addExchange}
          />
        )}

        <Composer
          value={draft} onChange={setDraft}
          onSend={() => send(draft)} onStop={stop} streaming={streaming}
          webSearch={webSearch} onWebSearch={setWebSearch}
          thinking={thinking} onThinking={setThinking}
          attachments={attachments} onAttachments={setAttachments}
          models={models} model={model} onModel={changeModel}
          hint={settings?.dailyChatCostLimit != null && settings.todayChatCost >= settings.dailyChatCostLimit
            ? 'Daily chat limit reached' : undefined}
        />
      </div>

      {modal === 'memory' && <MemoryModal onClose={() => setModal(null)} />}
      {modal === 'settings' && (
        <ChatSettingsModal models={models} onClose={() => setModal(null)} onSaved={setSettings} />
      )}
      {modal === 'import' && (
        <ImportModal onClose={() => setModal(null)} onImported={(cid) => { reloadList(); navigate(`/chat/${cid}`); }} />
      )}
    </div>
  );
}

function toMsg(m: import('../api').ChatMessageDto): Msg {
  return {
    id: m.id, role: m.role, content: m.content, thinking: m.thinking, model: m.model,
    cost: m.cost, error: m.error, attachments: m.attachments,
  };
}

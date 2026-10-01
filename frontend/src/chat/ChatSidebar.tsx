import { useEffect, useRef, useState } from 'react';
import { ChatDto } from '../api';
import { AnchoredMenu, Icon } from '../ui';

/** Buckets the list the way a person reads it: pinned first, then by recency. */
function group(chats: ChatDto[]): { label: string; items: ChatDto[] }[] {
  const now = Date.now();
  const day = 86_400_000;
  const buckets: Record<string, ChatDto[]> = { Pinned: [], Today: [], 'Previous 7 days': [], Older: [] };
  chats.forEach((c) => {
    if (c.pinned) return void buckets.Pinned.push(c);
    const age = now - new Date(c.updatedAt).getTime();
    if (age < day) buckets.Today.push(c);
    else if (age < 7 * day) buckets['Previous 7 days'].push(c);
    else buckets.Older.push(c);
  });
  return Object.entries(buckets)
    .filter(([, items]) => items.length > 0)
    .map(([label, items]) => ({ label, items }));
}

export function ChatSidebar({
  chats, activeId, temporary, query, onQuery, onSelect, onNew, onTemporary, onRename, onPin,
  onArchive, onCopy, onDelete, onOpenMemory, onOpenSettings, onOpenImport, onClose, onOpenNav,
}: {
  chats: ChatDto[];
  activeId: number | null;
  temporary: boolean;
  query: string;
  onQuery: (q: string) => void;
  onSelect: (id: number) => void;
  onNew: () => void;
  onTemporary: () => void;
  onRename: (chat: ChatDto, title: string) => void;
  onPin: (chat: ChatDto) => void;
  onArchive: (chat: ChatDto) => void;
  onCopy: (chat: ChatDto) => void;
  onDelete: (chat: ChatDto) => void;
  onOpenMemory: () => void;
  onOpenSettings: () => void;
  onOpenImport: () => void;
  onClose: () => void;
  /** Phones only: the chat page hides the app topbar, so the way back to the rest of the admin
   *  lives here, above the conversation list. */
  onOpenNav: () => void;
}) {
  // The open menu keeps a handle on the button that opened it: the list scrolls, so the menu is
  // positioned against the live element rather than laid out inside the (clipping) list.
  const [menu, setMenu] = useState<{ id: number; anchor: HTMLElement } | null>(null);
  const [renaming, setRenaming] = useState<number | null>(null);
  const [draft, setDraft] = useState('');

  const groups = group(chats);

  return (
    <aside className="chat-side">
      <button className="leave-chat" onClick={onOpenNav}>
        <Icon name="collapse" size={15} />
        <span>claude-proxy</span>
        <span className="hint">menu</span>
      </button>

      <div className="chat-side-top">
        <button className="new-chat" onClick={onNew}>
          <Icon name="plus" size={16} /><span>New chat</span>
        </button>
        <button className="ghost icon close-side" onClick={onClose} aria-label="Close conversations">
          <Icon name="collapse" size={16} />
        </button>
      </div>

      <div className="chat-search">
        <Icon name="search" size={15} />
        <input value={query} onChange={(e) => onQuery(e.target.value)} placeholder="Search chats"
          aria-label="Search chats" />
        {query && <button className="x" onClick={() => onQuery('')} aria-label="Clear search">×</button>}
      </div>

      <div className="chat-list">
        {temporary && (
          <button className={'chat-row temp active'} onClick={onTemporary}>
            <span className="t"><Icon name="ghost" size={14} /> Temporary chat</span>
            <span className="p">Nothing here is saved</span>
          </button>
        )}
        {groups.length === 0 && !temporary && (
          <p className="empty">{query ? 'Nothing matches that search.' : 'No conversations yet.'}</p>
        )}
        {groups.map((g) => (
          <div key={g.label} className="chat-group">
            <div className="chat-group-label">{g.label}</div>
            {g.items.map((c) => (
              <div key={c.id} className={'chat-row-wrap' + (c.id === activeId && !temporary ? ' active' : '')}>
                {renaming === c.id ? (
                  <input
                    className="rename" autoFocus value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                    onBlur={() => { onRename(c, draft); setRenaming(null); }}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') { onRename(c, draft); setRenaming(null); }
                      if (e.key === 'Escape') setRenaming(null);
                    }}
                  />
                ) : (
                  <button className="chat-row" onClick={() => onSelect(c.id)} title={c.title}>
                    <span className="t">{c.pinned && <Icon name="pin" size={12} />}{c.title}</span>
                    <span className="p">{c.snippet ?? c.preview ?? '—'}</span>
                  </button>
                )}
                <button
                  className="row-menu-btn" aria-label="Chat actions"
                  onClick={(e) => {
                    e.stopPropagation();
                    const anchor = e.currentTarget;
                    setMenu((cur) => (cur?.id === c.id ? null : { id: c.id, anchor }));
                  }}
                >⋯</button>
                {menu?.id === c.id && (
                  <AnchoredMenu anchor={menu.anchor} onClose={() => setMenu(null)}>
                    <button onClick={() => { setDraft(c.title); setRenaming(c.id); setMenu(null); }}>Rename</button>
                    <button onClick={() => { onPin(c); setMenu(null); }}>{c.pinned ? 'Unpin' : 'Pin'}</button>
                    <button onClick={() => { onCopy(c); setMenu(null); }}>Duplicate</button>
                    <button onClick={() => { onArchive(c); setMenu(null); }}>{c.archived ? 'Unarchive' : 'Archive'}</button>
                    <button className="danger" onClick={() => { onDelete(c); setMenu(null); }}>Delete</button>
                  </AnchoredMenu>
                )}
              </div>
            ))}
          </div>
        ))}
      </div>

      <div className="chat-side-foot">
        <button className="ghost sm" onClick={onOpenMemory}><Icon name="memory" size={15} /><span>Memory</span></button>
        <button className="ghost sm" onClick={onOpenImport}><Icon name="import" size={15} /><span>Import</span></button>
        <button className="ghost sm" onClick={onOpenSettings}><Icon name="settings" size={15} /><span>Settings</span></button>
      </div>
    </aside>
  );
}

/** Auto-growing textarea used by the composer and the inline message editor. */
export function useAutoGrow(value: string, max = 260) {
  const ref = useRef<HTMLTextAreaElement | null>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, max)}px`;
  }, [value, max]);
  return ref;
}

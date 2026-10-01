import { useRef, useState } from 'react';
import { ChatAttachmentDto, ChatModelDto, uploadAttachment } from '../api';
import { Icon } from '../ui';
import { Attachments } from './ChatThread';
import { useAutoGrow } from './ChatSidebar';
import { ModelPicker } from './ModelPicker';

/**
 * The composer. Uploads happen as soon as a file is attached, so sending is instant and the
 * server already holds the bytes when the turn goes out.
 */
export function Composer({
  value, onChange, onSend, onStop, streaming, webSearch, onWebSearch, thinking, onThinking,
  attachments, onAttachments, models, model, onModel, disabled, hint,
}: {
  value: string;
  onChange: (v: string) => void;
  onSend: () => void;
  onStop: () => void;
  streaming: boolean;
  webSearch: boolean;
  onWebSearch: (v: boolean) => void;
  thinking: boolean;
  onThinking: (v: boolean) => void;
  attachments: ChatAttachmentDto[];
  onAttachments: (a: ChatAttachmentDto[]) => void;
  models: ChatModelDto[];
  model: string;
  onModel: (id: string) => void;
  disabled?: boolean;
  hint?: string;
}) {
  const ref = useAutoGrow(value);
  const fileInput = useRef<HTMLInputElement | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);

  async function upload(files: FileList | File[]) {
    setErr(null);
    setBusy(true);
    try {
      const added: ChatAttachmentDto[] = [];
      for (const file of Array.from(files)) added.push(await uploadAttachment(file));
      onAttachments([...attachments, ...added]);
    } catch (e: any) {
      setErr(e.message);
    } finally {
      setBusy(false);
    }
  }

  const canSend = !disabled && !streaming && (value.trim() !== '' || attachments.length > 0);

  return (
    <div className={'composer' + (dragging ? ' dragging' : '')}
      onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
      onDragLeave={() => setDragging(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragging(false);
        if (e.dataTransfer.files.length) upload(e.dataTransfer.files);
      }}>
      {err && <div className="err composer-err">{err}</div>}
      <div className="composer-box">
        <Attachments items={attachments}
          onRemove={(a) => onAttachments(attachments.filter((x) => x.id !== a.id))} />

        <textarea
          ref={ref}
          value={value}
          rows={1}
          placeholder={disabled ? 'Chat is unavailable' : 'Message Claude…'}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
          onPaste={(e) => {
            const files = Array.from(e.clipboardData.files);
            if (files.length) { e.preventDefault(); upload(files); }
          }}
          onKeyDown={(e) => {
            // Enter sends, Shift+Enter breaks the line — the convention every chat uses.
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              if (canSend) onSend();
            }
          }}
        />

        <div className="composer-bar">
          <div className="left">
            <button className="ghost icon" onClick={() => fileInput.current?.click()} disabled={busy || disabled}
              title="Attach an image, PDF or text file" aria-label="Attach a file">
              <Icon name={busy ? 'dots' : 'clip'} size={17} />
            </button>
            <button className={'chip' + (webSearch ? ' on' : '')} onClick={() => onWebSearch(!webSearch)}
              title="Let Claude search the web" aria-pressed={webSearch}>
              <Icon name="globe" size={14} /><span>Search</span>
            </button>
            <button className={'chip' + (thinking ? ' on' : '')} onClick={() => onThinking(!thinking)}
              title="Extended thinking — slower, better on hard problems" aria-pressed={thinking}>
              <Icon name="brain" size={14} /><span>Think</span>
            </button>
          </div>
          <div className="right">
            {hint && <span className="hint composer-hint">{hint}</span>}
            <ModelPicker models={models} value={model} onChange={onModel} disabled={disabled} />
            {streaming ? (
              <button className="stop" onClick={onStop} aria-label="Stop generating">
                <Icon name="stop" size={15} /><span>Stop</span>
              </button>
            ) : (
              <button className="send" onClick={onSend} disabled={!canSend} aria-label="Send">
                <Icon name="send" size={16} />
              </button>
            )}
          </div>
        </div>
      </div>

      <input ref={fileInput} type="file" multiple hidden
        accept="image/png,image/jpeg,image/gif,image/webp,application/pdf,text/*,.md,.json,.yaml,.yml,.csv,.ts,.tsx,.js,.jsx,.py,.go,.rs,.kt,.java,.sql,.sh"
        onChange={(e) => { if (e.target.files?.length) upload(e.target.files); e.target.value = ''; }} />
    </div>
  );
}

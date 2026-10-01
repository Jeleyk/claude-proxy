import { useEffect, useRef, useState } from 'react';
import { ChatModelDto } from '../api';
import { Icon } from '../ui';

/**
 * Model picker for the composer: a small trigger beside Send, opening a menu upward.
 *
 * A native `<select>` would be one line, but it can't show the per-model note and on iOS it
 * hands the choice to a full-screen wheel — a heavy interaction for something you flip between
 * two entries of. This is a plain button + popover, dismissed by click-outside or Escape.
 */
export function ModelPicker({ models, value, onChange, disabled }: {
  models: ChatModelDto[];
  value: string;
  onChange: (id: string) => void;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement | null>(null);
  const current = models.find((m) => m.id === value);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!box.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    window.addEventListener('mousedown', onDown);
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('mousedown', onDown);
      window.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div className="modelpick" ref={box}>
      <button
        type="button" className={'modelpick-btn' + (open ? ' open' : '')} disabled={disabled}
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="listbox" aria-expanded={open}
        aria-label={`Model: ${current?.label ?? value}`}
        title={`Model: ${current?.label ?? value}`}
      >
        <Icon name="model" size={15} />
        <span className="lbl">{current?.label ?? value}</span>
        <Icon name="chevron-down" size={13} />
      </button>

      {open && (
        <div className="modelpick-menu" role="listbox">
          {models.map((m) => (
            <button
              key={m.id} type="button" role="option" aria-selected={m.id === value}
              className={'modelpick-item' + (m.id === value ? ' active' : '')}
              onClick={() => { onChange(m.id); setOpen(false); }}
            >
              <span className="n">{m.label}</span>
              {m.note && <span className="d">{m.note}</span>}
              {m.id === value && <span className="tick">✓</span>}
            </button>
          ))}
          {models.length === 0 && <div className="modelpick-item empty">No models available</div>}
        </div>
      )}
    </div>
  );
}

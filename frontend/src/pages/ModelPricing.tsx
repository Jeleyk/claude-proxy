import { useEffect, useState } from 'react';
import { api, ModelPrice } from '../api';
import { NumberInput } from '../ui';
import { SkeletonTable } from '../Skeleton';

type Field = { key: keyof Omit<ModelPrice, 'pattern'>; label: string; hint: string; unit: string; step: number };

// Per-1M-token prices. `cacheWrite1hPrice` is its own tier, not a variant of the 5m one:
// Anthropic charges 2× input for a 1-hour cache write against 1.25× for the default 5 minutes,
// and Claude Code writes its main-loop prefix with ttl:"1h" — so most cache-write spend lands
// in that column, not the one next to it.
const TOKEN_FIELDS: Field[] = [
  { key: 'inputPrice', label: 'Input', hint: 'prompt tokens', unit: '$ / 1M', step: 0.5 },
  { key: 'outputPrice', label: 'Output', hint: 'completion tokens', unit: '$ / 1M', step: 0.5 },
  { key: 'cacheReadPrice', label: 'Cache read', hint: 'cache hits', unit: '$ / 1M', step: 0.1 },
  { key: 'cacheWritePrice', label: 'Cache write 5m', hint: 'default TTL · ≈1.25× input', unit: '$ / 1M', step: 0.5 },
  { key: 'cacheWrite1hPrice', label: 'Cache write 1h', hint: 'extended TTL · ≈2× input', unit: '$ / 1M', step: 0.5 },
];

// Charges that aren't per token.
const EXTRA_FIELDS: Field[] = [
  { key: 'fastMultiplier', label: 'Fast mode', hint: 'premium tier on the same model', unit: '× all tokens', step: 0.25 },
  { key: 'webSearchPrice', label: 'Web search', hint: 'server-side search calls', unit: '$ / request', step: 0.01 },
];

const ALL_FIELDS = [...TOKEN_FIELDS, ...EXTRA_FIELDS];

const BLANK: Record<string, number> = {
  inputPrice: 0, outputPrice: 0, cacheReadPrice: 0, cacheWritePrice: 0,
  cacheWrite1hPrice: 0, fastMultiplier: 2, webSearchPrice: 0.01,
};

export function ModelPricing() {
  const [prices, setPrices] = useState<ModelPrice[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [np, setNp] = useState('');
  const [nv, setNv] = useState<Record<string, number>>({ ...BLANK });

  async function load() {
    try { setPrices(await api.modelPrices()); } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); }, []);

  async function add() {
    if (!np.trim()) return;
    try {
      setPrices(await api.setModelPrice({ pattern: np.trim().toLowerCase(), ...(nv as any) }));
      setNp(''); setNv({ ...BLANK });
    } catch (e: any) { setErr(e.message); }
  }

  return (
    <div className="main-inner">
      <h1>Model pricing</h1>
      <p className="sub">What each response costs: per-1M-token rates, plus the two charges that aren't per token.</p>
      {err && <div className="err">{err}</div>}

      <div className="panel">
        <p className="hint" style={{ marginTop: 0 }}>
          Matched by substring of the model id, longest match wins — <span className="mono">opus</span> matches{' '}
          <span className="mono">claude-opus-5</span>. Unknown models cost $0.
        </p>
        <p className="hint" style={{ marginTop: 6 }}>
          <b>cost</b> = (input×in + output×out + cache_read×cr + cache_write_5m×cw + cache_write_1h×cw1h) ÷ 1M,
          × the fast-mode multiplier when the response was served in fast mode, + web searches × their per-request price.
        </p>
        <p className="hint" style={{ marginTop: 6 }}>
          Claude Code caches its main-loop prefix at the <b>1h</b> TTL, so that column — not “Cache write 5m” —
          carries most of the cache spend. Leaving it at 0 would price those writes as free.
        </p>
        {!prices ? <SkeletonTable rows={4} cols={ALL_FIELDS.length + 2} /> : (
          <div className="tablewrap">
            <table>
              <thead>
                <tr>
                  <th>Model</th>
                  {ALL_FIELDS.map((f) => (
                    <th key={f.key} className="num" title={`${f.hint} · ${f.unit}`}>
                      {f.label}
                      <div className="hint" style={{ fontWeight: 400 }}>{f.unit}</div>
                    </th>
                  ))}
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {prices.map((p) => (
                  <PriceRow key={p.pattern} p={p}
                    onSave={async (np2) => setPrices(await api.setModelPrice(np2))}
                    onDelete={async (pat) => setPrices(await api.deleteModelPrice(pat))} />
                ))}
                {prices.length === 0 && <tr><td colSpan={ALL_FIELDS.length + 2} className="hint">No pricing rules — everything costs $0.</td></tr>}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="panel narrow">
        <h2 style={{ marginTop: 0 }}>Add / update a price</h2>
        <label className="field"><span>Model pattern (substring of the model id)</span>
          <input placeholder="e.g. opus" value={np} onChange={(e) => setNp(e.target.value)} />
        </label>
        <div className="grid2">
          {ALL_FIELDS.map((f) => (
            <label key={f.key} className="field">
              <span>{f.label} <span className="hint">· {f.unit} · {f.hint}</span></span>
              <NumberInput value={nv[f.key]} onChange={(v) => setNv((cur) => ({ ...cur, [f.key]: v }))} min={0} step={f.step} />
            </label>
          ))}
        </div>
        <p className="hint">
          Leave <b>Cache write 1h</b> at 0 to derive it as 2× input; a <b>Fast mode</b> of 0 is read as ×1 (no premium).
        </p>
        <button onClick={add} disabled={!np.trim()}>{prices?.some((p) => p.pattern === np.trim().toLowerCase()) ? 'Update price' : 'Add price'}</button>
      </div>
    </div>
  );
}

function PriceRow({ p, onSave, onDelete }: { p: ModelPrice; onSave: (p: ModelPrice) => void; onDelete: (pat: string) => void }) {
  const [v, setV] = useState<Record<string, number>>(() =>
    Object.fromEntries(ALL_FIELDS.map((f) => [f.key, (p as any)[f.key] ?? BLANK[f.key]])));
  const [dirty, setDirty] = useState(false);
  const set = (key: string, x: number) => { setV((cur) => ({ ...cur, [key]: x })); setDirty(true); };
  return (
    <tr>
      <td><span className="mono">{p.pattern}</span></td>
      {ALL_FIELDS.map((f) => (
        <td key={f.key} style={{ width: 104 }}><NumberInput value={v[f.key]} onChange={(x) => set(f.key, x)} min={0} step={f.step} /></td>
      ))}
      <td>
        <div className="row">
          {dirty && <button className="sm" onClick={() => { onSave({ pattern: p.pattern, ...(v as any) }); setDirty(false); }}>Save</button>}
          <button className="sm danger" onClick={() => onDelete(p.pattern)}>Delete</button>
        </div>
      </td>
    </tr>
  );
}

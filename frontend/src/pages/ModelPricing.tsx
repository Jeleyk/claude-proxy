import { useEffect, useState } from 'react';
import { api, ModelPrice } from '../api';
import { NumberInput } from '../ui';
import { SkeletonTable } from '../Skeleton';

const KINDS: { key: keyof Omit<ModelPrice, 'pattern'>; label: string; hint: string }[] = [
  { key: 'inputPrice', label: 'Input', hint: 'prompt tokens' },
  { key: 'outputPrice', label: 'Output', hint: 'completion tokens' },
  { key: 'cacheReadPrice', label: 'Cache read', hint: 'cache hits' },
  { key: 'cacheWritePrice', label: 'Cache write', hint: 'cache stores' },
];

export function ModelPricing() {
  const [prices, setPrices] = useState<ModelPrice[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [np, setNp] = useState('');
  const [nv, setNv] = useState<Record<string, number>>({ inputPrice: 0, outputPrice: 0, cacheReadPrice: 0, cacheWritePrice: 0 });

  async function load() {
    try { setPrices(await api.modelPrices()); } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); }, []);

  async function add() {
    if (!np.trim()) return;
    try {
      setPrices(await api.setModelPrice({ pattern: np.trim().toLowerCase(), ...(nv as any) }));
      setNp(''); setNv({ inputPrice: 0, outputPrice: 0, cacheReadPrice: 0, cacheWritePrice: 0 });
    } catch (e: any) { setErr(e.message); }
  }

  return (
    <div className="main-inner">
      <h1>Model pricing</h1>
      <p className="sub">USD per 1M tokens, per token kind. Used to compute each request's cost.</p>
      {err && <div className="err">{err}</div>}

      <div className="panel">
        <p className="hint" style={{ marginTop: 0 }}>
          Matched by substring of the model id (longest match wins). Cost = input×in + output×out + cache_read×cr + cache_write×cw, per 1M tokens.
          Example: <span className="mono">opus</span> matches <span className="mono">claude-opus-4-8</span>. Unknown models cost $0.
        </p>
        {!prices ? <SkeletonTable rows={4} cols={6} /> : (
          <div className="tablewrap">
            <table>
              <thead><tr><th>Model</th><th className="num">Input</th><th className="num">Output</th><th className="num">Cache read</th><th className="num">Cache write</th><th></th></tr></thead>
              <tbody>
                {prices.map((p) => (
                  <PriceRow key={p.pattern} p={p}
                    onSave={async (np2) => setPrices(await api.setModelPrice(np2))}
                    onDelete={async (pat) => setPrices(await api.deleteModelPrice(pat))} />
                ))}
                {prices.length === 0 && <tr><td colSpan={6} className="hint">No pricing rules — everything costs $0.</td></tr>}
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
          {KINDS.map((k) => (
            <label key={k.key} className="field">
              <span>{k.label} <span className="hint">· $ / 1M {k.hint}</span></span>
              <NumberInput value={nv[k.key]} onChange={(v) => setNv((cur) => ({ ...cur, [k.key]: v }))} min={0} step={0.5} />
            </label>
          ))}
        </div>
        <button onClick={add} disabled={!np.trim()}>{prices?.some((p) => p.pattern === np.trim().toLowerCase()) ? 'Update price' : 'Add price'}</button>
      </div>
    </div>
  );
}

function PriceRow({ p, onSave, onDelete }: { p: ModelPrice; onSave: (p: ModelPrice) => void; onDelete: (pat: string) => void }) {
  const [v, setV] = useState<Record<string, number>>({
    inputPrice: p.inputPrice, outputPrice: p.outputPrice, cacheReadPrice: p.cacheReadPrice, cacheWritePrice: p.cacheWritePrice,
  });
  const [dirty, setDirty] = useState(false);
  const set = (key: string, x: number) => { setV((cur) => ({ ...cur, [key]: x })); setDirty(true); };
  return (
    <tr>
      <td><span className="mono">{p.pattern}</span></td>
      {KINDS.map((k) => (
        <td key={k.key} style={{ width: 110 }}><NumberInput value={v[k.key]} onChange={(x) => set(k.key, x)} min={0} step={0.5} /></td>
      ))}
      <td>
        <div className="row">
          {dirty && <button className="sm" onClick={() => { onSave({ pattern: p.pattern, inputPrice: v.inputPrice, outputPrice: v.outputPrice, cacheReadPrice: v.cacheReadPrice, cacheWritePrice: v.cacheWritePrice }); setDirty(false); }}>Save</button>}
          <button className="sm danger" onClick={() => onDelete(p.pattern)}>Delete</button>
        </div>
      </td>
    </tr>
  );
}

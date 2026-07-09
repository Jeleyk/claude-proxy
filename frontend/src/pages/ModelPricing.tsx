import { useEffect, useState } from 'react';
import { api, ModelPrice } from '../api';

export function ModelPricing() {
  const [prices, setPrices] = useState<ModelPrice[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [np, setNp] = useState('');
  const [nv, setNv] = useState<[number, number, number, number]>([0, 0, 0, 0]);

  async function load() {
    try { setPrices(await api.modelPrices()); } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); }, []);

  async function add() {
    if (!np.trim()) return;
    try {
      setPrices(await api.setModelPrice({ pattern: np.trim().toLowerCase(), inputPrice: nv[0], outputPrice: nv[1], cacheReadPrice: nv[2], cacheWritePrice: nv[3] }));
      setNp(''); setNv([0, 0, 0, 0]);
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
        <div className="tablewrap" style={{ marginBottom: 12 }}>
          <table>
            <thead><tr><th>Model</th><th>Input</th><th>Output</th><th>Cache read</th><th>Cache write</th><th></th></tr></thead>
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
        <div className="row wrap">
          <input placeholder="pattern (e.g. opus)" value={np} onChange={(e) => setNp(e.target.value)} style={{ maxWidth: 160 }} />
          {['input', 'output', 'cache read', 'cache write'].map((lbl, i) => (
            <input key={i} type="number" step="0.5" min="0" placeholder={lbl} value={nv[i]}
              onChange={(e) => setNv((v) => { const c = [...v] as any; c[i] = +e.target.value; return c; })} style={{ maxWidth: 110 }} />
          ))}
          <button onClick={add}>Add</button>
        </div>
      </div>
    </div>
  );
}

function PriceRow({ p, onSave, onDelete }: { p: ModelPrice; onSave: (p: ModelPrice) => void; onDelete: (pat: string) => void }) {
  const [v, setV] = useState<[number, number, number, number]>([p.inputPrice, p.outputPrice, p.cacheReadPrice, p.cacheWritePrice]);
  const [dirty, setDirty] = useState(false);
  const set = (i: number, x: number) => { setV((cur) => { const c = [...cur] as any; c[i] = x; return c; }); setDirty(true); };
  return (
    <tr>
      <td><span className="mono">{p.pattern}</span></td>
      {[0, 1, 2, 3].map((i) => (
        <td key={i} style={{ width: 100 }}><input type="number" step="0.5" min="0" value={v[i]} onChange={(e) => set(i, +e.target.value)} /></td>
      ))}
      <td>
        <div className="row">
          {dirty && <button className="sm" onClick={() => { onSave({ pattern: p.pattern, inputPrice: v[0], outputPrice: v[1], cacheReadPrice: v[2], cacheWritePrice: v[3] }); setDirty(false); }}>Save</button>}
          <button className="sm danger" onClick={() => onDelete(p.pattern)}>Delete</button>
        </div>
      </td>
    </tr>
  );
}

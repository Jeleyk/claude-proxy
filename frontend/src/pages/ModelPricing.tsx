import { useEffect, useState } from 'react';
import { api, ModelPrice } from '../api';

export function ModelPricing() {
  const [prices, setPrices] = useState<ModelPrice[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [np, setNp] = useState('');
  const [ni, setNi] = useState(0);
  const [no, setNo] = useState(0);

  async function load() {
    try { setPrices(await api.modelPrices()); } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); }, []);

  async function add() {
    if (!np.trim()) return;
    try { setPrices(await api.setModelPrice(np.trim().toLowerCase(), ni, no)); setNp(''); setNi(0); setNo(0); }
    catch (e: any) { setErr(e.message); }
  }

  return (
    <div className="main-inner">
      <h1>Model pricing</h1>
      <p className="sub">USD per 1M tokens per model. Used to compute the cost of each request.</p>
      {err && <div className="err">{err}</div>}

      <div className="panel narrow">
        <p className="hint" style={{ marginTop: 0 }}>
          A request's model id is matched by substring (longest match wins). Cost = input×in$ + output×out$, per million tokens.
          Example: <span className="mono">opus</span> matches <span className="mono">claude-opus-4-8</span>. Unknown models cost $0.
        </p>
        <div className="tablewrap" style={{ marginBottom: 12 }}>
          <table>
            <thead><tr><th>Model pattern</th><th>Input $/1M</th><th>Output $/1M</th><th></th></tr></thead>
            <tbody>
              {prices.map((p) => <PriceRow key={p.pattern} p={p} onSave={async (pat, i, o) => setPrices(await api.setModelPrice(pat, i, o))} onDelete={async (pat) => setPrices(await api.deleteModelPrice(pat))} />)}
              {prices.length === 0 && <tr><td colSpan={4} className="hint">No pricing rules — everything costs $0.</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="row">
          <input placeholder="pattern (e.g. opus)" value={np} onChange={(e) => setNp(e.target.value)} style={{ maxWidth: 180 }} />
          <input type="number" step="0.5" min="0" placeholder="input $" value={ni} onChange={(e) => setNi(+e.target.value)} style={{ maxWidth: 110 }} />
          <input type="number" step="0.5" min="0" placeholder="output $" value={no} onChange={(e) => setNo(+e.target.value)} style={{ maxWidth: 110 }} />
          <button onClick={add}>Add</button>
        </div>
      </div>
    </div>
  );
}

function PriceRow({ p, onSave, onDelete }: { p: ModelPrice; onSave: (pat: string, i: number, o: number) => void; onDelete: (pat: string) => void }) {
  const [i, setI] = useState(p.inputPrice);
  const [o, setO] = useState(p.outputPrice);
  const [dirty, setDirty] = useState(false);
  return (
    <tr>
      <td><span className="mono">{p.pattern}</span></td>
      <td style={{ width: 120 }}><input type="number" step="0.5" min="0" value={i} onChange={(e) => { setI(+e.target.value); setDirty(true); }} /></td>
      <td style={{ width: 120 }}><input type="number" step="0.5" min="0" value={o} onChange={(e) => { setO(+e.target.value); setDirty(true); }} /></td>
      <td>
        <div className="row">
          {dirty && <button className="sm" onClick={() => { onSave(p.pattern, i, o); setDirty(false); }}>Save</button>}
          <button className="sm danger" onClick={() => onDelete(p.pattern)}>Delete</button>
        </div>
      </td>
    </tr>
  );
}

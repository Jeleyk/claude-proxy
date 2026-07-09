import { useEffect, useState } from 'react';
import { api, fmtTokens, ModelCoeff } from '../api';

export function TokenRules() {
  const [tpp, setTpp] = useState(10000);
  const [tppVal, setTppVal] = useState(10000);
  const [coeffs, setCoeffs] = useState<ModelCoeff[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const [np, setNp] = useState('');
  const [nc, setNc] = useState(1);

  async function load() {
    try {
      const c = await api.config(); setTpp(c.tokensPerWindowPercent); setTppVal(c.tokensPerWindowPercent);
      setCoeffs(await api.modelCoeffs());
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); }, []);

  async function saveTpp() {
    try { const c = await api.updateSettings({ tokensPerWindowPercent: tppVal }); setTpp(c.tokensPerWindowPercent); setSaved(true); setTimeout(() => setSaved(false), 1400); }
    catch (e: any) { setErr(e.message); }
  }
  async function addCoeff() {
    if (!np.trim()) return;
    try { setCoeffs(await api.setModelCoeff(np.trim().toLowerCase(), nc)); setNp(''); setNc(1); }
    catch (e: any) { setErr(e.message); }
  }
  async function updateCoeff(pattern: string, coefficient: number) {
    try { setCoeffs(await api.setModelCoeff(pattern, coefficient)); } catch (e: any) { setErr(e.message); }
  }
  async function delCoeff(pattern: string) {
    try { setCoeffs(await api.deleteModelCoeff(pattern)); } catch (e: any) { setErr(e.message); }
  }

  return (
    <div className="main-inner">
      <h1>Token rules</h1>
      <p className="sub">Configure how tokens map to window-percent, and per-model dirty-token multipliers.</p>
      {err && <div className="err">{err}</div>}

      <div className="panel narrow">
        <h2 style={{ marginTop: 0 }}>Tokens per 1% of a normal window</h2>
        <p className="hint" style={{ marginTop: -4 }}>
          Approximate raw tokens that equal <b>1%</b> of a normal (×1) account window. Used to translate token budgets
          to/from pseudo session-percent. An account with tier coefficient ×5 is treated as 5× this.
        </p>
        <div className="row">
          <input type="number" style={{ maxWidth: 160 }} value={tppVal} onChange={(e) => setTppVal(+e.target.value)} />
          <span className="hint">100% ≈ {fmtTokens(tppVal * 100)} tokens</span>
          <button className="right" onClick={saveTpp}>{saved ? '✓ Saved' : 'Save'}</button>
        </div>
      </div>

      <div className="panel narrow">
        <h2 style={{ marginTop: 0 }}>Model dirty-token multipliers</h2>
        <p className="hint" style={{ marginTop: -4 }}>
          A request's model id is matched by substring (longest match wins). Dirty tokens = raw tokens × multiplier.
          Examples: <span className="mono">opus</span> matches <span className="mono">claude-opus-4-8</span>, <span className="mono">opus-4.8</span>.
        </p>
        <div className="tablewrap" style={{ marginBottom: 12 }}>
          <table>
            <thead><tr><th>Model pattern</th><th>Multiplier</th><th></th></tr></thead>
            <tbody>
              {coeffs.map((c) => (
                <CoeffRow key={c.pattern} c={c} onSave={updateCoeff} onDelete={delCoeff} />
              ))}
              {coeffs.length === 0 && <tr><td colSpan={3} className="hint">No rules — everything defaults to ×1.</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="row">
          <input placeholder="pattern (e.g. opus)" value={np} onChange={(e) => setNp(e.target.value)} style={{ maxWidth: 200 }} />
          <input type="number" step="0.5" min="0" value={nc} onChange={(e) => setNc(+e.target.value)} style={{ maxWidth: 110 }} />
          <button onClick={addCoeff}>Add rule</button>
        </div>
      </div>
    </div>
  );
}

function CoeffRow({ c, onSave, onDelete }: { c: ModelCoeff; onSave: (p: string, v: number) => void; onDelete: (p: string) => void }) {
  const [v, setV] = useState(c.coefficient);
  const [dirty, setDirty] = useState(false);
  return (
    <tr>
      <td><span className="mono">{c.pattern}</span></td>
      <td style={{ width: 130 }}>
        <input type="number" step="0.5" min="0" value={v} onChange={(e) => { setV(+e.target.value); setDirty(true); }} />
      </td>
      <td>
        <div className="row">
          {dirty && <button className="sm" onClick={() => { onSave(c.pattern, v); setDirty(false); }}>Save</button>}
          <button className="sm danger" onClick={() => onDelete(c.pattern)}>Delete</button>
        </div>
      </td>
    </tr>
  );
}

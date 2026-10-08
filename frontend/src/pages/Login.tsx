import { useEffect, useState } from 'react';
import { api, OidcPublicConfig, UserDto } from '../api';

// The callback only ever sends one of these fixed codes; anything else is shown generically.
const SSO_ERRORS: Record<string, string> = {
  idp_error: 'Sign-in was cancelled or refused by the identity provider.',
  invalid_request: 'The sign-in attempt expired or was not started here. Please try again.',
  token_error: 'The identity provider response could not be verified.',
  forbidden: 'Your account is not allowed to use this panel.',
  no_account: 'No panel account is linked to this identity. Ask an administrator.',
  pending: 'Your account was created and is waiting for an administrator to approve it.',
  unavailable: 'The identity provider is unreachable. Try again later.',
};

export function Login({ onLogin }: { onLogin: (u: UserDto) => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [sso, setSso] = useState<OidcPublicConfig | null>(null);

  useEffect(() => {
    api.oidcConfig().then(setSso).catch(() => setSso({ enabled: false, passwordLogin: true }));
    const params = new URLSearchParams(window.location.search);
    const code = params.get('sso_error');
    if (code) {
      setErr(SSO_ERRORS[code] ?? 'SSO sign-in failed.');
      params.delete('sso_error');
      const rest = params.toString();
      window.history.replaceState(null, '', window.location.pathname + (rest ? `?${rest}` : ''));
    }
  }, []);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr(null);
    setBusy(true);
    try {
      const u = await api.login(username, password);
      onLogin(u);
    } catch (e: any) {
      setErr(e.message || 'Login failed');
    } finally {
      setBusy(false);
    }
  }

  // Until the SSO config arrives, show neither form nor button — avoids flashing a password form
  // that SSO-only deployments then hide.
  const passwordLogin = sso !== null && sso.passwordLogin;

  return (
    <div className="login-wrap">
      <form className="panel login-card" onSubmit={submit}>
        <div className="brand" style={{ padding: '0 0 14px' }}>claude-proxy</div>
        {sso?.enabled && sso.startUrl && (
          // A full navigation (not fetch): the server redirects to the IdP. startUrl is the
          // callback's origin, so the transaction cookie lands on the host the IdP returns to.
          <button type="button" style={{ width: '100%', marginBottom: passwordLogin ? 14 : 0 }}
            onClick={() => window.location.assign(sso.startUrl!)}>{sso.buttonLabel || 'Sign in with SSO'}</button>
        )}
        {passwordLogin && (
          <>
            <label className="field">
              <span>Username</span>
              <input value={username} onChange={(e) => setUsername(e.target.value)} autoFocus={!sso?.enabled} />
            </label>
            <label className="field">
              <span>Password</span>
              <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
            </label>
          </>
        )}
        {err && <div className="err">{err}</div>}
        {passwordLogin && <button disabled={busy} type="submit">{busy ? '…' : 'Sign in'}</button>}
      </form>
    </div>
  );
}

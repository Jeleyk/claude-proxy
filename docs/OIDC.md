# Single sign-on (OIDC) for the panel

Optional and **off by default**. When enabled, the panel's login page gets a "Sign in with SSO"
button that runs an OpenID Connect Authorization Code flow with PKCE against any standard
provider (Authentik, Keycloak, Okta, Entra ID, Google, Dex, …). A successful login creates the
same signed session cookie a password login does.

Unchanged by this feature: `cxp_`/`cxr_` API token authentication, the gateways and `/internal/*`.

Present even with OIDC off: the `users.email` column and `user_identities` table (additive
schema), the optional Email field on users, the **Offboard** action
(`POST /api/users/{id}/offboard`), SSO unlink (`DELETE /api/users/{id}/identities`),
`GET /api/auth/oidc/config` (answers `enabled:false`) and `SESSION_COOKIE_SAMESITE` (default `Lax`,
which is what current browsers already assume for a cookie without the attribute).

## How it works

```
browser ── GET /api/auth/oidc/start ──► service: new transaction {state, nonce, PKCE verifier}
        ◄── 302 to IdP authorize (S256 challenge, state, nonce) + HttpOnly txn cookie
browser ── authenticates at the IdP ──► 302 back to OIDC_REDIRECT_URI?code&state
browser ── GET /api/auth/oidc/callback ─► service: consume txn (cookie + state, single use)
                                          POST code + verifier + client secret to token endpoint
                                          validate ID token (JWKS signature, iss, aud/azp, exp, nonce)
                                          gates → account match → panel session
        ◄── 302 to /  (or /?sso_error=<fixed code>)
```

Protocol work (discovery, the token request, JOSE/JWKS validation) is done by the
[Nimbus OAuth 2.0 / OIDC SDK](https://connect2id.com/products/nimbus-oauth-openid-connect-sdk) —
no hand-rolled crypto.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `OIDC_ENABLED` | `false` | Master switch. Everything below is ignored unless `true`. |
| `OIDC_ISSUER` | — (required) | Issuer URL; discovery at `<issuer>/.well-known/openid-configuration`, the document's `issuer` must match exactly. HTTPS required (plain http only for localhost). |
| `OIDC_CLIENT_ID` | — (required) | Client id of a **confidential** client. |
| `OIDC_CLIENT_SECRET_FILE` / `OIDC_CLIENT_SECRET` | — (one required) | Prefer the file (mode 0600, e.g. under the `./data:/data` mount). The secret is never logged. |
| `OIDC_REDIRECT_URI` | — (required) | Must be exactly `<panel origin>/api/auth/oidc/callback` and registered verbatim (strict match) at the IdP. |
| `OIDC_SCOPES` | `openid email profile` | Must include `openid`. |
| `OIDC_GROUPS_CLAIM` | `groups` | Top-level claim holding group names (array or string). |
| `OIDC_ALLOWED_GROUPS` | empty | Comma list; login requires membership in at least one (exact, case-sensitive). Empty = no group gate (a startup warning is logged). |
| `OIDC_ALLOWED_EMAIL_DOMAINS` | empty | Comma list; login requires a **verified** email whose domain matches exactly (no subdomains). |
| `OIDC_LINK_BY_EMAIL` | `false` | Allow a first login to claim an existing local user by verified email (rules below). |
| `OIDC_JIT_PROVISIONING` | `false` | Create local accounts for unknown identities. Requires a non-empty `OIDC_ALLOWED_GROUPS`. |
| `OIDC_JIT_ROLE` | empty | Empty = JIT accounts are created **disabled and without any role** (a pending request; the user sees "waiting for approval" and an admin enables it and picks a role). Set to an existing role name to make JIT accounts usable at once — note that the built-in `user` role can mint `cxp_`/`cxr_` tokens and spend the shared pool (ungrouped accounts are open to every user with `POOL_GLOBAL_USE`), so create a narrower role if that is not intended. |
| `OIDC_BLOCK_PASSWORD_FOR_LINKED` | `true` | While OIDC is active, users with a linked SSO identity cannot use password login (403 after a correct password) — otherwise removing them at the IdP would not end their access. Unlinked users (break-glass admin, service accounts) keep password login. |
| `OIDC_SESSION_TTL_HOURS` | `12` | Server-enforced lifetime of SSO sessions (1…720). Password sessions keep 30 days. |
| `OIDC_BUTTON_LABEL` | `Sign in with SSO` | Login button text. |
| `OIDC_DISABLE_PASSWORD_LOGIN` | `false` | `POST /api/auth/login` answers 403. Break-glass = unset and restart. |

An invalid OIDC configuration (missing value, http issuer, wrong callback path, unreadable secret
file, JIT without a group gate) **disables OIDC** and logs an ERROR at startup — it does not stop
the service, because the same process serves the datapath control API. With OIDC disabled the
button disappears and password login works for everyone (including linked users): that is the
break-glass path. An unreachable IdP doesn't stop startup either — discovery is lazy, the button
ends on `sso_error=unavailable`, and an hourly metadata refresh that fails keeps using the last good
metadata/JWKS.

Related core setting: `SESSION_COOKIE_SAMESITE` = `lax` (default) or `strict` for the panel session
cookie. Both work with SSO — the post-login redirect is a top-level navigation and the SPA's API
calls are same-site.

The redirect URI's origin is what the browser must use: cookies are host-bound, so the button
starts the flow on that origin (`/api/auth/oidc/config` returns it as `startUrl`). The callback is
the only URL the IdP needs to know; it is reached by the **browser**, so it may be an internal
name (e.g. a VPN-only panel) as long as users' browsers resolve it.

HTTPS is strongly preferred. A plain-http redirect URI is accepted for private deployments (OIDC
Core allows it for confidential clients): then the authorization code, the transaction cookie
and the session cookie cross the network unencrypted and without the `Secure` flag, so only do
this when the network itself is encrypted and access-controlled (e.g. WireGuard/Tailscale with
ACLs). PKCE and the client secret still make a sniffed code useless on its own; a sniffed
**session cookie** is not.

Logging: the callback URL carries `code` and `state`. The service never logs them, but a reverse
proxy's access log does by default — shorten retention or exclude `/api/auth/oidc/callback` from
access logs (or log `$uri` instead of `$request`). Codes are single-use and short-lived, and are
useless without the PKCE verifier and client secret, but they should not linger.

## Account matching

Applied in this order on every login:

1. **Gates**: allowed groups, allowed email domains. They apply to already linked users too, so
   removing someone from the IdP group locks them out at their next login.
2. **Existing link**: `(issuer, sub)` → local user. This is the only identity key; username and
   email are never used to recognise a returning user. A disabled local user is refused.
3. **Link by email** (`OIDC_LINK_BY_EMAIL=true` only): if the ID token (or userinfo for the same
   `sub`) says `email_verified: true`, and exactly one local user has that admin-set email
   (Users → Edit → Email, stored lower-case, unique), that user is enabled and has no identity for
   this issuer yet — link and log in. A user already linked to another subject is never re-pointed.
4. **JIT** (`OIDC_JIT_PROVISIONING=true` only): create a user with **no account-group access**, a
   random unusable password, the verified email if unused, and a sanitised ASCII username
   (suffixed on collision — an IdP `preferred_username` of `admin` becomes `admin-2`). By default
   the account is disabled and role-less until an admin approves it (`sso_error=pending`); with
   `OIDC_JIT_ROLE` it gets that role and is usable immediately.
5. Otherwise: `sso_error=no_account`.

Admins can drop a user's SSO links (Users → "Unlink SSO", `DELETE /api/users/{id}/identities`),
e.g. after a wrong link; the next SSO login goes through steps 3–5 again.

## Offboarding

- **At the IdP** (remove from the group / app binding / disable): new panel logins stop
  immediately; an open SSO session ends within `OIDC_SESSION_TTL_HOURS`.
- **In the panel** — Users → **Offboard**, or `POST /api/users/{id}/offboard` (USERS_MANAGE):
  disables the user, bumps the session generation (every panel session ends now) and **deletes**
  all their `cxp_` and `cxr_` tokens, evicting the resolve caches so every datapath (Go gateway
  via `/internal/resolve`, `/gateway/v1/usage`, the Kotlin rollback path) answers 401 on the next
  request. Each datapath also re-checks the owner's live enabled state, so even a stale cache
  entry cannot serve a disabled user.

  Re-enabling an offboarded user later restores panel access only (password unchanged, SSO link
  kept); they have no tokens and must create new ones. Any `USERS_MANAGE` holder can offboard any
  other user, admins included — the same reach the existing user edit/disable already has.

  The endpoint is cookie-authenticated and body-less, so it requires an `X-Requested-With`
  header and, if the browser sends `Origin`, a same-host origin (CSRF guard):

  ```bash
  curl -X POST -H 'X-Requested-With: curl' -b "CLAUDE_PROXY_SESSION=…" \
    https://proxy.example.com/api/users/42/offboard
  # {"userId":42,"proxyTokensRevoked":2,"routingTokensRevoked":1}
  ```

API tokens are independent of the IdP: an IdP-side removal alone does **not** revoke them. Run
the offboard action as part of your leaver process. Password login is not a back door for linked
users while `OIDC_BLOCK_PASSWORD_FOR_LINKED` is on (default); if you turn it off, or the user was
never linked, an IdP removal leaves their password working — Offboard is then mandatory. (Disabling a user via the regular user edit
also stops their tokens — a disabled owner's tokens never resolve — but leaves them in place.)

## Example: Authentik

1. Applications → Providers → **OAuth2/OpenID Provider**: client type *Confidential*; redirect
   URI (strict) `https://proxy.example.com/api/auth/oidc/callback`; signing key = a certificate
   (RS256); scopes `openid`, `email`, `profile` (Authentik's `profile` mapping carries `groups`);
   subject mode e.g. *hashed user id*. Authentik ≥ 2026.8: make sure grant types include
   `authorization_code` (and `refresh_token`), otherwise login fails with `Invalid grant_type`.
2. Applications → **Application** with slug `claude-proxy` bound to that provider; bind the
   groups that may use it (an application without bindings is open to every Authentik user).
3. Service `.env`:

   ```
   OIDC_ENABLED=true
   OIDC_ISSUER=https://sso.example.com/application/o/claude-proxy/
   OIDC_CLIENT_ID=<client id>
   OIDC_CLIENT_SECRET_FILE=/data/oidc_client_secret
   OIDC_REDIRECT_URI=https://proxy.example.com/api/auth/oidc/callback
   OIDC_ALLOWED_GROUPS=claude-proxy-user
   ```

Authentik's built-in `email` scope mapping sends `email_verified: false`. Email linking and the
domain gate therefore need either a custom email mapping that asserts verification only where
your IdP really guarantees it (e.g. emails from a Workspace source that users cannot edit), or
leaving email linking off and relying on JIT provisioning.

## Threat model

| Threat | Mitigation |
|---|---|
| Login CSRF / session fixation via a forged callback | `state` is bound to a random transaction id in an HttpOnly cookie and compared in constant time; transactions are single-use and expire after 10 min; the session cookie is (re)issued only after full validation. |
| Authorization-code interception / replay | PKCE S256 (verifier never leaves the server); confidential client secret; single-use transaction; IdP enforces single-use codes. |
| ID-token forgery / substitution | Signature checked against the issuer's JWKS; only asymmetric algorithms (no `none`, no HS* keyed with the client secret — even if the IdP advertises HS256 or publishes a symmetric key); `iss`, `aud`, `exp`/`iat` (60 s skew) and `nonce` validated by Nimbus; `azp` checked explicitly (required with several audiences, must equal the client id when present). Userinfo is only used to fill missing claims and only if its `sub` matches. |
| Mix-up / rogue discovery | Issuer pinned by config and must equal discovery `issuer`; HTTPS required. |
| Account takeover via email or username collision | Returning users match on `(iss, sub)` only. Email linking is opt-in, requires `email_verified`, admin-set unique email, an unlinked enabled target, and never re-points an existing link. JIT never reuses a username; by default it creates a disabled, role-less account an admin must approve. |
| CSRF on new admin actions | Offboard/unlink require `X-Requested-With` plus a same-host `Origin`; the session cookie is SameSite=Lax (configurable Strict). Existing JSON endpoints were already protected by their content type. |
| Open redirect | Success always redirects to `/`; failures to `/?sso_error=<fixed code>`; no return-to parameter. |
| Token / secret leakage | No codes or tokens in logs or URLs the app builds; IdP error text is not reflected; callback responses send `Cache-Control: no-store` and `Referrer-Policy: no-referrer`; client secret redacted from config logging, readable from a 0600 file. |
| Leaver keeps access | IdP gates run on every login; linked users cannot fall back to password login; SSO sessions are short-lived; Offboard revokes sessions and all API tokens at once; disabled owners' tokens are rejected on every datapath. |
| DoS on `/start` | In-memory transaction store bounded to 10 000 live entries (503 beyond); a transaction is dropped at once when discovery fails. Rate-limit per client at the reverse proxy, e.g. `limit_req_zone $binary_remote_addr zone=oidc:1m rate=10r/m;` and `location /api/auth/oidc/ { limit_req zone=oidc burst=10 nodelay; proxy_pass …; }`. |
| IdP outage | Lazy discovery (service still boots); password login remains available unless explicitly disabled. |

Cookie details: the transaction cookie is `HttpOnly; SameSite=Lax; Path=/api/auth/oidc;
Max-Age=600` and `Secure` when the redirect URI is HTTPS. It is Lax rather than Strict on
purpose — the callback is a cross-site top-level navigation from the IdP and a Strict cookie would
not be sent on it. The panel session cookie itself is unchanged.

Rollback: the schema is additive and `user_identities.user_id` is `ON DELETE CASCADE`, so the
previous release boots on the upgraded database and can still delete users that have SSO links.

Out of scope / follow-ups: RP-initiated logout, back-channel logout, SCIM or a signed webhook for
automated offboarding (today: call the offboard API with an admin session), and periodic
re-validation of IdP group membership during an open session (bounded by the SSO session TTL).
Transactions are in-process (the service is single-instance by design); a restart only aborts
logins that are mid-redirect.

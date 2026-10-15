# Panel SSO (OIDC)

Optional and off by default. The panel uses OpenID Connect Authorization Code + PKCE with a
configurable issuer; API tokens (`cxp_`/`cxr_`) and the gateways do not use SSO. When enabled,
the login page shows an SSO button alongside password login unless password login is disabled.

## Setup

Register a confidential OIDC client with your provider. Allow the exact callback URL
`https://proxy.example.com/api/auth/oidc/callback` (replace the origin with your panel's origin).
Configure the service:

```dotenv
OIDC_ENABLED=true
OIDC_ISSUER=https://sso.example.com/
OIDC_CLIENT_ID=<client id>
OIDC_CLIENT_SECRET_FILE=/data/oidc_client_secret
OIDC_REDIRECT_URI=https://proxy.example.com/api/auth/oidc/callback
OIDC_ALLOWED_GROUPS=proxy-users
```

The issuer must match the discovery document's `issuer`; it normally requires HTTPS (HTTP is
allowed for loopback). Keep the client secret in a readable, restricted file.
`OIDC_ALLOWED_GROUPS` is strongly recommended: without it, the provider's application binding
is the only group gate. Claim names and values depend on your provider.

| Option | Default | Effect |
|---|---|---|
| `OIDC_SCOPES` | `openid email profile` | Requested scopes; must include `openid`. |
| `OIDC_GROUPS_CLAIM` | `groups` | Group claim name. |
| `OIDC_ALLOWED_GROUPS` | empty | Comma-separated, exact group names; empty disables this gate. |
| `OIDC_ALLOWED_EMAIL_DOMAINS` | empty | Comma-separated domains; requires `email_verified=true` when set. Empty allows any domain. |
| `OIDC_LINK_BY_EMAIL` | `false` | First login may link an existing, unlinked account with a matching admin-set email only if the provider asserts `email_verified=true`. |
| `OIDC_JIT_PROVISIONING` | `false` | Create accounts for unknown identities; requires allowed groups. |
| `OIDC_JIT_ROLE` | empty | Empty: JIT accounts are disabled and role-less until approved. A named existing role grants immediate access. |
| `OIDC_BLOCK_PASSWORD_FOR_LINKED` | `true` | Refuse password login for SSO-linked users, even when OIDC is disabled or misconfigured. |
| `OIDC_DISABLE_PASSWORD_LOGIN` | `false` | Disable password login for everyone while OIDC is enabled. |
| `OIDC_SESSION_TTL_HOURS` | `12` | Server-enforced SSO session lifetime. |
| `OIDC_METADATA_MAX_STALE_HOURS` | `24` | Maximum age for cached IdP metadata during an outage (`0`–`168`, `0` disables stale use). |
| `OIDC_BUTTON_LABEL` | `Sign in with SSO` | Login button text. |
| `SESSION_COOKIE_SAMESITE` | `lax` | Panel session cookie: `lax` or `strict`; other values fall back to Strict. |

`OIDC_CLIENT_SECRET` may be used instead of `OIDC_CLIENT_SECRET_FILE`. See `.env.example` for
the complete variable list. A bad OIDC configuration disables SSO and logs an error, but does
not stop the service. An unavailable provider blocks new SSO logins, not the datapath.

## Accounts and access

Returning users are matched by `(issuer, sub)`, never by username or email. Group and domain
gates are checked on every SSO login. Email linking and JIT are opt-in; only enable email linking
if your provider reliably verifies ownership of the email. Linking revokes the user's existing
panel sessions. Keep an unlinked local admin with a password for break-glass access before linking
admins or disabling password login. Setting `OIDC_BLOCK_PASSWORD_FOR_LINKED=false` requires a
restart and restores password access to linked users.

A provider-side removal prevents the next SSO login, but does **not** end an existing session
immediately or revoke API tokens. To revoke access now, use Users → **Offboard**
(`POST /api/users/{id}/offboard`): it disables the account, revokes panel sessions and deletes its
`cxp_`/`cxr_` tokens. Re-enabling the account does not restore those tokens. Users with
`USERS_MANAGE` can offboard other users, including admins. The endpoint and SSO unlink
(`DELETE /api/users/{id}/identities`) require `X-Requested-With` and reject a foreign `Origin`.

## Deployment notes

- Use HTTPS for the panel. HTTP redirects are accepted for private deployments, but session
  cookies then lack `Secure`; use an encrypted, access-controlled network.
- The callback query contains an authorization code and state. Avoid storing its full query in
  reverse-proxy access logs. Rate-limit `/api/auth/oidc/` if exposed publicly.
- OIDC transactions live in one service process; restarting it aborts logins in progress.
- The schema additions (`users.email` and `user_identities`) are additive. The previous service
  version boots against the upgraded schema, but linked users remain blocked from password login
  on the new version by default. For rollback, preserve an unlinked admin and the old service artifact.

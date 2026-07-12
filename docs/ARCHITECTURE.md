# Architecture

An nginx router (in `docker-compose`) fronts three components on one origin:

- **Datapath** — `/gateway/{...}` (nginx strips the prefix → the service sees `/v1/{...}`)
  transparently proxies the Anthropic API for Claude Code. Served by the Kotlin **service**
  today; a Go **gateway** in Spec B (nginx flips one `proxy_pass`).
- **Management API** — `/api/*` REST endpoints on the Kotlin **service**.
- **SPA** — the React admin UI, static files served by nginx at `/` (react-router).

The Kotlin service (`service/`) still owns all business logic; it no longer serves the UI.

## Request lifecycle (datapath)

`proxy/ProxyRoutes.kt` → `ProxyEngine.handle` → `UpstreamForwarder.forward`:

1. **Inbound auth.** Extract the proxy token from `Authorization: Bearer` or `x-api-key`;
   resolve it to a user (`ProxyTokenRepo`, SHA-256 lookup). Require `PROXY_USE`.
2. **Scope.** Admins may use any account; others are limited to their granted account groups
   (ungrouped accounts are always available).
3. **Read body** once into a `ByteArray` (buffered so it can be replayed across accounts).
4. **Free paths** (`count_tokens`, `/v1/models`) bypass limit checks and use any account.
5. **Daily USD limit.** If the user has one and today's spend ≥ limit → `429` with headers.
6. **Account selection.** `AccountPool.selectionOrder(allowedGroups)` returns candidates in
   order (under-threshold by priority first, then over-threshold for fallback).
7. **Forward with retry.** For each candidate, `UpstreamForwarder.forward` swaps in the
   account's credentials and calls Anthropic. `429`/`5xx`/`401` on a non-last account → try the
   next; the **last** account's real response (including `429`/`5xx` + `retry-after`) is passed
   straight through so the client sees the true status.
8. **Relay + meter.** On success the response is streamed back:
   - **SSE** (`text/event-stream`): flush the head immediately, then stream chunks with
     `readAvailable` while `SseUsageScanner` tees token counts out of the stream, injecting
     `: keep-alive\n\n` comments during upstream silence (see `CLAUDE.md` for why this matters).
   - **JSON** (single message): buffered, usage parsed from the `usage` object.
   - Usage + USD cost recorded via `UsageRepo`/`ModelPriceRepo`.
9. **Live limits.** Every upstream response's `anthropic-ratelimit-*` headers update the
   account's window state (`RateLimitHeaders` → `AccountPool.updateLimit` → `WindowSnapshotRepo`).

## Account selection & rotation

- Accounts have `priority`, `threshold` (0..1), `coefficient` (×1/×5/×20 capacity weight),
  `enabled`, `health`, and per-window live `utilization`.
- **Usage fraction** driving selection = max utilization across known windows (`LimitState`).
- Order: accounts **under** their threshold first (by priority), then accounts **over**
  threshold (**fallback**), then park hard rate-limited ones until `rateLimitedUntil`.
- `TokenRefresher` refreshes OAuth access tokens in the background; `LimitScheduler`/`LimitProbe`
  periodically probe accounts to keep window state fresh even when idle.

## Data model (Exposed, `db/Tables.kt`)

- `users`, `roles`, `role_permissions`, `user_roles` — RBAC.
- `accounts` (priority/threshold/coefficient/health/type/groupId/clientId) + `account_secrets`
  (AES-256-GCM ciphertext), `account_limits` (per-window utilization), `window_snapshots`
  (time series for the usage graphs).
- `account_groups`, `user_group_access` — per-user routing scope.
- `proxy_tokens` (SHA-256 hash + owner).
- `usage_events` (input/output/cache_read/cache_write tokens, cost, model, httpStatus, userId,
  accountId), `model_prices` (4 prices per model pattern).
- `settings`, `oauth_add_sessions` (PKCE state for "Login with Claude").

Postgres in production (`DATABASE_URL` set); SQLite fallback otherwise. Use Exposed `upsert`
(cross-DB); clear referencing rows before deletes (Postgres FKs).

## Cost model

Per model pattern, `model_prices` stores USD-per-million-token prices for input, output,
cache_read, cache_write. `ModelPriceRepo.costOf(model, …)` computes each event's cost from the
response token breakdown; per-user daily spend is summed from `usage_events` since 00:00 UTC and
compared to the user's optional `dailyCostLimit`.

## Auth & RBAC

- **UI/API:** signed session cookie (`auth/Security.kt`), bcrypt password hashing
  (`auth/Passwords.kt`). Permissions resolved from roles (`model/Models.kt`, `RoleRepo`/`UserRepo`).
- **Datapath:** proxy tokens (not sessions). A token maps to a user whose permissions and group
  access gate proxying.

## Frontend

React 18 + Vite + TS in `frontend/src/`, built to `frontend/dist` and served by the nginx
router. Section URLs use **react-router-dom** (`/dashboard`, `/my/accounts`, `/my/stats`,
`/stats`, `/tokens`, `/pricing`, `/users`); nginx `try_files` falls unknown paths back to
`index.html`. Pages: Dashboard (pool), Accounts, Tokens, Stats (cost + window-utilization SVG
charts), MyStats, ModelPricing, Users, Login. Charts are hand-rolled SVG in `Chart.tsx`; shared
components in `ui.tsx`; API client + types in `api.ts`. In dev, Vite (`:5173`) proxies `/api`,
`/gateway`, `/v1`, `/healthz` to the service; in production everything is same-origin via nginx.

## Server engine tuning (`Application.kt`)

Netty is configured with raised `maxInitialLineLength` / `maxHeaderSize` / `maxChunkSize` —
Claude Code sends many/large headers (Stainless `x-stainless-*`, long `anthropic-beta`, big
tokens) that the ~8 KB defaults would reject at the decoder (→ 502 before the handler runs).

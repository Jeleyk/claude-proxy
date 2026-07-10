# CLAUDE.md

Guidance for AI agents (Claude Code) working in this repository. Keep it current when
architecture or deploy steps change.

## What this is

**claude-proxy** — a multi-account rotating reverse proxy for Claude Code / the Anthropic
API. It stores several Anthropic accounts (OAuth subscriptions and/or API keys), routes each
request to the highest-priority account that still has headroom, falls back when all are
saturated, tracks USD cost and rolling-window limits, and ships a React admin UI with
role-based access control. Production: nginx + Cloudflare in front of the container at
`https://proxy.example.com`.

## Stack

- **Backend:** Kotlin 2.2, Ktor 3.2 (Netty engine), Exposed 0.58 ORM, HikariCP, JVM target 21.
- **DB:** PostgreSQL 16 in production; SQLite fallback when `DATABASE_URL` is unset.
- **Frontend:** React 18 + Vite + TypeScript, hand-rolled SVG charts, no UI framework. Built
  into `src/main/resources/static/` and served by Ktor (same origin as the API).
- **Packaging:** one self-contained fat jar (UI baked in) or Docker (multi-stage build).

## Repository layout (`src/main/kotlin/org/claudeproxy/`)

| Path | Responsibility |
|------|----------------|
| `Application.kt` | Entry point. `embeddedServer(Netty)` with raised header/line limits; installs ContentNegotiation, ForwardedHeaders, CORS, StatusPages, security; wires routing. |
| `Config.kt` | Config from env vars / `.env` (loaded into system properties). |
| `proxy/` | **Datapath.** `ProxyRoutes` (inbound auth → account select → forward, `/v1/{...}`), `UpstreamForwarder` (forwards to Anthropic, relays SSE, records usage), `Http` (upstream CIO client), `SseUsageScanner` (token counting from the stream). |
| `accounts/` | `AccountPool` (selection/rotation/fallback), `AccountRepo`, `RateLimitHeaders` (parse `anthropic-ratelimit-*`), `TokenRefresher` (background OAuth refresh), `LimitProbe`/`LimitScheduler`, `UpstreamAuth`, `Secrets`. |
| `api/` | `AdminRoutes` (REST API for the UI), `Dtos`. |
| `auth/` | `Security` (session cookies), `Passwords` (bcrypt). |
| `db/` | `Database` (init + seed), `Tables` (Exposed schema), `Crypto` (AES-256-GCM for account secrets at rest). |
| `model/Models.kt` | `Permission`/`AccountType`/`WindowKind`/health enums + serializable DTOs. |
| `repo/` | Data access: Users, Roles, Groups, ProxyTokens, Usage, ModelPrices, WindowSnapshots, Settings, OAuthAdd. |
| `oauth/ClaudeOAuth.kt` | PKCE "Login with Claude" flow to add accounts. |

Frontend lives in `frontend/src/` (`App.tsx`, `api.ts`, `Chart.tsx`, `ui.tsx`, `pages/*`).

## Build / run / test

```bash
./gradlew run                 # backend only, reads .env, serves BIND_HOST:PORT (default 127.0.0.1:8787)
cd frontend && pnpm dev       # Vite HMR on :5173, proxies /api and /v1 to the backend
./gradlew bundle              # build the UI + a self-contained fat jar -> build/libs/claude-proxy-<v>-all.jar
./gradlew test                # JUnit
docker-compose up -d --build  # postgres + app together
```

## Deployment — read `docs/DEPLOY.md` before deploying

Server `root@YOUR_SERVER`, dir `/opt/claude-proxy`, behind nginx
(`/etc/nginx/sites/proxy.example.com.conf`) + Cloudflare.

> ⚠️ **NEVER `rsync --delete` into `/opt/claude-proxy/`.** That directory holds runtime state
> that is NOT in the repo: `pgdata/` (the entire Postgres DB, bind-mounted) and `.env` (server
> secrets, incl. `DATABASE_PASSWORD`). A `--delete` once wiped the database. Sync **only
> source**, without `--delete`, excluding `pgdata`, `.env`, `data`. Full recipe + safety in
> `docs/DEPLOY.md`.

## Config precedence gotcha

Effective config = Docker image `ENV` < compose `env_file: .env` < compose `environment:`.
Because `.env` ships `BIND_HOST=127.0.0.1` (a local default), `docker-compose.yml` pins two
overrides in the `environment:` block — **do not remove them**:

- `BIND_HOST: "0.0.0.0"` — the app must bind all interfaces inside the container, or Docker's
  published `127.0.0.1:8787` can't reach it (→ nginx 502, `healthz` unreachable).
- `PUBLIC_DOMAIN: "proxy.example.com"` — enables the CORS `allowHost` for the SPA's origin, or
  Ktor CORS answers every `/api/*` with an empty **403**.

## Domain concepts (see `docs/ARCHITECTURE.md` for depth)

- **Account types:** `OAUTH` (access+refresh, auto-refreshed), `OAUTH_STATIC` (access only),
  `API_KEY` (`x-api-key`). Secrets encrypted with `MASTER_KEY` (AES-256-GCM).
- **Global vs personal accounts:** `accounts.owner_id` = null → **global** (shared pool, the
  Dashboard); non-null → a user's **personal** account. The pool holds both; selection tries a
  user's personal accounts first (own tier), then the global pool (see Rotation). Personal
  accounts are never grouped, are excluded from *all* global stats (`UsageRepo` filters
  personal account ids; `pool.snapshotGlobal()`), and their spend does **not** count toward the
  per-user daily USD limit — so a user over the shared limit can still route through their own
  accounts. Managed at `/api/my/accounts/*` (self, `ACCOUNTS_OWN_MANAGE`) and
  `/api/users/{id}/accounts/*` (admin oversight, `USERS_MANAGE`).
- **Rotation:** strictly by `priority`; move to the next account once the active one's window
  usage crosses its `threshold`; when *all* are over threshold, enter **fallback** (ignore
  threshold until a real limit). `coefficient` (×1/×5/×20) weights pool capacity. Personal
  accounts form a preferred tier ordered ahead of the global tier.
- **Windows:** `FIVE_HOUR` ("5h") + `WEEKLY` ("7d"), read from
  `anthropic-ratelimit-unified-{5h,7d}-utilization` (0..1) response headers. Subscriptions
  report **utilization**, not remaining/limit.
- **Cost model:** per-model USD pricing (input / output / cache_read / cache_write) in
  `ModelPrices`; per-request cost from the response usage; optional per-user daily USD limit.
- **Proxy tokens:** `cxp_...`, stored as SHA-256; presented inbound via `Authorization: Bearer`
  or `x-api-key`.
- **Permissions** (`model/Models.kt`, ordered least→most): `PROXY_USE`, `STATS_VIEW_OWN`,
  `STATS_RESET_OWN`, `ACCOUNTS_OWN_MANAGE` (manage own personal accounts + "My Accounts" page),
  `STATS_VIEW_RECENT`, `STATS_VIEW_ACCOUNTS`, `ACCOUNTS_VIEW`, `STATS_VIEW`, `ACCOUNTS_MANAGE`,
  `USERS_MANAGE`, `ADMIN`. Default roles `user`/`manager` include `ACCOUNTS_OWN_MANAGE`.

## Anthropic upstream specifics (calibrated against live traffic)

- OAuth requests require `Authorization: Bearer sk-ant-oat...`, `anthropic-version: 2023-06-01`,
  `anthropic-beta: oauth-2025-04-20`, and the Claude Code system prompt
  `"You are Claude Code, Anthropic's official CLI for Claude."` — otherwise 400/401.
- Rate-limit headers: `anthropic-ratelimit-unified-5h-utilization` / `-7d-utilization` (0..1),
  `-5h-status`, `-5h-reset` (epoch seconds). No remaining/limit for subscriptions.
- **SSE relay is timing-sensitive.** `UpstreamForwarder` must flush the response head
  *immediately*, stream with `readAvailable` (not the buffering `readRemaining`), and inject
  `: keep-alive\n\n` SSE comments during upstream silence. Adaptive-thinking Opus on a 1M
  context stays silent 30s+ before the first event; a late head or buffering makes clients
  abort → nginx `upstream prematurely closed connection while reading response header` (502)
  and an endless client retry loop. Don't regress this.

## Conventions

- Match the surrounding style; comments explain **why**, not what (see existing files).
- The datapath is fully coroutine/suspend. Use Exposed `upsert` (cross-DB) — `replace` fails on
  Postgres. On delete, clear referencing rows first (Postgres enforces FKs).
- Don't add dependencies without a clear reason. Don't commit secrets — they live in the
  server `.env`.

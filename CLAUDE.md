# CLAUDE.md

Guidance for AI agents (Claude Code) working in this repository. Keep it current when
architecture or deploy steps change.

## What this is

**claude-proxy** — a multi-account rotating reverse proxy for Claude Code / the Anthropic
API. It stores several Anthropic accounts (OAuth subscriptions and/or API keys), routes each
request to the highest-priority account that still has headroom, falls back when all are
saturated, tracks USD cost and rolling-window limits, and ships a React admin UI with
role-based access control. Production shape: a TLS-terminating host proxy (nginx/Caddy, optionally
behind a CDN) in front of an in-compose nginx router on `127.0.0.1:8080`.

## Components & URL routing

The app is split into self-contained components fronted by one nginx router (in `docker-compose`,
listening on `127.0.0.1:8080`). A host reverse proxy terminates TLS and proxies to `:8080`.

| Public path   | Component            | Notes                                                    |
|---------------|----------------------|----------------------------------------------------------|
| `/`           | **frontend** (SPA)   | React admin UI, static, served by nginx (React Router)   |
| `/api/…`      | **service** (Kotlin) | Management REST API                                      |
| `/gateway/…`  | **gateway** (Go)     | Anthropic datapath (`/gateway/v1/…`), served by the Go **gateway** (Spec B). It resolves each request against the service's private `/internal/*` control API, forwards to Anthropic, relays SSE, and reports usage back. The Kotlin datapath (`service:8787`) stays running as an instant rollback (revert the two nginx `proxy_pass` targets). |
| `/routing/openai/…`    | **gateway-openai** (Go)    | OpenAI Chat Completions API emulated over Claude Code subscriptions. Translates OpenAI↔Anthropic (streaming + tool calls), resolves via the control API with `source="routing"`. base_url = `<origin>/routing/openai/v1`. |
| `/routing/anthropic/…` | **gateway-anthropic** (Go) | Native Anthropic Messages API served from Claude Code subscriptions (injects the Claude Code system prompt), `source="routing"`. base_url = `<origin>/routing/anthropic`. |

The routing gateways expose standard OpenAI/Anthropic API contracts to arbitrary clients but serve
them through the same account pool as the Claude Code proxy. They authenticate with **routing
tokens** (`cxr_…`, gated by `ROUTING_USE`), meter spend against a **separate per-user daily USD
limit** (`users.daily_routing_cost_limit`), and tag usage rows `source="routing"` (proxy rows are
`source="proxy"`) so the two datapaths share stats but keep independent limits. A routing token
may carry a **static system prompt** (`routing_tokens.system_prompt`, editable on the API Routing
page): the service returns it from `/internal/resolve` and `routing.go` injects it via
`ccident.InsertStaticPrompt` right after the mandatory Claude Code block — ahead of (higher
priority than) any system content arriving in the API request. All three gateways
are one Go module (`gateway/`, binaries under `cmd/`); they never touch Postgres.

Top-level dirs: `frontend/` (React) · `service/` (Kotlin business logic + control API + Kotlin
datapath/rollback) · `gateway/` (Go — one module: the Claude Code datapath at `gateway/main.go`
plus `cmd/openai` + `cmd/anthropic` routing gateways, shared `internal/`) · `deploy/` (nginx config
+ Dockerfiles).
Containers: `claude-proxy-{nginx,front,service,gateway,gateway-openai,gateway-anthropic,db}`.
`docker-compose.yml`, `.env.example`, `.dockerignore` stay at the repo root (compose sits next
to server runtime state). See `docs/superpowers/specs/2026-07-12-repo-restructure-nginx-routing-design.md`.

## Stack

- **Backend:** Kotlin 2.2, Ktor 3.2 (Netty engine), Exposed 0.58 ORM, HikariCP, JVM target 21.
- **Gateway:** Go 1.23 (stdlib `net/http` only, no framework) — the datapath in Spec B.
- **DB:** PostgreSQL 16 in production; SQLite fallback when `DATABASE_URL` is unset.
- **Cache:** in-process `MemoryCache` (service-side TTL map) — hot-path accelerator (token
  resolve, daily spend), never a source of truth: every miss falls through to the DB, a restart
  is just a cold cache. Single-instance by design; if the service is ever scaled horizontally
  this must become a shared cache again (a Redis implementation lived here until 2026-07 — see
  git history).
- **Frontend:** React 18 + Vite + TypeScript, **react-router-dom** for section URLs, hand-rolled
  SVG charts, no UI framework. Builds to `frontend/dist`; the nginx router serves it (same origin
  as the API — no CORS in prod).
- **Packaging:** `docker-compose` stack — `nginx` (SPA + router) + `service` (Kotlin fat jar,
  UI **not** baked in) + `postgres`.

## Repository layout (`service/src/main/kotlin/org/claudeproxy/`)

| Path | Responsibility |
|------|----------------|
| `Application.kt` | Entry point. `embeddedServer(Netty)` with raised header/line limits; installs ContentNegotiation, ForwardedHeaders, CORS, StatusPages, security; wires routing. |
| `Config.kt` | Config from env vars / `.env` (loaded into system properties). |
| `proxy/` | **Datapath.** `ProxyRoutes` (inbound auth → account select → forward, `/v1/{...}`), `UpstreamForwarder` (forwards to Anthropic, relays SSE, records usage), `Http` (upstream CIO client), `SseUsageScanner` (token counting from the stream). |
| `accounts/` | `AccountPool` (selection/rotation/fallback), `AccountRepo`, `RateLimitHeaders` (parse `anthropic-ratelimit-*`), `TokenRefresher` (background OAuth refresh), `LimitProbe`/`LimitScheduler`, `UpstreamAuth`, `Secrets`. |
| `api/` | `AdminRoutes` (REST API for the UI), `Dtos`; `InternalRoutes` + `InternalDtos` (the private `/internal/resolve` + `/internal/usage` control API for the Go gateway, gated by `X-Internal-Token`). |
| `datapath/` | `DatapathService` — the reusable resolve-a-request-into-an-ordered-plan + apply-an-outcome logic, shared by the Kotlin datapath and the control API (selection/crypto/limit bookkeeping stays here). |
| `cache/` | `MemoryCache` — in-process TTL cache with DB fallback (token resolve, daily spend); evicted on token delete. |
| `auth/` | `Security` (session cookies), `Passwords` (bcrypt). |
| `db/` | `Database` (init + seed), `Tables` (Exposed schema), `Crypto` (AES-256-GCM for account secrets at rest). |
| `model/Models.kt` | `Permission`/`AccountType`/`WindowKind`/health enums + serializable DTOs. |
| `repo/` | Data access: Users, Roles, Groups, ProxyTokens, Usage, ModelPrices, WindowSnapshots, Settings, OAuthAdd. |
| `oauth/ClaudeOAuth.kt` | PKCE "Login with Claude" flow to add accounts. |

Frontend lives in `frontend/src/` (`App.tsx` = router shell, `api.ts`, `Chart.tsx`, `ui.tsx`,
`pages/*`). Section URLs: `/dashboard`, `/my/accounts`, `/my/stats`, `/stats`, `/tokens`,
`/pricing`, `/users` (react-router; nginx `try_files` falls unknown paths back to `index.html`).

## Build / run / test

```bash
cd service && ./gradlew run    # service only, reads .env, serves BIND_HOST:PORT (default 127.0.0.1:8787)
cd frontend && pnpm dev        # Vite HMR on :5173, proxies /api /gateway /v1 /healthz to the service
cd service && ./gradlew fatJar # service fat jar (UI NOT baked in) -> service/build/libs/claude-proxy-<v>-all.jar
cd service && ./gradlew test   # JUnit
cd gateway && go test ./...    # Go gateway unit tests (go vet ./... too)
cd gateway && go run .            # Claude Code datapath gateway on :9000 (needs SERVICE_URL + INTERNAL_TOKEN)
cd gateway && go run ./cmd/openai    # OpenAI routing gateway on :9100 (SERVICE_URL + INTERNAL_TOKEN; DEFAULT_MODEL opt.)
cd gateway && go run ./cmd/anthropic # Anthropic routing gateway on :9200
docker-compose up -d --build   # nginx (:8080) + front + service + gateway(+openai/anthropic) + postgres
```

The Gradle project now lives under `service/` — run `./gradlew` from there (or `service/gradlew
-p service …`). The frontend build is fully decoupled (nginx owns it); there is no `bundle` task.

## Deployment — read `docs/DEPLOY.md` before deploying

A Docker Compose stack on one host, behind a TLS-terminating reverse proxy that points at the
**in-compose nginx router** on `127.0.0.1:8080`; the compose nginx fans out to `service` and the
Go gateways. `docs/DEPLOY.md` uses placeholders (`root@YOUR_SERVER`, `/opt/claude-proxy`,
`proxy.example.com`) — real hostnames and paths belong in an operator-private note, never in the
repo.

> ⚠️ **NEVER `rsync --delete` into the deploy directory.** It holds runtime state
> that is NOT in the repo: `pgdata/` (the entire Postgres DB, bind-mounted) and `.env` (server
> secrets, incl. `DATABASE_PASSWORD`). A `--delete` once wiped the database. Sync **only
> source**, without `--delete`, excluding `pgdata`, `.env`, `data`. Full recipe + safety in
> `docs/DEPLOY.md`.

## Config precedence gotcha

Effective config = Docker image `ENV` < compose `env_file: .env` < compose `environment:`.
Because `.env` ships `BIND_HOST=127.0.0.1` (a local default), `docker-compose.yml` pins an
override in the `service` `environment:` block — **do not remove it**:

- `BIND_HOST: "0.0.0.0"` — the service must bind all interfaces inside the container, or the
  compose **nginx** (reaching it as `service:8787` over the compose network) can't connect
  (→ nginx 502, healthcheck fails).

`PUBLIC_DOMAIN` is passed through from `.env` (`PUBLIC_DOMAIN: ${PUBLIC_DOMAIN:-}`) — it sets the
token base URL and the CORS `allowHost`. In prod the SPA is same-origin (served by nginx), so CORS
is moot there; the setting still matters for the Vite dev origin and the displayed base URL.
Blank/unset is valid: the UI then falls back to the browser's current origin.

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
  usage crosses its `threshold`. When *all* are over threshold, only accounts with the opt-in
  **`over_threshold`** flag stay usable past their threshold (appended as **fallback**, ordered
  by priority); accounts without the flag drop out of selection until their window resets. The
  flag is off by default, so with no opted-in account the pool can return nothing once everyone
  is saturated. `coefficient` (×1/×5/×20) weights pool capacity. Personal accounts form a
  preferred tier ordered ahead of the global tier — unless the user flips
  `users.prefer_global_pool` (self-service toggle on "My Accounts", gated by
  `ACCOUNTS_ORDER_TOGGLE`), which routes through the global pool first and falls back to
  personal accounts.
- **Windows:** `FIVE_HOUR` ("5h") + `WEEKLY` ("7d"), read from
  `anthropic-ratelimit-unified-{5h,7d}-utilization` (0..1) response headers. Subscriptions
  report **utilization**, not remaining/limit.
- **Cost model:** per-model USD pricing (input / output / cache_read / cache_write) in
  `ModelPrices`; per-request cost from the response usage; optional per-user daily USD limit.
- **MCP-call accounting:** the Claude Code gateway counts MCP tool invocations — `tool_use`
  content blocks named `mcp__server__tool` — via a structured SSE parse (`internal/proxy/mcpscan.go`,
  riding `anthropic.SSEParser` next to the regex usage scan) and the buffered-JSON path, ships
  them as `UsageReport.mcpCalls`, and the service writes one `mcp_tool_calls` row per
  (request, tool). Served by `/api/stats/mine/mcp` + `/api/users/{id}/stats/mcp`; "MCP tools"
  block in the shared `UserStatsView`. Proxy datapath only (routing gateways don't report it).
- **Proxy tokens:** `cxp_...` (Claude Code datapath); **routing tokens:** `cxr_...` (OpenAI/Anthropic
  gateways) — distinct namespaces (a `cxp_` never authenticates routing and vice versa), both stored
  as SHA-256 and presented inbound via `Authorization: Bearer` or `x-api-key`. Both kinds carry
  an `enabled` flag (default true, `PATCH /api/{proxy,routing}-tokens/{id}/enabled`, own tokens
  only): a disabled token is filtered out in `loadByHash`, so it resolves like an unknown token
  (401) on every datapath at once; the switch evicts the resolve cache, so it applies instantly.
  A routing token's settings (enable switch + static system prompt) live in one Edit dialog on
  the API Routing page; creation only takes a name.
- **Viewer-local days:** every time-series endpoint takes an IANA `tz` query param
  (`rangeParams()` in `AdminRoutes`), and so do the **"today" counter** payloads that carry no
  date range — `/stats/mine`, `/users/{id}/stats`, `/stats/models`, `/users/stats/overview` — via
  `zoneParam()` + `UserRepo.startOfDayIn(zone)`. The frontend sends
  `Intl…resolvedOptions().timeZone` on every stats request, so the cards and the charts under them
  are sliced on the same (viewer's) day. Missing/unparseable `tz` falls back to UTC — the
  pre-existing behaviour. **Exception, load-bearing:** the per-user **daily USD limits stay on UTC
  days** (`UserRepo.startOfUtcDay`, enforced in `DatapathService`/`ProxyRoutes`), so everything
  read *against a limit* keeps the UTC basis — `MyStatsPayload.proxyTodayCost`/`routingTodayCost`,
  `UserOverviewRow.todayProxyCost`/`todayRoutingCost`, and the Users-list "Spent today" column.
  Those are labelled UTC in the UI (the limit card spells out its own 00:00 UTC countdown);
  `UsageRepo.overviewByUser` takes both day starts for exactly this reason. Guarded by
  `repo/ViewerDayStatsTest`.
- **Window utilization charts** (`/stats/windows`, `/stats/mine/…`, `/users/{id}/…`): 30-min
  buckets (matching the ~30-min `LimitProbe` sampling), capped at 336 — longer ranges coarsen the
  bucket width. `planWindowBuckets` sizes the grid and **truncates it at `now`** when the range
  ends today. Do not "simplify" that back to the end of the last calendar day: `aggregateWindows`
  carries the last reading forward across gaps, so trailing empty buckets draw a flat line hours
  into the future that reads as live data. The width is derived from the full span *before*
  truncation, so dropping the tail never re-scales the buckets. Guarded by `api/WindowBucketPlanTest`.
- **Window burn per day** (`/stats/window-daily`, `/stats/mine/…`, `/users/{id}/…`): how much of
  each limit window was *consumed* per day, in window-fractions (1.0 = one full window). Computed
  in `aggregateWindowDaily` as the sum of positive steps of the utilization series — a drop means
  the window reset, so the fresh reading counts in full, which is why a day with several 5h resets
  reads well past 100%. Samples are fetched with a 24h lookback so the first day continues the
  previous series; the first sample of a series is a baseline, never spend.
- **Active sessions:** `datapath/ActiveSessions` counts requests streaming from Anthropic right
  now. The Go gateway mints a session id per request (`control.Client.Resolve`), the service opens
  the entry on a successful resolve and closes it on `/internal/session-end` (fire-and-forget from
  the gateway's `defer`); the Kotlin datapath wraps its own handler. Entries expire after 30 min so
  a lost close can't pin the gauge. In-process and unpersisted — a restart correctly reads zero.
- **Permissions** (`model/Models.kt`, ordered least→most): `PROXY_USE`, `ROUTING_USE` (use the
  OpenAI/Anthropic routing gateways + manage `cxr_` tokens), `STATS_VIEW_OWN`, `STATS_RESET_OWN`,
  `ACCOUNTS_OWN_MANAGE` (manage own personal accounts + "My Accounts" page), `ACCOUNTS_ORDER_TOGGLE`
  (switch personal-vs-global routing order), `POOL_GLOBAL_USE` (route through the shared pool),
  `STATS_VIEW_RECENT`, `STATS_VIEW_ACCOUNTS`, `ACCOUNTS_VIEW`, `STATS_VIEW`, `ACCOUNTS_MANAGE`,
  `USERS_MANAGE`, `ADMIN`. Default roles `user`/`manager` include `ACCOUNTS_OWN_MANAGE` +
  `ACCOUNTS_ORDER_TOGGLE` + `POOL_GLOBAL_USE` + `ROUTING_USE`.

## Anthropic upstream specifics (calibrated against live traffic)

- OAuth requests require `Authorization: Bearer sk-ant-oat...`, `anthropic-version: 2023-06-01`,
  `anthropic-beta: oauth-2025-04-20`, and the Claude Code system prompt
  `"You are Claude Code, Anthropic's official CLI for Claude."` — otherwise 400/401.
- Rate-limit headers: `anthropic-ratelimit-unified-5h-utilization` / `-7d-utilization` (0..1),
  `-5h-status`, `-5h-reset` (epoch seconds). No remaining/limit for subscriptions.
- **`overloaded_error` can arrive *after* a 200.** Anthropic accepts the request, then fails it
  with an in-stream `event: error` frame. Both Go datapaths scan for it
  (`anthropic.ErrScan`, shared): if nothing client-visible has been written yet — only
  `: keep-alive` comments, which SSE consumers discard — the next candidate takes the request over
  **on the same open stream**, invisibly to the client (`headSent` threads through
  `forward`/`relaySSE`/`relayStream` so the head is written exactly once). Once real frames are
  out, or no candidate is left, the error is surfaced instead: the proxy datapath writes a
  normalized retryable frame (`midStreamError`), routing lets the translator emit its own error
  shape. Retryable HTTP statuses (429/401/500/502/503/529) still swap accounts before any head is
  sent, as before. The Kotlin rollback datapath does **not** do the in-stream swap — it normalizes
  and lets the client retry (its coroutine relay carries the load-bearing 502 fix; left alone).
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

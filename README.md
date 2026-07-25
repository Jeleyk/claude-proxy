# claude-proxy

A self-hosted, multi-account rotating proxy for **Claude Code** and the Anthropic API.

Store several Anthropic accounts (Claude subscriptions via OAuth, and/or API keys). Every request
is routed to the highest-priority account that still has headroom in its rate-limit window; once
that account crosses its configured **threshold**, traffic moves to the next one. When *all*
accounts are saturated, only the accounts you explicitly opted into **fallback** keep serving.
Spend, rolling-window utilization and per-user/per-token statistics are tracked and rendered in a
React admin UI with roles and permissions.

Beyond the Claude Code datapath, the same account pool is exposed through two **API routing
gateways** — an OpenAI Chat Completions endpoint and a native Anthropic Messages endpoint — so
arbitrary clients (SDKs, agent frameworks, IDE plugins) can be pointed at the pool with their own
tokens and their own spend limits.

> **Scope & responsibility.** This is infrastructure for routing *your own* accounts. Sharing
> Anthropic access with third parties may conflict with Anthropic's terms — check them before
> pointing anyone else's client at your instance.

## Features

**Routing & limits**
- **Priority rotation** — accounts are used strictly one after another, by priority.
- **Per-account threshold** (e.g. `0.9`) with opt-in **fallback** past the threshold once the
  whole pool is saturated.
- **Live window tracking** from Anthropic's `anthropic-ratelimit-unified-{5h,7d}-utilization`
  response headers, plus a background prober that keeps window state fresh while idle.
- **Capacity coefficients** (×1 / ×5 / ×20) so a Max plan weighs correctly in pool capacity.
- **Transparent retry across accounts** on `429/401/5xx`; hard-limited accounts are parked until
  their window resets.
- **Mid-stream failover** — Anthropic can return `overloaded_error` *after* a `200`. If nothing
  client-visible has been written yet, the next account takes the request over on the same open
  stream, invisibly to the client.
- **Personal accounts** — a user can attach their own; they are tried before the shared pool (or
  after it, if the user flips the order), are excluded from global stats, and don't count against
  the shared daily limit.

**Accounts & auth**
- **Account types:** OAuth (access + refresh, auto-refreshed in the background), OAuth static
  (access only), API key (`x-api-key`).
- **Add accounts in the UI** by pasting a credential, or through the **Login with Claude** OAuth
  (PKCE) flow.
- **Secrets encrypted at rest** with AES-256-GCM under your `MASTER_KEY`.

**Multi-user**
- **Users, roles & granular permissions**, from `proxy.use` up to `admin`; account **groups**
  scope which accounts a user may route through.
- **Proxy tokens** (`cxp_…`) for Claude Code and **routing tokens** (`cxr_…`) for the OpenAI /
  Anthropic gateways — separate namespaces, stored as SHA-256, individually revocable and
  toggleable.
- **Per-user daily USD limits**, tracked separately for the proxy and the routing datapaths.
- A routing token can carry a **static system prompt**, injected ahead of anything the client
  sends.

**Observability**
- Per-model USD pricing (input / output / cache read / cache write) → per-request cost.
- Cost, token and request time series; per-token and per-user breakdowns; **window burn per day**
  (how much of each limit window a day actually consumed); live **active session** count.
- **MCP tool-call accounting** — `mcp__server__tool` invocations are parsed out of the response
  stream and counted per tool.
- Statistics are sliced on the **viewer's** timezone. The daily USD limits are enforced on UTC
  days, so the figures read against them are labelled UTC and carry their own 00:00 countdown.

## Architecture

One nginx router fronts every component on a single origin:

| Public path            | Component                  | What it is |
|------------------------|----------------------------|------------|
| `/`                    | **frontend**               | React SPA (admin UI), static |
| `/api/…`               | **service** (Kotlin/Ktor)  | Management REST API |
| `/gateway/…`           | **gateway** (Go)           | The Claude Code datapath |
| `/routing/openai/…`    | **gateway-openai** (Go)    | OpenAI Chat Completions over your pool |
| `/routing/anthropic/…` | **gateway-anthropic** (Go) | Anthropic Messages over your pool |

The Go gateways are a stateless data plane: they resolve each request against the service's
private `/internal/*` control API (which owns selection, crypto and bookkeeping), forward to
Anthropic, relay SSE, and report usage back. They never touch the database. The Kotlin service
also still contains a complete datapath, kept as an instant rollback target — flipping one nginx
`proxy_pass` reverts to it.

Layout: `frontend/` (React) · `service/` (Kotlin) · `gateway/` (Go, one module with `cmd/openai`
+ `cmd/anthropic`) · `deploy/` (nginx config + Dockerfiles) · `docs/`.

Depth: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Stack

Kotlin 2.2 / Ktor 3.2 / Exposed / JVM 21 · Go 1.23 (stdlib `net/http`, no framework) ·
PostgreSQL 16 (SQLite fallback for local runs) · React 18 + Vite + TypeScript (hand-rolled SVG
charts, no UI framework) · Docker Compose.

## Requirements

- Docker + Docker Compose — for the full stack.
- To run pieces natively: JDK 21+, Go 1.23+, Node 18+ with `pnpm`.

## Quick start

```bash
cp .env.example .env
# edit .env — MASTER_KEY, SESSION_SECRET, ADMIN_PASSWORD, DATABASE_PASSWORD, INTERNAL_TOKEN
#   openssl rand -hex 32     # fine for each of them
docker-compose up -d --build
```

Open <http://127.0.0.1:8080> and sign in with `ADMIN_USER` / `ADMIN_PASSWORD`. Add an account
(**Dashboard → Add account**, or *Login with Claude*), then create a token.

### Point Claude Code at it

Create a proxy token on the **Proxy Tokens** page, then:

```bash
export ANTHROPIC_BASE_URL=http://127.0.0.1:8080/gateway
export ANTHROPIC_AUTH_TOKEN=cxp_...
claude
```

### Point any OpenAI / Anthropic client at it

Create a routing token on the **API Routing** page (needs the `routing.use` permission) and use it
as an ordinary API key:

```
# OpenAI-compatible clients
base_url = http://127.0.0.1:8080/routing/openai/v1
api_key  = cxr_...

# Anthropic SDK clients
base_url = http://127.0.0.1:8080/routing/anthropic
api_key  = cxr_...
```

## Development

```bash
# terminal 1 — Kotlin service (reads .env), http://127.0.0.1:8787
cd service && ./gradlew run
# terminal 2 — Vite dev server with HMR, http://localhost:5173
#   proxies /api /gateway /v1 /healthz to the service — no nginx needed
cd frontend && pnpm install && pnpm dev
```

The Go gateways run standalone against a running service:

```bash
cd gateway
SERVICE_URL=http://localhost:8787 INTERNAL_TOKEN=devtok go run .              # datapath        :9000
SERVICE_URL=http://localhost:8787 INTERNAL_TOKEN=devtok go run ./cmd/openai    # OpenAI routing  :9100
SERVICE_URL=http://localhost:8787 INTERNAL_TOKEN=devtok go run ./cmd/anthropic # Anthropic route :9200
```

Tests:

```bash
cd service && ./gradlew test
cd gateway && go test ./... && go vet ./...
```

## Deployment

A Compose stack on one host behind a TLS-terminating reverse proxy. Set `BIND_HOST=0.0.0.0`,
strong `MASTER_KEY` / `SESSION_SECRET` / `INTERNAL_TOKEN`, and `PUBLIC_DOMAIN`. Full runbook,
safety rules (the deploy directory holds the bind-mounted database — never `rsync --delete` into
it) and a troubleshooting table: [`docs/DEPLOY.md`](docs/DEPLOY.md).

## Configuration

Everything is environment variables, optionally via a local `.env` — see
[`.env.example`](.env.example) for the annotated list.

`MASTER_KEY` encrypts every stored account credential. **Losing it means losing every stored
account**, and rotating it invalidates them all. Back it up separately from the database.

## Security notes

- Never expose `/internal/*` publicly — the bundled nginx config does not route it, and it is
  gated by the shared `INTERNAL_TOKEN`.
- The bootstrap admin is created only while the users table is empty; change its password
  immediately after the first login.
- Tokens are stored hashed (SHA-256) and shown in full exactly once, at creation.
- Anthropic subscription accounts need the Claude Code identity headers and system prompt to be
  accepted upstream; the gateways inject them. Read [`CLAUDE.md`](CLAUDE.md) before touching that
  path — parts of it (the SSE relay in particular) are timing-sensitive and load-bearing.

## Contributing

Issues and pull requests are welcome. Please run both test suites before opening a PR, and keep
[`CLAUDE.md`](CLAUDE.md) — the working notes for humans and AI agents alike — current when you
change architecture or deploy steps.

## License

[MIT](LICENSE).

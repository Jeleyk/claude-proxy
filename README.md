# claude-proxy

A multi-account proxy for Claude Code. Store several Anthropic accounts (OAuth
subscriptions and/or API keys); every request is routed to the highest-priority
account that still has headroom in its 5-hour window. Each account has a
configurable **threshold** — once the active account crosses it, traffic moves to
the next account by **priority**. When *all* accounts are over their thresholds,
the proxy enters **fallback mode** and keeps using them (ignoring the threshold)
until each hits its real limit. A web UI with login, roles/permissions, and live
stats manages everything.

## Features

- **Priority rotation** — accounts are used strictly one after another by priority.
- **Per-account threshold** (e.g. 0.9) with automatic **fallback** when all are saturated.
- **Capacity coefficients** — mark a Max ×5 / ×20 account so weighted pool capacity is computed correctly.
- **Live limit tracking** from Anthropic `anthropic-ratelimit-*` response headers.
- **Transparent 429 retry** across accounts; hard-limited accounts are parked until reset.
- **Account types**: API key (`x-api-key`), OAuth (access + refresh, auto-refreshed in the background), OAuth static (access only).
- **Add accounts in the UI** by pasting a credential, or via the **Login with Claude** OAuth (PKCE) flow.
- **Users, roles & permissions** — `proxy.use`, `accounts.view`, `stats.view`, `accounts.manage`, `users.manage`, `admin`.
- **Encrypted secrets at rest** (AES-256-GCM), SQLite storage, single self-contained jar.

## Requirements

- JDK 21+ (tested on Temurin 25)
- Node 18+ and `pnpm` (only to build the UI)

## Quick start (local)

```bash
cp .env.example .env        # set MASTER_KEY, ADMIN_PASSWORD, ...
./gradlew bundle            # builds the React UI + a self-contained fat jar
java -jar build/libs/claude-proxy-0.1.0-all.jar
```

Open http://127.0.0.1:8787 and sign in with the bootstrap admin.

### Point Claude Code at the proxy

Create a proxy token in the UI (**Proxy Tokens** page), then:

```bash
export ANTHROPIC_BASE_URL=http://127.0.0.1:8787
export ANTHROPIC_AUTH_TOKEN=<your-proxy-token>
claude
```

## Development

Run the backend and the Vite dev server separately (Vite proxies `/api` and `/v1`):

```bash
# terminal 1 — backend (reads .env)
./gradlew run
# terminal 2 — frontend with HMR on http://localhost:5173
cd frontend && pnpm install && pnpm dev
```

## Server deployment

Set `BIND_HOST=0.0.0.0`, a strong `MASTER_KEY`/`SESSION_SECRET`, and `PUBLIC_DOMAIN`
to your host. Put a TLS-terminating reverse proxy (Caddy/nginx) in front. The single
fat jar serves both the UI and the proxy datapath.

## Configuration

All configuration is via environment variables (or a local `.env`). See
[`.env.example`](.env.example).

## Calibration note

The exact Anthropic subscription rate-limit header names and the OAuth
authorize/token endpoints are pinned to the public Claude Code values but should be
confirmed against live traffic. The rate-limit parser is tolerant and logs every
`anthropic-ratelimit-*` header — set the `RateLimitHeaders` logger to `DEBUG` in
`logback.xml` to see them and adjust `RateLimitHeaders.kt` / the `OAUTH_*` env vars
if needed. Design details: [`docs/superpowers/specs/2026-07-08-claude-proxy-design.md`](docs/superpowers/specs/2026-07-08-claude-proxy-design.md).

# Deployment & operations

The recommended production shape is a Docker Compose stack on a single host, fronted by a
TLS-terminating reverse proxy. Throughout this document:

| Placeholder | Meaning |
|-------------|---------|
| `root@YOUR_SERVER` | your SSH target |
| `/opt/claude-proxy` | the deploy directory on that host |
| `proxy.example.com` | the public domain you serve the proxy on |

```
Claude Code / browser
   → (optional CDN, e.g. Cloudflare — TLS, proxied DNS)
     → host nginx (:443, proxy_pass http://127.0.0.1:8080)
       → compose nginx router (host 127.0.0.1:8080) — serves the SPA (front) and routes
         /api, /gateway, /v1, /routing/*
         → claude-proxy-service (service:8787)            # /api + /internal control API + Kotlin datapath (rollback)
         → claude-proxy-gateway (gateway:9000)            # /v1 datapath + /routing/{openai,anthropic}
           → the service's /internal/resolve + /internal/usage (crypto, pool, usage)
             → Postgres claude-proxy-db (./pgdata)
```

The datapath base URL clients use is `https://proxy.example.com/gateway` (Claude Code appends
`/v1/…`). The routing gateways are at `https://proxy.example.com/routing/openai/v1` and
`https://proxy.example.com/routing/anthropic`.

A TLS-terminating host proxy is not strictly required — the compose router publishes
`127.0.0.1:8080` and any reverse proxy (nginx, Caddy, Traefik) can front it. Keep whatever you
choose SSE-safe: buffering **off**, long read timeouts.

## Compose stack (`docker-compose.yml`)

- **postgres** — `postgres:16-alpine`, data bind-mounted at `./pgdata`. Initialized with
  `POSTGRES_PASSWORD=${DATABASE_PASSWORD}`.
- **service** — Kotlin API + control API + Kotlin datapath (rollback), built from
  `deploy/Dockerfile.service` (context = root). Container `claude-proxy-service`. **No host
  port** — reachable only as `service:8787` on the compose network. `env_file: .env` plus an
  `environment:` block pinning `BIND_HOST=0.0.0.0` and passing through `PUBLIC_DOMAIN`,
  `DATABASE_*` and `INTERNAL_TOKEN`. SQLite dir bind-mounted at `./data`. `curl /healthz`
  healthcheck.
- **gateway** — the Go datapath, built from `deploy/Dockerfile.gateway`. Container
  `claude-proxy-gateway`, reachable as `gateway:9000`. Calls the service's `/internal/*` control
  API (shared `INTERNAL_TOKEN`), forwards to Anthropic. `depends_on: service (healthy)`;
  `wget /healthz` healthcheck.
  The API routing gateways live in this **same** container and port, mounted at `/routing/openai/`
  and `/routing/anthropic/`; nginx forwards those prefixes verbatim and the gateway strips them.
- **nginx** — edge router, built from `deploy/Dockerfile.nginx`. Publishes `127.0.0.1:8080:8080`.
  `depends_on: service, front (healthy)` + the gateways (started). Config in `deploy/nginx/` —
  `/gateway/` and `/v1/` `proxy_pass` to `gateway:9000` (revert to `service:8787` to roll the
  datapath back); `proxy_stream.conf` is the SSE-safe one.

> **`INTERNAL_TOKEN`** must be present in the server `.env` (a long random secret, e.g.
> `openssl rand -hex 32`), shared by `service` and all three gateways. Without it the control API
> stays `401` and the gateways can't resolve. nginx **never** routes `/internal` publicly.

### Server `.env`

The deploy directory holds a server-managed `.env` (never synced from your working copy). At
minimum:

```dotenv
MASTER_KEY=<32+ random chars>          # encrypts account credentials at rest — losing it loses them
SESSION_SECRET=<random>
ADMIN_USER=admin
ADMIN_PASSWORD=<strong>                # bootstrap admin, only used while the users table is empty
BIND_HOST=0.0.0.0
PUBLIC_DOMAIN=proxy.example.com        # token base URL + CORS allowHost
DATABASE_PASSWORD=<random>             # must match what ./pgdata was initialized with
INTERNAL_TOKEN=<openssl rand -hex 32>  # shared with the gateways
```

## ⚠️ Critical safety rules

The deploy directory contains **runtime state that is not in the repo**. Treat it carefully:

1. **NEVER `rsync --delete` into `/opt/claude-proxy/`.** It will delete `pgdata/` (the whole
   database), `.env` (secrets), and `data/`. This has already caused one total DB loss in
   practice.
2. **`.env` is server-managed.** Never overwrite it from the local repo — the local one differs
   (e.g. `BIND_HOST`) and lacks `DATABASE_PASSWORD`. Exclude it from every sync.
3. **`DATABASE_PASSWORD` must stay in the server `.env`** and match the value the `pgdata`
   volume was initialized with. If it's missing, compose defaults to `claudeproxy`, which fails
   auth against the existing volume and the app crash-loops on
   `password authentication failed for user "claudeproxy"`.
4. **Take a dump before risky work:**
   `docker exec claude-proxy-db pg_dump -U claudeproxy claudeproxy > backups/pg-$(date +%s).sql`.

## Safe deploy recipe

Sync **source only**, no `--delete`, with explicit excludes:

```bash
# from the repo root, local machine — sync BOTH source trees, no --delete:
rsync -az --exclude '.git' --exclude 'build' --exclude '.gradle' \
  ./service/ root@YOUR_SERVER:/opt/claude-proxy/service/
rsync -az --exclude 'node_modules' --exclude 'dist' \
  ./frontend/ root@YOUR_SERVER:/opt/claude-proxy/frontend/
# also copy changed build/config files (Dockerfiles live in deploy/):
scp docker-compose.yml .dockerignore root@YOUR_SERVER:/opt/claude-proxy/
rsync -az ./deploy/ root@YOUR_SERVER:/opt/claude-proxy/deploy/
rsync -az --exclude '.git' ./gateway/ root@YOUR_SERVER:/opt/claude-proxy/gateway/
```

Rebuild just the affected app services (not postgres — `--no-deps` keeps the DB untouched;
Compose v1.25 needs `--force-recreate`):

```bash
ssh root@YOUR_SERVER 'cd /opt/claude-proxy && \
  docker-compose up -d --build --force-recreate --no-deps service gateway nginx'
```

When the `/internal/*` contract changes, deploy `service` **before** the gateways.

SSH sessions can drop during long builds — run the build detached and poll:

```bash
ssh root@YOUR_SERVER 'cd /opt/claude-proxy && \
  setsid sh -c "docker-compose up -d --build --force-recreate --no-deps service gateway nginx > /tmp/build.log 2>&1" &'
# then poll /tmp/build.log and: docker inspect claude-proxy-service --format '{{.State.Status}}'
```

> **Fast deploy (skip the server-side Gradle/node build):** build locally
> (`cd service && ./gradlew fatJar`; `cd frontend && pnpm build`), ship the jar via
> `deploy/Dockerfile.runtime` for the service and rebuild the nginx image from the prebuilt
> `frontend/dist`.

Verify:

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://proxy.example.com/healthz  # -> 200
docker logs --tail 5 claude-proxy-service | grep Responding                 # -> Responding at http://0.0.0.0:8787
docker logs --tail 5 claude-proxy-gateway | grep 'gateway on'               # -> gateway on :9000 -> https://api.anthropic.com
```

### Verifying a gateway before it is public

The Go datapath can be rolled out while nginx still points `/gateway`,`/v1` at the Kotlin
datapath — a broken gateway then has **no client impact**. Test it from inside the compose
network:

```bash
ssh root@YOUR_SERVER 'docker exec claude-proxy-gateway wget -qO- http://127.0.0.1:9000/healthz'
ssh root@YOUR_SERVER 'docker run --rm --network claude-proxy_default curlimages/curl -s \
  -o /dev/null -w "%{http_code}\n" -X POST http://gateway:9000/v1/messages \
  -H "Authorization: Bearer <a real cxp_ token>" -H "anthropic-version: 2023-06-01" \
  -H "content-type: application/json" \
  -d "{\"model\":\"claude-haiku-4-5-20251001\",\"max_tokens\":16,\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"'
# -> 200; `docker logs claude-proxy-gateway` shows the SSE relay draining + a usage report.
```

**Datapath rollback:** revert the two `proxy_pass` targets in `deploy/nginx/nginx.conf` to
`http://service:8787` (bare) / `http://service:8787/` (trailing slash), re-sync `deploy/`, and
rebuild nginx. The Kotlin datapath never stopped running.

## Admin / access

- The bootstrap admin is created only when the users table is empty; credentials come from
  `ADMIN_USER` / `ADMIN_PASSWORD` in the server `.env`. Change the password after first login.
- Reach Postgres directly: `docker exec -it claude-proxy-db psql -U claudeproxy -d claudeproxy`.
- Point Claude Code at the proxy: create a token in the UI (**Proxy Tokens**), then
  `export ANTHROPIC_BASE_URL=https://proxy.example.com/gateway` and
  `export ANTHROPIC_AUTH_TOKEN=<proxy-token>` (the Tokens page shows the exact URL).

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `502` + nginx `upstream prematurely closed connection while reading response header`, client retries forever | SSE relay didn't flush the head early / buffered the stream during a long Opus "thinking" pause | the relay must flush the head immediately + stream incrementally + inject `: keep-alive` during silence (already handled — don't regress) |
| App crash-loops: `password authentication failed for user "claudeproxy"` | `DATABASE_PASSWORD` missing from the server `.env` after an `.env` overwrite | restore `DATABASE_PASSWORD` to the value `pgdata` was initialized with |
| `healthz` unreachable on host / nginx 502 though the container is "running" | app bound to container-loopback (`BIND_HOST=127.0.0.1`) | ensure compose `environment: BIND_HOST: "0.0.0.0"` |
| `/api/*` returns an empty `403` (login fails, `Vary: Origin`) | Ktor CORS rejects the SPA origin because `PUBLIC_DOMAIN` is unset | set `PUBLIC_DOMAIN` in the server `.env` |
| `502` where the request never reaches the handler (no `IN POST` log) | Netty decoder rejecting large request headers | `Application.kt` raises `maxHeaderSize` / `maxInitialLineLength` / `maxChunkSize` — keep them |
| Sudden `502` right after `--force-recreate` of a backend | compose nginx cached the old upstream container IP at start | `docker exec claude-proxy-nginx nginx -s reload` |
| Empty DB / "Bootstrapped admin" on a DB that had data | `pgdata` was deleted (e.g. `rsync --delete`) and Postgres re-initialized | restore from a `pg_dump` backup |

## nginx notes

**Two nginx layers.**

- **Host nginx** — terminates TLS, optionally behind a CDN. It only needs to `proxy_pass` to
  `http://127.0.0.1:8080` (the compose router). Keep SSE-safe settings as defence in depth
  (`proxy_buffering off`, `proxy_read_timeout 3600s`, `large_client_header_buffers`,
  `client_max_body_size`).
- **Compose nginx** (`deploy/nginx/`) — the path router + SPA server on `:8080`. Its datapath
  locations (`/gateway/`, `/v1/`, `/routing/*`) pull in `proxy_stream.conf`, which disables
  buffering and sets long read timeouts (the load-bearing SSE fix). Do not weaken it.

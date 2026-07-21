# Deployment & operations

Production runs as a Docker Compose stack on **`root@YOUR_SERVER`** in
**`/opt/claude-proxy`**, fronted by nginx + Cloudflare at **`https://proxy.example.com`**.

```
Claude Code / browser
   → Cloudflare (TLS, proxied DNS)
     → host nginx (:443, /etc/nginx/sites/proxy.example.com.conf, proxy_pass http://127.0.0.1:8080)
       → compose nginx router (host 127.0.0.1:8080) — serves SPA (front), routes /api /gateway /v1
         → claude-proxy-service (service:8787)  # /api + /internal control API + Kotlin datapath (rollback)
         → claude-proxy-gateway (gateway:9000)  # /gateway(→/v1), /v1 — Go datapath (Spec B)
           → claude-proxy-service /internal/resolve + /internal/usage (crypto, pool, usage)
             → Postgres claude-proxy-db (./pgdata)
```

Only the host-nginx **upstream** changed: `proxy_pass http://127.0.0.1:8787` →
`http://127.0.0.1:8080`. TLS/Cloudflare config is unchanged. The datapath base URL clients use
is now `https://proxy.example.com/gateway` (Claude Code appends `/v1/…`).

## Compose stack (`docker-compose.yml`)

- **postgres** — `postgres:16-alpine`, data bind-mounted at `./pgdata` (host
  `/opt/claude-proxy/pgdata`). Initialized with `POSTGRES_PASSWORD=${DATABASE_PASSWORD}`.
- **service** — Kotlin API + control API + Kotlin datapath (rollback), built from
  `deploy/Dockerfile.service` (context = root). Container name `claude-proxy-service`. **No host
  port** — reachable only as `service:8787` on the compose network. `env_file: .env` plus an
  `environment:` block pinning `BIND_HOST=0.0.0.0`, `PUBLIC_DOMAIN=proxy.example.com`,
  and `INTERNAL_TOKEN=${INTERNAL_TOKEN}`. SQLite dir bind-mounted
  at `./data`. Has a `curl /healthz` healthcheck.
- **gateway** — Go datapath (Spec B), built from `deploy/Dockerfile.gateway`. Container
  `claude-proxy-gateway`, reachable as `gateway:9000`. Calls the service's `/internal/*` control
  API (shared `INTERNAL_TOKEN`), forwards to Anthropic. `depends_on: service (healthy)`;
  `wget /healthz` healthcheck.
- **nginx** — edge router, built from `deploy/Dockerfile.nginx`. Publishes `127.0.0.1:8080:8080`.
  `depends_on: service, front, gateway (healthy)`. Config in `deploy/nginx/` — `/gateway/` and
  `/v1/` now `proxy_pass` to `gateway:9000` (revert to `service:8787` to roll back the datapath);
  `proxy_stream.conf` is the SSE-safe one.

> **`INTERNAL_TOKEN`** must be present in the server `.env` (a long random secret, e.g.
> `openssl rand -hex 32`), shared by `service` and `gateway`. Without it the control API stays
> `401` and the gateway can't resolve. nginx **never** routes `/internal` publicly.

## ⚠️ Critical safety rules

The deploy directory `/opt/claude-proxy/` contains **runtime state that is not in the repo**.
Treat it carefully:

1. **NEVER `rsync --delete` into `/opt/claude-proxy/`.** It will delete `pgdata/` (the whole
   database), `.env` (secrets), and `data/`. This has already caused one total DB loss.
2. **`.env` is server-managed.** Never overwrite it from the local repo — the local `.env`
   differs (e.g. `BIND_HOST`, and it lacks `DATABASE_PASSWORD`). Exclude it from every sync.
3. **`DATABASE_PASSWORD` must stay in the server `.env`** and match the value the `pgdata`
   volume was initialized with. If it's missing, compose defaults to `claudeproxy`, which fails
   auth against the existing volume and the app crash-loops on
   `password authentication failed for user "claudeproxy"`.
4. **Take a dump before risky work:** `docker exec claude-proxy-db pg_dump -U claudeproxy
   claudeproxy > /opt/claude-proxy-backups/pg-$(date +%s).sql`.

## Safe deploy recipe

Sync **source only**, no `--delete`, with explicit excludes:

```bash
# from the repo root, local machine — sync BOTH source trees, no --delete:
rsync -az --exclude '.git' --exclude 'build' --exclude '.gradle' \
  ./service/ root@YOUR_SERVER:/opt/claude-proxy/service/
rsync -az --exclude 'node_modules' --exclude 'dist' \
  ./frontend/ root@YOUR_SERVER:/opt/claude-proxy/frontend/
# also copy changed build/config files (note: Dockerfiles now live in deploy/):
scp docker-compose.yml .dockerignore root@YOUR_SERVER:/opt/claude-proxy/
rsync -az ./deploy/ root@YOUR_SERVER:/opt/claude-proxy/deploy/
rsync -az --exclude '.git' ./gateway/ root@YOUR_SERVER:/opt/claude-proxy/gateway/
```

**Gateway rollout (do it in two steps so it's reversible):**

1. **Deploy service + gateway, leave nginx on the Kotlin datapath.** Add
   `INTERNAL_TOKEN=<secret>` to the server `.env` first. Rebuild without touching nginx — the
   public path is still the Kotlin datapath, so a broken gateway has **no client impact**:

   ```bash
   ssh root@YOUR_SERVER 'cd /opt/claude-proxy && \
     docker-compose up -d --build --force-recreate --no-deps service gateway'
   ```

   Verify the gateway end-to-end **from inside the compose network** (not yet public):

   ```bash
   ssh root@YOUR_SERVER 'docker exec claude-proxy-gateway wget -qO- http://127.0.0.1:9000/healthz'
   ssh root@YOUR_SERVER 'docker run --rm --network claude-proxy_default curlimages/curl -s \
     -o /dev/null -w "%{http_code}\n" -X POST http://gateway:9000/v1/messages \
     -H "Authorization: Bearer <a real cxp_ token>" -H "anthropic-version: 2023-06-01" \
     -H "content-type: application/json" \
     -d "{\"model\":\"claude-haiku-4-5-20251001\",\"max_tokens\":16,\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"'
   # -> 200; `docker logs claude-proxy-gateway` shows the SSE relay draining + a usage report;
   #    `docker logs claude-proxy-service` shows /internal/resolve + /internal/usage hits.
   ```

2. **Flip nginx to the gateway** (the repo's `nginx.conf` already points `/gateway`,`/v1` at
   `gateway:9000`). Rebuild nginx only:

   ```bash
   ssh root@YOUR_SERVER 'cd /opt/claude-proxy && \
     docker-compose up -d --build --force-recreate --no-deps nginx'
   ```

   **Rollback:** revert the two `proxy_pass` targets in `deploy/nginx/nginx.conf` back to
   `http://service:8787` (bare) / `http://service:8787/` (trailing slash), re-sync `deploy/`, and
   rebuild nginx. The Kotlin datapath never stopped running.

For an ordinary (non-gateway) source change, rebuild just the affected app services (not postgres
— `--no-deps` keeps the DB untouched; Compose v1.25 needs `--force-recreate`):

```bash
ssh root@YOUR_SERVER 'cd /opt/claude-proxy && \
  docker-compose up -d --build --force-recreate --no-deps service gateway nginx'
```

SSH sessions can drop during long builds — run the build detached and poll:

```bash
ssh root@YOUR_SERVER 'cd /opt/claude-proxy && \
  setsid sh -c "docker-compose up -d --build --force-recreate --no-deps service gateway nginx > /tmp/build.log 2>&1" &'
# then poll /tmp/build.log and: docker inspect claude-proxy-service --format '{{.State.Status}}'
```

> **Fast deploy (skip the server-side Gradle/node build):** build locally
> (`cd service && ./gradlew fatJar`; `cd frontend && pnpm build`), ship the jar via
> `deploy/Dockerfile.runtime` for the service and rebuild the nginx image from the prebuilt
> `frontend/dist`. Update `rollback.sh` image tags accordingly.

Verify:

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://proxy.example.com/healthz   # -> 200
docker logs --tail 5 claude-proxy-service | grep Responding                  # -> Responding at http://0.0.0.0:8787
docker logs --tail 5 claude-proxy-gateway | grep 'gateway on'                # -> gateway on :9000 -> https://api.anthropic.com
```

## Admin / access

- Bootstrap admin is created only when the users table is empty; credentials come from
  `ADMIN_USER` / `ADMIN_PASSWORD` in the server `.env`.
- Reach Postgres directly: `docker exec -it claude-proxy-db psql -U claudeproxy -d claudeproxy`.
- Point Claude Code at the proxy: create a token in the UI (**Proxy Tokens**), then
  `export ANTHROPIC_BASE_URL=https://proxy.example.com/gateway` and
  `export ANTHROPIC_AUTH_TOKEN=<proxy-token>` (the Tokens page shows the exact `/gateway` URL).
  Tokens whose base URL is still the bare domain keep working via the nginx `/v1/` route.

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `502` + nginx `upstream prematurely closed connection while reading response header`, client retries forever | SSE relay didn't flush the head early / buffered the stream during a long Opus "thinking" pause | `UpstreamForwarder` must flush head immediately + stream with `readAvailable` + inject `: keep-alive` during silence (already fixed — don't regress) |
| App crash-loops: `password authentication failed for user "claudeproxy"` | `DATABASE_PASSWORD` missing from server `.env` after an `.env` overwrite | restore `DATABASE_PASSWORD` in `/opt/claude-proxy/.env` to the value `pgdata` was initialized with |
| `healthz` unreachable on host / nginx 502 though container is "running" | app bound to container-loopback (`BIND_HOST=127.0.0.1`) | ensure compose `environment: BIND_HOST: "0.0.0.0"` |
| `/api/*` returns empty `403` (login fails, `Vary: Origin`) | Ktor CORS rejects the SPA origin because `PUBLIC_DOMAIN` is unset | ensure compose `environment: PUBLIC_DOMAIN: "proxy.example.com"` |
| `502` where the request never reaches the handler (no `IN POST` log) | Netty decoder rejecting large request headers | `Application.kt` raises `maxHeaderSize` / `maxInitialLineLength` / `maxChunkSize` — keep them |
| Empty DB / "Bootstrapped admin" on a DB that had data | `pgdata` was deleted (e.g. `rsync --delete`) and Postgres re-initialized | restore from a `pg_dump` backup; re-add accounts/tokens if none |

## nginx notes

**Two nginx layers now.**

- **Host nginx** (`/etc/nginx/sites/proxy.example.com.conf`) — terminates TLS (acme.sh /
  Let's Encrypt under `/etc/nginx/ssl/le/`), fronts Cloudflare. The **only change** for this
  restructure: point its `proxy_pass` at `http://127.0.0.1:8080` (the compose nginx) instead of
  `:8787`. Keep its SSE-safe settings as defence in depth (`proxy_buffering off`,
  `proxy_read_timeout 3600s`, `large_client_header_buffers`, `client_max_body_size`).
- **Compose nginx** (`deploy/nginx/`) — the path router + SPA server on `:8080`. Its datapath
  locations (`/gateway/`, `/v1/`) pull in `proxy_stream.conf`, which disables buffering and sets
  long read timeouts (the load-bearing SSE fix). Do not weaken it.

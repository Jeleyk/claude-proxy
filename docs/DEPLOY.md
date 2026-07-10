# Deployment & operations

Production runs as a Docker Compose stack on **`root@YOUR_SERVER`** in
**`/opt/claude-proxy`**, fronted by nginx + Cloudflare at **`https://proxy.example.com`**.

```
Claude Code / browser
   → Cloudflare (TLS, proxied DNS)
     → nginx  (:443, /etc/nginx/sites/proxy.example.com.conf, proxy_pass http://127.0.0.1:8787)
       → docker-proxy (host 127.0.0.1:8787)
         → container claude-proxy (172.22.0.x:8787, app binds 0.0.0.0)
           → Postgres container claude-proxy-db (bind mount ./pgdata)
```

## Compose stack (`docker-compose.yml`)

- **postgres** — `postgres:16-alpine`, data bind-mounted at `./pgdata` (host
  `/opt/claude-proxy/pgdata`). Initialized with `POSTGRES_PASSWORD=${DATABASE_PASSWORD}`.
- **claude-proxy** — built from the repo. Publishes `127.0.0.1:8787:8787` (host-private; nginx
  fronts it). `env_file: .env` plus an `environment:` block that pins `BIND_HOST=0.0.0.0` and
  `PUBLIC_DOMAIN=proxy.example.com` (see "Config precedence" in `CLAUDE.md`). SQLite dir
  bind-mounted at `./data`.

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
# from the repo root, local machine
rsync -az --exclude '.git' ./src/ root@YOUR_SERVER:/opt/claude-proxy/src/
# also copy any changed build/config files individually, e.g.:
scp docker-compose.yml build.gradle.kts Dockerfile root@YOUR_SERVER:/opt/claude-proxy/
```

Then rebuild + recreate **only the app** (Compose v1.25 needs `--force-recreate`; the DB stays
untouched thanks to `--no-deps`):

```bash
ssh root@YOUR_SERVER 'cd /opt/claude-proxy && \
  docker-compose up -d --build --force-recreate --no-deps claude-proxy'
```

SSH sessions can drop during long builds — run the build detached and poll:

```bash
ssh root@YOUR_SERVER 'cd /opt/claude-proxy && \
  setsid sh -c "docker-compose up -d --build --force-recreate --no-deps claude-proxy > /tmp/build.log 2>&1" &'
# then poll /tmp/build.log and: docker inspect claude-proxy --format '{{.State.Status}}'
```

Verify:

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://proxy.example.com/healthz   # -> 200
docker logs --tail 5 claude-proxy | grep Responding                          # -> Responding at http://0.0.0.0:8787
```

## Admin / access

- Bootstrap admin is created only when the users table is empty; credentials come from
  `ADMIN_USER` / `ADMIN_PASSWORD` in the server `.env`.
- Reach Postgres directly: `docker exec -it claude-proxy-db psql -U claudeproxy -d claudeproxy`.
- Point Claude Code at the proxy: create a token in the UI (**Proxy Tokens**), then
  `export ANTHROPIC_BASE_URL=https://proxy.example.com` and
  `export ANTHROPIC_AUTH_TOKEN=<proxy-token>`.

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

`client_max_body_size 0` (unlimited bodies), `large_client_header_buffers 16 64k`,
`proxy_buffering off`, `proxy_read_timeout 3600s`. TLS via acme.sh (Let's Encrypt) under
`/etc/nginx/ssl/le/`.

# gateway (Go proxy) — Spec B

The thin Go data plane for the Anthropic datapath. It replaces the Kotlin datapath behind
`/gateway`, `/v1` (nginx strips the `/gateway` prefix). All state lives in the **service**
(Kotlin/Postgres); the gateway is stateless and never touches Postgres or Redis directly.

## Flow (per request)

1. Read the proxy token (`Authorization: Bearer` or `x-api-key`) and the full body.
2. `POST /internal/resolve` on the service → ordered candidate list, each with the **decrypted**
   upstream auth headers, device-id, and window reset epochs. Maps resolve `401`/`403` straight
   to the client; `overLimit` with no candidates → `429`; no candidates → `503`.
3. Loop candidates (all but the last may retry). For each: rewrite the body (per-account device-id
   + rotated session-id), swap in the account's auth headers, strip hop/auth/telemetry headers,
   forward to Anthropic.
   - **SSE** (`text/event-stream`): flush the head immediately, inject `: keep-alive\n\n` during
     upstream silence, stream incrementally, scan token usage out of the stream, and on a
     mid-stream retryable error inject a normalized error frame so the client retries.
   - **JSON**: buffer, parse `usage`, pass through.
   - Retryable status (`429/401/500/502/503/529`) on a non-last attempt → try the next account.
4. `POST /internal/usage` (fire-and-forget) reports tokens + status + rate-limit headers; the
   service records usage, updates limit state, and parks/curbs the account.

**The SSE relay is timing-sensitive — see `internal/proxy/sse.go`.** A late head or buffering
makes clients abort → nginx 502 + retry loops. Don't regress it.

## Layout

| Path | Responsibility |
|------|----------------|
| `main.go` | HTTP server, `/healthz`, mounts the datapath handler on `/`. |
| `internal/config/` | Env config (`PORT`, `SERVICE_URL`, `INTERNAL_TOKEN`, `UPSTREAM_BASE_URL`). |
| `internal/control/` | HTTP client for the service `/internal/*` control API + typed DTOs. |
| `internal/proxy/handler.go` | Request entry, token extraction, resolve, retry loop. |
| `internal/proxy/forward.go` | Single upstream attempt (headers, auth, status decision, non-SSE). |
| `internal/proxy/sse.go` | SSE relay (head flush, keep-alives, mid-stream inject). |
| `internal/proxy/usagescan.go` · `errscan.go` | Token counting + mid-stream error detection. |
| `internal/proxy/rewrite.go` | Body rewrite: device-id stamp + UUIDv5 session rotation. |
| `internal/proxy/inject.go` | Mid-stream error frame builder. |

## Config

| Env | Default | Notes |
|-----|---------|-------|
| `PORT` | `9000` | Listen port. |
| `SERVICE_URL` | `http://service:8787` | Base URL of the Kotlin service control API. |
| `INTERNAL_TOKEN` | — | Shared secret sent as `X-Internal-Token`; must match the service. |
| `UPSTREAM_BASE_URL` | `https://api.anthropic.com` | Anthropic API base. |

## Build / run / test

```bash
cd gateway
go test ./...            # unit tests (config, control client, forward, SSE relay, scanners, rewrite)
go vet ./...
go build -o /tmp/gateway .
PORT=9000 SERVICE_URL=http://localhost:8787 INTERNAL_TOKEN=devtok go run .   # local
curl localhost:9000/healthz    # {"message":"ok"}
```

Stdlib only (`net/http`) — no framework, no external module dependencies. Built in Docker via
`deploy/Dockerfile.gateway` (multi-stage → alpine).

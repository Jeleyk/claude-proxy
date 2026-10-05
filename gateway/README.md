# gateway (Go)

The thin Go data plane. One module, **one binary, one port** — the four surfaces are split by path prefix in `newMux`:

| Path | Port | Serves |
|--------|------|--------|
| `/v1/…` | 9000 | The Claude Code datapath behind `/gateway`, `/v1` (nginx strips the `/gateway` prefix). |
| `/routing/openai/…` | 9000 | OpenAI Chat Completions, translated to/from Anthropic. |
| `/routing/anthropic/…` | 9000 | Native Anthropic Messages, with the Claude Code system prompt injected. |
| `/openai/v1/…` | 9000 | Native OpenAI Responses using OpenAI API keys or Codex subscription accounts. |

Durable state lives in the **service** (Kotlin/Postgres); the gateway never touches the database.
Native OpenAI conversation affinity is an ephemeral, bounded in-memory exception. The routing surfaces share `internal/` with the datapath and resolve through the
same control API, tagging their usage `source="routing"`.

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
| `internal/proxy/usagescan.go` | Token counting out of the stream. |
| `internal/proxy/mcpscan.go` | MCP `tool_use` block counting (`mcp__server__tool`). |
| `internal/proxy/rewrite.go` | Body rewrite: device-id stamp + UUIDv5 session rotation. |
| `internal/proxy/inject.go` | Mid-stream error frame builder. |
| `internal/anthropic/` | Shared SSE parser + `errscan` (mid-stream `overloaded_error` detection). |
| `internal/ccident/` | Claude Code identity: mandatory system block + static-prompt insertion. |
| `internal/openaigw/` · `internal/anthropicgw/` | The two routing gateways' translation + handlers. |
| `internal/routing/` | Shared routing-gateway plumbing (resolve, retry, usage reporting). |
| `internal/nativeopenai/` | Native OpenAI HTTP/Responses transport, Codex SSE relay, model discovery and usage. |

## Config

| Env | Default | Notes |
|-----|---------|-------|
| `PORT` | `9000` | Listen port. |
| `GATEWAY_BIND_HOST` | all interfaces | Set `127.0.0.1` for isolated local tests. |
| `SERVICE_URL` | `http://service:8787` | Base URL of the Kotlin service control API. |
| `INTERNAL_TOKEN` | — | Shared secret sent as `X-Internal-Token`; must match the service. |
| `UPSTREAM_BASE_URL` | `https://api.anthropic.com` | Anthropic API base. |
| `OPENAI_API_BASE_URL` | `https://api.openai.com/v1` | Operator-owned API-key upstream. |
| `OPENAI_CODEX_BASE_URL` | `https://chatgpt.com/backend-api/codex` | Operator-owned OAuth upstream. |
| `OPENAI_CLIENT_VERSION` | `0.159.2` | Tested fallback catalog compatibility version (operator override; not a latest-version claim). |

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

## Native OpenAI contract

Use `<origin>/openai/v1` with a routing token (`cxr_…`) in `Authorization: Bearer` or
`x-api-key`. `POST /responses` and `GET /models` are the only supported routes. Existing
`/routing/openai/v1/chat/completions` still translates to Claude; it is not an OpenAI account route.

Native requests resolve with `source=routing`, `provider=OPENAI`, and the requested model.
Selection, user/group scope and separate routing daily caps remain service-owned. Accounts from
another provider fail closed. A configured routing-token system prompt prefixes `instructions`.
A capped user's shared pool requires an explicitly configured price for the requested model;
a missing price produces `403 pricing_not_configured` when no personal candidate is available.

`OAUTH` and `OAUTH_STATIC` use the Codex backend; `API_KEY` uses the OpenAI API. OAuth requests
always use `store=false` and streaming upstream. For a non-streaming client the gateway returns
the complete response object from `response.completed` (or `response.incomplete`). When Codex
omits terminal output, completed output items are collected in index order (arrival order when
no index exists) and fill it without losing text, function calls or encrypted reasoning. A
nonempty terminal output remains authoritative. Buffered output is capped at 32 MiB/4096 items. Tool calls,
reasoning and encrypted reasoning items retain the Responses format. Input string shorthand is
converted to a user message for Codex. Other model parameters are validated by the upstream;
Codex subscription capabilities are not identical to the API-key API.

`GET /models` reads the actual catalog of the first usable in-scope account, **not a union** of
all accounts. A bounded semver `client_version` query is honored for Codex catalog filtering;
otherwise `OPENAI_CLIENT_VERSION` supplies the operator-configurable compatibility fallback.
OAuth hidden/disabled entries are removed. The response carries standard `data`
entries and, for OAuth, a filtered `models` catalog usable as a Codex `model_catalog_url`.
The list is a snapshot, not a guarantee that another account chosen later has identical
entitlements; model-not-found errors can move to another candidate before any response is shown.

Clients must send full conversation input. Encrypted reasoning/tool arguments and compaction
items are additionally account-bound. Send a stable `X-Proxy-Session-ID`, Codex `session-id`, or
`thread-id` on the first plaintext turn and every continuation (that priority order applies).
These headers stay local; they are never sent upstream. Bindings are scoped to the authenticated
routing token, retain only a token/session hash plus account ID, expire after 24 idle hours, and
are capped at 4096 sessions. A response that reaches the client binds its account, including a
partial semantic stream. Plaintext can fail over; encrypted history can only use its bound
eligible account. Unknown/expired bindings or an unavailable owner return an actionable 409.
Restarting the gateway or rotating the routing token loses that continuation binding: start a
new conversation with plaintext input rather than silently replaying ciphertext to another
account. Concurrent turns sharing a session are serialized with cancellation; queued requests
are re-authorized after waiting. Plaintext SDK calls without session headers remain supported.

`previous_response_id`, persistent `conversation`,
`store=true` and `background=true` are explicitly rejected rather than silently moving a saved
conversation between accounts. WebSockets, Chat Completions, Files, Batches, response retrieval,
and remote `/responses/compact` are not exposed by this native route. Stored item/file/vector-store/
container/prompt references and hosted file-search tools are rejected: the shared account has no
per-user resource-ownership boundary. Inline input and function/custom tools remain supported. Configure Codex as a
custom provider with `wire_api="responses"`, `requires_openai_auth=false`, and WebSockets disabled.

HTTP 401/403/429/5xx and model-entitlement errors can fail over before output. An SSE error can
fail over only before a real event reaches the client; keep-alive comments do not count. A
visible response is never mixed with another account's response. Errors are sanitized so they
cannot expose upstream account details. Redirects are never followed with account credentials.
Only service-owned authorization/organization/project/workspace headers go upstream; client
cookies, forwarded IP, workspace and authorization headers are not forwarded.

Requests are capped at 16 MiB, buffered responses at 32 MiB and SSE events at 4 MiB. Uploads and client writes have
a 30-second deadline; the upstream header timeout is 60 seconds, the native stream stall budget
uses `UPSTREAM_STALL_SECONDS` (120 seconds when unset/zero), and the whole request
is bounded to 15 minutes. Client cancellation closes upstream. Early streaming comments keep
long waits alive; only complete SSE frames are written.

OpenAI input includes cached input: usage reports subtract cached tokens from `input`, place them
in `cacheRead`, and leave reasoning tokens inside output (never charged twice). Reports retain
the requested model so the pricing check and accounting use the same alias. `x-codex-*`,
`x-ratelimit-*`, and `Retry-After` headers, plus default `codex.rate_limits` SSE windows, update service
limits. Named/model-specific rate-limit events still reach streaming clients but cannot
overwrite the service's default account quota until separate bucket storage is supported. Metadata/model requests carry `free=true` and do not create successful usage rows.

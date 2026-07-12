# gateway (Go proxy) — Spec B

Placeholder for the Go rewrite of the Anthropic datapath (see
`docs/superpowers/specs/2026-07-12-repo-restructure-nginx-routing-design.md`, §"Spec B").

Until Spec B lands, the datapath is served by the **service** (Kotlin); the nginx router points
`/gateway/` at `service:8787`. Spec B adds the Go binary here and flips that one `proxy_pass` to
`gateway:9000`, with the token base URL (`https://<domain>/gateway`) unchanged.

Planned shape (thin data plane):
- Receives `/v1/...` (nginx strips the `/gateway` prefix).
- Calls the service's internal control API (`POST /internal/lease`) to resolve the proxy token,
  pick account(s), and get ephemeral upstream credentials + request-rewrite params.
- Streams to Anthropic and back (early head flush + keep-alives — SSE timing is load-bearing).
- Reports usage + rate-limit headers back (`POST /internal/report`); the service owns all DB,
  crypto, pool state, and OAuth refresh.

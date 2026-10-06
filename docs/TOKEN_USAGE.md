# Token-authenticated quota snapshot

`GET /gateway/v1/usage` accepts a proxy (`cxp_`) or routing (`cxr_`) token in
`Authorization: Bearer <token>` or `x-api-key`. It checks the enabled owner and
`PROXY_USE` / `ROUTING_USE` permission. No management cookie is needed.

The exact nginx location routes this endpoint to the Kotlin service. Requests
use cached pool observations; polling does not make an upstream inference,
record usage or mark an active session. Responses use `Cache-Control: no-store`.

## Response

- `snapshot_at`: Unix seconds at snapshot time.
- `source`: proxy or routing, determined by the token namespace.
- `basis`: `next_request_candidate`. A prediction, not a reservation: session
  affinity, concurrent traffic and failover may change the actual serving account.
- `next_account_index`: index into `accounts`, or null if nothing is eligible.
- `rate_limits`: selected candidate's windows, or null without a candidate.
- `available_accounts`: number eligible for selection now.
- `accounts`: enabled accounts in the caller's personal/shared group scope.
  Each exposes only `scope`, `available` and `rate_limits`, including unavailable
  accounts so clients can show exhausted windows. No account IDs, names,
  email addresses, device identifiers or credentials are returned.
- `daily`: source-specific shared-pool spend (`used_usd`), cap (`limit_usd`,
  null when unlimited), `exhausted`, and next UTC-day `resets_at`. Reaching the
  shared cap does not disable the caller's personal accounts.

Windows `five_hour` / `seven_day` are null when unknown. A known window contains
`used_percentage` (used, not remaining), `resets_at`, `updated_at` (Unix seconds)
and `stale`. Missing/35-minute-old observations and passed reset times are stale;
a passed reset never fabricates a zero usage reading. Individual values can be
null when the upstream omitted them. Pool percentages must not be summed.

For a CLI status line, poll at most once a minute with a short timeout, render
root `rate_limits` and mark stale observations. Native CLI quota indicators do
not automatically discover this endpoint.

```sh
curl --silent --show-error --max-time 5 --config - \
  https://proxy.example.com/gateway/v1/usage <<EOF
header = "Authorization: Bearer $ANTHROPIC_AUTH_TOKEN"
EOF
```

Missing/invalid/revoked keys return 401; missing permission returns 403.
The bundled nginx rejects non-GET methods with 405.

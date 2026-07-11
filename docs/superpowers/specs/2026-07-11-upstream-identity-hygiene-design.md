# Upstream identity hygiene: device-id, session-id rotation, telemetry stripping

**Date:** 2026-07-11
**Status:** Design — approved for planning

## Goal

Make claude-proxy's outbound requests to Anthropic look like independent, genuine
Claude Code clients rather than one shared proxy funnelling many accounts. Concretely:

1. Stop adding the `x-client-id` header the proxy currently injects — real Claude Code
   never sends it, so its presence is a direct "this is a proxy" tell.
2. Strip SDK telemetry headers (`X-Stainless-*`).
3. Give each account a **fixed, distinct device-id** and substitute it into the request
   body so the device fingerprint differs per account.
4. **Rotate the session-id per account** so the same client session-id never appears
   across multiple accounts (which would let Anthropic correlate them as one origin).

## Wire format (captured from live Claude Code 2.1.207 on 2026-07-11)

`POST /v1/messages?beta=true`, relevant headers:

```
Authorization: Bearer …                 (or x-api-key)
anthropic-version: 2023-06-01
anthropic-beta: interleaved-thinking-…,claude-code-20250219,advisor-tool-2026-03-01
anthropic-dangerous-direct-browser-access: true
x-app: cli
User-Agent: claude-cli/2.1.207 (external, sdk-cli)
X-Claude-Code-Session-Id: 1bc056c8-7cf1-4584-84cc-15f6bc4bc130
X-Stainless-Arch / -Lang / -OS / -Package-Version / -Retry-Count / -Runtime / -Runtime-Version / -Timeout
```

The client does **not** send `x-client-id`; the proxy adds it today.

Body `metadata` — the key finding: `user_id` is a **string containing escaped JSON**:

```json
"metadata": {
  "user_id": "{\"device_id\":\"7b70f342…658c4e\",\"account_uuid\":\"\",\"session_id\":\"1bc056c8-…\"}"
}
```

- `device_id`: 64-hex fingerprint. Lives **only** in the body (no device-id header).
- `account_uuid`: empty when authenticating via auth-token; leave untouched.
- `session_id`: matches the `X-Claude-Code-Session-Id` header — must be kept in sync.

## Where the transform runs

All substitution happens **per account** inside `UpstreamForwarder.forward()`, before the
upstream request is built. It knows the chosen account, and since one client request can be
retried across several accounts, each attempt must present that account's own identity.

Applied to every forwarded request (both `/v1/messages` and `count_tokens`).

## Component design

### Header hygiene (`UpstreamForwarder.forward` + `UpstreamAuth`)

- Remove the `x-client-id` injection block from `UpstreamAuth.apply` entirely (and drop the
  now-unused `clientId` parameter / `ACCOUNT_CLIENT_ID_HEADER` handling there).
- In the header-copy loop, skip any header whose lowercase name starts with `x-stainless-`.
- Do not copy the original `X-Claude-Code-Session-Id`; instead append the **replaced**
  session-id value (see rotation). If the client sent no such header, add it only when we have
  a session-id (e.g. recovered from the body).
- Keep `User-Agent`, `x-app`, `anthropic-dangerous-direct-browser-access`, `anthropic-beta`,
  `anthropic-version`, `Accept`, `Content-Type` as the client sent them.

### device-id (fixed per account)

Repurpose the existing `accounts.client_id` column (varchar 64 — fits a 64-hex string):

- Rename the concept to **Device ID** at the code/DTO/UI layer (`AccountRepo`, `Dtos`,
  `model/Models.kt`, `AdminRoutes`, frontend). Keep the **DB column name `client_id`** to
  avoid a rename migration — Exposed maps `deviceId = varchar("client_id", 64)`.
- Generation: random 64-hex on account create. For existing rows whose value is `null` or not
  64-hex (today they're UUID-shaped), lazily backfill a fresh 64-hex when the account is loaded
  into the pool, and persist it.
- UI label "Client ID" → "Device ID" on the account edit forms (admin + My Accounts).

### session-id rotation

Key = `(origin_session_id, account_id)`. `origin_session_id` is read from the
`X-Claude-Code-Session-Id` header, falling back to `metadata.user_id.session_id` in the body.

Resolution (`SessionMapRepo.resolve(origin, accountId) -> replaced`):

1. No origin session-id present → skip rotation (no header/body session change).
2. Row `(origin, account)` exists → use its `replaced`.
3. No row, and `origin` has **no owner yet** → this account becomes owner: write
   `(origin, account, replaced = origin)`, send origin unchanged.
4. No row, but `origin` already owned by a different account → generate a fresh UUID, write
   `(origin, account, replaced = custom)`, send custom.

The `replaced` value is written into **both** the `X-Claude-Code-Session-Id` header and
`metadata.user_id.session_id`.

**First-touch race safety.** If two different accounts see the same brand-new `origin`
concurrently, the owner is decided atomically by inserting into `session_owner` (PK on
`origin`; first insert wins). Only the winning account gets `replaced = origin`; the loser
falls through to the custom-uuid branch. This guarantees the unchanged origin session-id is
presented to exactly one account.

### Body mutation (best-effort)

- Only when the body is JSON and `metadata.user_id` parses to the `{device_id, account_uuid,
  session_id}` shape: replace `device_id` with the account's device-id and `session_id` with
  the replaced value, keep `account_uuid`, re-encode the nested string and the whole body.
- If the shape is absent, leave the body untouched (the header session-id is still rewritten).
- Re-serialization is safe for prompt caching: cached content lives in `messages`/`system`/
  `tools`, which the tokenizer sees post-parse; `metadata` is not part of the cache key.

### LimitProbe

The synthetic 1-token probe request gets the account's device-id and a fixed per-account
probe session-id (no rotation — a probe has no client origin session).

## Database schema (new tables)

```
session_owner(
  origin      varchar(64) PRIMARY KEY,
  account_id  int,
  created_at  timestamp
)

session_map(
  origin      varchar(64),
  account_id  int,
  replaced    varchar(64),
  created_at  timestamp,
  PRIMARY KEY (origin, account_id)
)
```

Added to `ALL_TABLES`; created by the existing `createMissingTablesAndColumns` path. These
are ephemeral routing state (like `account_limits`); no cascade FKs required, but rows may be
pruned by age in a later iteration (out of scope here).

## Storage: hybrid DB + in-memory cache

DB is the source of truth (survives restart, race-safe via `session_owner` PK). A
`SessionMapRepo` keeps an in-memory cache:

- `cache: ConcurrentHashMap<Pair<origin, accountId>, replaced>` for hot lookups.
- `owners: ConcurrentHashMap<origin, accountId>` to short-circuit the owner check.

`resolve` checks the cache first; on miss it runs a single transaction (owner upsert-ignore +
read-back, then map get-or-create) and populates the cache. Cache is empty on boot and
repopulates from DB lazily/on first touch.

## Files touched (anticipated)

- `proxy/UpstreamForwarder.kt` — header filter, session-id header swap, body rewrite hook.
- `accounts/UpstreamAuth.kt` — drop `x-client-id` injection + `clientId` param.
- `accounts/LimitProbe.kt` — device-id + fixed probe session-id, drop clientId usage.
- `accounts/AccountRepo.kt`, `accounts/AccountPool.kt` — `deviceId` field, lazy 64-hex backfill.
- `repo/SessionMapRepo.kt` — **new**, resolve + cache.
- `db/Tables.kt` — `SessionOwner`, `SessionMap` tables; `deviceId = varchar("client_id", 64)`.
- `db/Database.kt` — register new tables (via `ALL_TABLES`).
- `api/Dtos.kt`, `model/Models.kt`, `api/AdminRoutes.kt` — `clientId` → `deviceId` naming.
- `frontend/src/**` — relabel Client ID → Device ID.
- A small body-rewrite helper (new file or private in `UpstreamForwarder`).

## Edge cases

- No `metadata` / non-JSON body (e.g. GET `/v1/models`) → header-only handling, no body touch.
- No session-id anywhere → rotation skipped cleanly.
- Malformed `metadata.user_id` (not the expected nested JSON) → body left as-is.
- Account with legacy UUID `client_id` → backfilled to 64-hex on load, then stable.
- Concurrent first-touch of one origin on two accounts → owner-table PK arbitrates.

## Testing

- Unit: `SessionMapRepo.resolve` — first-touch owns origin; second account gets stable custom;
  same account repeat is stable; concurrent first-touch yields exactly one origin-owner.
- Unit: body rewrite — device-id + session-id swapped, `account_uuid` preserved, non-matching
  bodies untouched, header/body session-id stay in sync.
- Unit: header filter strips `x-stainless-*` and never emits `x-client-id`; keeps UA/x-app.
- Manual: re-run the local capture harness through the proxy and confirm the outbound request
  carries the account's device-id, a per-account session-id, and no telemetry/client-id.

## Out of scope

- Pruning old `session_map` / `session_owner` rows (add a TTL sweep later if the tables grow).
- Per-account variation of `User-Agent` / `X-Stainless-*` values (we strip, not vary).
- Setting a non-empty `account_uuid`.

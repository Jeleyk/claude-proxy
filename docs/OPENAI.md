# Native OpenAI accounts

Native OpenAI requests use a separate account pool selected by `provider=OPENAI`.
Existing accounts migrate to `ANTHROPIC`; Claude Code, built-in Claude chat, and
`/routing/openai` continue to use Anthropic accounts. `/routing/openai` remains the
Chat Completions-to-Claude translator. It does not select OpenAI accounts.

## Add an account

In **Dashboard → Add account**, choose **OpenAI / Codex**. Personal accounts can be
added under **My Accounts** with the same flow.

- **Login with OpenAI** starts device authorization. Open the displayed OpenAI URL,
  enter the short code, and approve the account. The proxy receives access/refresh
  tokens, encrypts them with the existing `MASTER_KEY`, and refreshes them as needed.
  Account login may require enabling device-code authorization in the OpenAI account.
  If that beta sign-in option is unavailable, use the ordinary browser OAuth flow
  on a trusted local machine and import the resulting credentials using **OAuth**.
  Keep the login isolated from existing Codex credentials; never paste credentials
  into a chat or commit an authentication cache. OpenAI documents browser login as
  a [fallback for unavailable device authorization](https://learn.chatgpt.com/docs/auth).
- **API key** uses an OpenAI Platform API key and the ordinary Responses API.
- **OAuth / OAuth static** can import existing credentials. Supply the matching
  ChatGPT account ID as well as the access token, and a refresh token for renewable OAuth.

Device flows last 15 minutes, belong to the initiating user and personal/shared scope,
and are kept only in memory. Restarting the service requires a new login. Tokens are
never returned to the browser. A user can have at most four active device flows.

Priority, thresholds, fallback, account groups, personal ownership and the routing
permission work as for existing accounts, within the selected provider only.
No subscription entitlements are created by the proxy: the selected upstream account
must support the requested model and feature.

## Connect a client

Create a **routing token** (`cxr_…`) in **API Routing**. A Claude proxy token (`cxp_…`)
cannot call the native OpenAI endpoint.

Base URL: `https://YOUR_HOST/openai/v1`

- `GET /models`: model discovery from an available account.
- `POST /responses`: OpenAI Responses over HTTP or SSE.

Choose a model returned by `/models`; model IDs are not translated to Claude aliases.
Model catalogs and entitlements can differ between accounts in the same pool.

```python
import os
from openai import OpenAI

client = OpenAI(base_url="https://YOUR_HOST/openai/v1",
                api_key=os.environ["OPENAI_PROXY_TOKEN"])
print([model.id for model in client.models.list()])
response = client.responses.create(
    model="<model-from-models>", input="Hello", store=False,
)
print(response.output_text)
```

Codex CLI custom provider (`~/.codex/config.toml`):

```toml
model_provider = "proxy_openai"
model = "<model-from-models>"

[model_providers.proxy_openai]
name = "OpenAI via proxy"
base_url = "https://YOUR_HOST/openai/v1"
env_key = "OPENAI_PROXY_TOKEN"
wire_api = "responses"
requires_openai_auth = false
supports_websockets = false
```

Set `OPENAI_PROXY_TOKEN` in the shell before starting Codex. No need to change or
copy Codex's own account credentials. The API Routing page includes client examples.

## Limits and accounting

`GET /gateway/v1/usage?provider=OPENAI&model=<requested-model>` accepts the same
routing token. It returns the next candidate's cached limits plus the caller's
anonymous accessible pool. This is a prediction, not a reservation.

Users with a daily routing USD cap currently cannot consume shared OpenAI accounts,
even if model prices are configured. They can still use their own personal accounts
and discover models. Native Responses usage arrives at the end of a response; a client
disconnect can prevent complete accounting, and the proxy does not yet reserve and
reconcile budgets. Rather than silently bypass a cap, resolution excludes shared
OpenAI accounts and returns `metering_unsupported` if no personal account is available.
The quota snapshot exposes `metering_unsupported: true` under the same condition.
Uncapped users can use the shared pool. Anthropic daily-cap behavior is unchanged.

Subscription percentages do not combine across providers or accounts. `five_hour` and `seven_day`
are populated only when the upstream explicitly identifies those window durations.
Unknown readings are `null`; stale readings remain marked stale rather than becoming zero.
OAuth limits come from the Codex quota endpoint and response headers/events. API keys
have no subscription-window probe; absence of quota data does not mean unlimited access.

Configure explicit OpenAI model prices under **Pricing** for token-cost estimates.
Patterns such as `gpt-…`, `codex-…`, `o3`, or `openai/<model-pattern>`
are recognized. Claude prices never serve as an OpenAI fallback. With no matching price,
usage is marked `costKnown=false`; the UI shows an unknown cost. Missing final usage,
hosted tools, and non-default service tiers are also marked unknown because the current
price table cannot account for every billable dimension. Observed token counts are kept;
missing counts are never fabricated or presented as a confirmed zero-cost request.
For a model-specific availability prediction, include `model` in the quota request.

Input, output and cached input tokens are recorded; cached input is removed from regular
input to avoid double billing. Reasoning tokens are already included in output tokens.
The monetary value is configured metering, not a separate charge for a subscription.

## Protocol boundaries

- Responses only: no native Chat Completions, WebSocket, Files, Batches, Realtime or
  response-retrieval endpoint. The existing Claude compatibility routes are unchanged.
  References to stored items, files, vector stores, containers, uploaded skills and saved prompts are
  rejected because the shared account has no per-user ownership boundary for those resources.
  Tool definitions use an explicit allowlist, including definitions in namespaces and
  dynamic tool-result input. Function/custom tools, client-executed shell/apply-patch/computer
  tools, web search and fresh automatic code-interpreter containers are supported.
  Hosted shell, tool search, MCP connectors and unknown tool kinds are rejected pending
  separate resource-ownership review. Standard MCP tools exposed by clients as function
  definitions remain supported.
- Send the complete conversation input each time. `previous_response_id`, `conversation`,
  `store=true`, and `background=true` are rejected. The gateway uses `store=false`.
  This avoids relying on stored responses owned by an account that may rotate.
- Encrypted reasoning/compaction can belong to a particular upstream account. Such input
  requires a known session binding and cannot rotate to another account. Codex supplies
  `session-id` automatically; SDK clients can supply a stable `X-Proxy-Session-ID` from the
  first request. Bindings are scoped to the routing token, held in a bounded memory cache for up to 24 hours of inactivity, and
  lost on gateway restart or cache eviction. If the binding is missing or its account is unavailable, the
  request fails explicitly: wait for that account or start a fresh conversation with
  plaintext history. The proxy never silently removes encrypted history.
- ChatGPT/Codex upstream requests stream internally; non-stream clients receive the final
  response object. Tool-call and reasoning events are preserved in streaming mode.
- Retry another eligible account only before any visible upstream output. Once output
  starts, a failure ends that response instead of mixing content from different accounts.
- The Codex subscription backend is not identical to the public Responses API. It may
  reject parameters or tools that the public API accepts. Device login/quota protocol
  changes upstream may require an update here.
- Usage reporting shares the existing retrying control-plane transport. It does not provide
  exactly-once billing when a report is committed but its acknowledgement is lost.

## Deployment and validation

Rebuild service, gateway, frontend and edge nginx together. Database changes add `provider`
to accounts (default `ANTHROPIC`) and a known-price marker to usage; existing rows keep their
previous behavior. Back up the database and `MASTER_KEY` before upgrading. Keep old images
for rollback. Once OpenAI credentials have been added, do not run an Anthropic-only older
service against that database: it cannot distinguish the new provider. Prefer a forward fix;
a full downgrade requires a reviewed, matching database backup and preservation of later data.

The bundled nginx forwards `/openai/` to the Go gateway without stripping the prefix.
For a separate public API ingress, permit only `GET /openai/v1/models` and
`POST /openai/v1/responses`; keep management and `/internal/*` private.
Operator upstream overrides are documented in `.env.example`; do not expose them as client
parameters. Responses are never redirected with account credentials to another origin.

Run the Kotlin and Go suites, Go vet, and the frontend TypeScript/Vite build. The tests
use isolated fake upstreams for device login, refresh, limits, provider isolation,
streaming/failover, revocation and pricing. A real device login and inference request
are still needed to verify the current upstream behavior for a particular account.

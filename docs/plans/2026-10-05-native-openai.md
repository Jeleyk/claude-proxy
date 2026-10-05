# Native OpenAI provider

Approved intent: connect ChatGPT/Codex accounts in the existing account manager,
route requests using existing user keys/permissions/groups, track quotas and rotate
within the matching provider. Preserve existing Claude behavior and hardening.

Architecture: persisted provider ANTHROPIC (default) / OPENAI orthogonal to account
credential type. Provider-scoped pool selection and upstream auth. A separate
/openai/v1 Responses and model-list surface uses routing tokens and routing spend.
Existing /routing/openai continues translating client requests to Claude.

OpenAI device OAuth flow is server-side, short-lived, user/scope-bound and single
use. Tokens are encrypted through existing account storage. Refreshes serialize
per account. Account/workspace identity comes from the OAuth result, never a
client inference header. No client-defined upstream URLs.

Responses HTTP/SSE preserves tool and reasoning events; transport adapters isolate
API-key and ChatGPT backends. No retry after visible output. Stateful continuations
must stay on the same account or fail explicitly; never silently rotate them.
OpenAI quota observations preserve unknown/stale values and feed existing selector
and token usage snapshot. No fabricated zero readings or invented dollar charges.

Agents: service core/provider isolation; Go transport and protocol; frontend account
flows. Root: OAuth/device lifecycle, OpenAI quota reads, integration, security review,
documentation, build and deployment. Keep agents' file ownership disjoint.

Acceptance: old tests + provider isolation/migrations; fake OAuth success/pending/
expiry/revocation and no secret leakage; OpenAI fake upstream streaming/nonstream,
tools, cancellation, failover and usage; frontend production build; live account
login and one controlled inference when user completes OAuth. Deploy with database
backup and rollback; retain private admin and public API-only exposure.

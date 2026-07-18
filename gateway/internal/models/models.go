// Package models is the catalogue of Claude models the routing gateways advertise via their
// `/v1/models` endpoints (OpenAI-shaped and Anthropic-shaped). Under the hood every request is
// served by Claude through a subscription account, so these are the only real targets.
package models

// Model is one advertised Claude model.
type Model struct {
	ID          string
	DisplayName string
}

// Claude lists the models exposed by both routing gateways, newest/most-capable first. IDs are
// the exact strings the subscription accounts actually serve (verified against live proxy traffic);
// any other valid claude-* id also works, since the gateway forwards the model verbatim.
var Claude = []Model{
	{ID: "claude-fable-5", DisplayName: "Claude Fable 5"},
	{ID: "claude-opus-4-8", DisplayName: "Claude Opus 4.8"},
	{ID: "claude-opus-4-7", DisplayName: "Claude Opus 4.7"},
	{ID: "claude-opus-4-6", DisplayName: "Claude Opus 4.6"},
	{ID: "claude-opus-4-5", DisplayName: "Claude Opus 4.5"},
	{ID: "claude-sonnet-5", DisplayName: "Claude Sonnet 5"},
	{ID: "claude-sonnet-4-6", DisplayName: "Claude Sonnet 4.6"},
	{ID: "claude-sonnet-4-5", DisplayName: "Claude Sonnet 4.5"},
	{ID: "claude-haiku-4-5-20251001", DisplayName: "Claude Haiku 4.5"},
}

// Created is a fixed epoch-seconds "created" timestamp for the OpenAI model list (clients only
// check presence/shape). Kept constant so responses are deterministic.
const Created int64 = 1735689600 // 2025-01-01T00:00:00Z

// CreatedAt is the Anthropic `created_at` RFC3339 stamp, constant for the same reason.
const CreatedAt = "2025-01-01T00:00:00Z"

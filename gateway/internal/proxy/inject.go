package proxy

import "claudeproxy/gateway/internal/control"

// midStreamError builds the client-facing SSE error frame when a retryable error surfaces
// mid-stream (after the head is already out). Full port of injectStreamError lands in Task B5.
func midStreamError(cands []control.Candidate, curIdx int) (frame string, recordStatus int) {
	return "", 0
}

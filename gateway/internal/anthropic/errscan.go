package anthropic

import "strings"

// ErrScan incrementally scans a streamed response for a *retryable* error frame that arrives
// after the 200 head is already out, mirroring the Kotlin SseErrorScanner. Shared by the Claude Code datapath and the routing gateways. Anthropic signals a
// mid-stream failure with a dedicated SSE frame:
//
//	event: error
//	data: {"type":"error","error":{"type":"overloaded_error","message":"..."}}
//
// `event: error` is an SSE field line, so model-generated text can't forge it. Once seen, we
// read the inner error type; only rate_limit_error / overloaded_error / api_error are retryable.
type ErrScan struct {
	retryableType string
	sawErrorEvent bool
	carry         string
}

var retryableErrTypes = []string{"rate_limit_error", "overloaded_error", "api_error"}

// Feed scans another chunk. Returns nothing; call retryable() to read the result.
func (e *ErrScan) Feed(b []byte) {
	if len(b) == 0 || e.retryableType != "" {
		return
	}
	text := e.carry + string(b)
	if !e.sawErrorEvent && strings.Contains(text, "event: error") {
		e.sawErrorEvent = true
	}
	if e.sawErrorEvent {
		for _, t := range retryableErrTypes {
			if strings.Contains(text, `"`+t+`"`) {
				e.retryableType = t
				break
			}
		}
	}
	// Keep a tail large enough to bridge an `event: error` line or type token split across chunks.
	if len(text) > 256 {
		e.carry = text[len(text)-256:]
	} else {
		e.carry = text
	}
}

// Retryable returns the retryable error type seen mid-stream, or "" if none.
func (e *ErrScan) Retryable() string { return e.retryableType }

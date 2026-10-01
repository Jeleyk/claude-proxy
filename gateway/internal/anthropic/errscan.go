package anthropic

import "strings"

// ErrScan incrementally scans a streamed response for a *retryable* error frame that arrives
// after the 200 head is already out, mirroring the Kotlin SseErrorScanner. Shared by the Claude Code datapath and the routing gateways. Anthropic signals a
// mid-stream failure with a dedicated SSE frame:
//
//	event: error
//	data: {"type":"error","error":{"type":"overloaded_error","message":"..."}}
//
// `event: error` only counts as an SSE field line when it starts one — anchoring the match is what
// keeps model output from forging it. A plan or a doc that quotes an error frame arrives as a
// `text_delta` whose newlines are escaped (`\n`, two characters), so the token appears mid-line and
// is ignored; an unanchored substring search would see it and kill a perfectly healthy answer.
// Once a real error event is seen we read the inner error type; only rate_limit_error /
// overloaded_error / api_error are retryable.
type ErrScan struct {
	retryableType string
	sawErrorEvent bool
	started       bool
	carry         string
}

var retryableErrTypes = []string{"rate_limit_error", "overloaded_error", "api_error"}

// Feed scans another chunk. Returns nothing; call retryable() to read the result.
func (e *ErrScan) Feed(b []byte) {
	if len(b) == 0 || e.retryableType != "" {
		return
	}
	text := e.carry + string(b)
	if !e.sawErrorEvent {
		// A line start is either the very first byte of the stream or a byte after a newline.
		if !e.started && strings.HasPrefix(text, "event: error") {
			e.sawErrorEvent = true
		} else if strings.Contains(text, "\nevent: error") {
			e.sawErrorEvent = true
		}
	}
	e.started = true
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

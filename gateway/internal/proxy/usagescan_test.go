package proxy

import "testing"

func TestUsageScanAcrossChunks(t *testing.T) {
	var s usageScan
	// message_start with input + cache counters, split mid-number across two feeds.
	part1 := `event: message_start
data: {"type":"message_start","message":{"usage":{"input_tokens":12`
	part2 := `00,"cache_read_input_tokens":10,"cache_creation_input_tokens":512}}}

event: message_delta
data: {"type":"message_delta","usage":{"output_tokens":50}}

event: message_delta
data: {"type":"message_delta","usage":{"output_tokens":340}}

`
	s.Feed([]byte(part1))
	s.Feed([]byte(part2))

	if s.Input != 1200 {
		t.Errorf("Input = %d, want 1200", s.Input)
	}
	if s.Output != 340 {
		t.Errorf("Output = %d, want 340 (max-seen)", s.Output)
	}
	if s.CacheRead != 10 {
		t.Errorf("CacheRead = %d, want 10", s.CacheRead)
	}
	if s.CacheWrite != 512 {
		t.Errorf("CacheWrite = %d, want 512", s.CacheWrite)
	}
}

func TestErrScanDetectsRetryableFrame(t *testing.T) {
	var e errScan
	e.Feed([]byte("event: error\n"))
	e.Feed([]byte(`data: {"type":"error","error":{"type":"overloaded_error","message":"x"}}` + "\n\n"))
	if e.retryable() != "overloaded_error" {
		t.Errorf("retryable = %q, want overloaded_error", e.retryable())
	}
}

func TestErrScanIgnoresTextMentioningError(t *testing.T) {
	var e errScan
	// A normal content delta that merely quotes "overloaded_error" in assistant text — no
	// `event: error` field line, so it must not trip the scanner.
	e.Feed([]byte(`data: {"type":"content_block_delta","delta":{"text":"the term overloaded_error means..."}}` + "\n\n"))
	if e.retryable() != "" {
		t.Errorf("retryable = %q, want empty", e.retryable())
	}
}

package proxy

import (
	"testing"

	"claudeproxy/gateway/internal/anthropic"
	"claudeproxy/gateway/internal/control"
)

func TestStreamScanAcrossChunks(t *testing.T) {
	var s streamScan
	// message_start with input + cache counters, split mid-number across two feeds.
	part1 := `event: message_start
data: {"type":"message_start","message":{"model":"claude-opus-5","usage":{"input_tokens":12`
	part2 := `00,"cache_read_input_tokens":10,"cache_creation_input_tokens":512}}}

event: message_delta
data: {"type":"message_delta","usage":{"output_tokens":50}}

event: message_delta
data: {"type":"message_delta","usage":{"output_tokens":340}}

`
	s.Feed([]byte(part1))
	s.Feed([]byte(part2))

	u := s.Usage()
	if u.Input != 1200 {
		t.Errorf("Input = %d, want 1200", u.Input)
	}
	if u.Output != 340 {
		t.Errorf("Output = %d, want 340 (last cumulative)", u.Output)
	}
	if u.CacheRead != 10 {
		t.Errorf("CacheRead = %d, want 10", u.CacheRead)
	}
	if u.CacheWrite != 512 {
		t.Errorf("CacheWrite = %d, want 512", u.CacheWrite)
	}
	if u.Model != "claude-opus-5" {
		t.Errorf("Model = %q, want claude-opus-5", u.Model)
	}
}

// Claude Code writes its main-loop prefix with ttl:"1h", billed at 2× input instead of 1.25×.
// The split arrives only in the nested cache_creation object.
func TestStreamScanSplitsCacheWriteByTTL(t *testing.T) {
	var s streamScan
	s.Feed([]byte("event: message_start\n" +
		`data: {"type":"message_start","message":{"model":"claude-opus-5","usage":{` +
		`"input_tokens":100,"cache_creation_input_tokens":9000,` +
		`"cache_creation":{"ephemeral_1h_input_tokens":8000,"ephemeral_5m_input_tokens":1000},` +
		`"server_tool_use":{"web_search_requests":3,"web_fetch_requests":1},"speed":"fast"}}}` + "\n\n"))

	u := s.Usage()
	if u.CacheWrite != 9000 || u.CacheWrite1h != 8000 {
		t.Errorf("cache write = %d (1h %d), want 9000 (1h 8000)", u.CacheWrite, u.CacheWrite1h)
	}
	if u.WebSearch != 3 || u.WebFetch != 1 {
		t.Errorf("server tools = %d/%d, want 3/1", u.WebSearch, u.WebFetch)
	}
	if !u.Fast {
		t.Error("speed:fast must set Fast")
	}
}

// A response carrying only the breakdown must still yield a total the 1h slice fits inside,
// otherwise the service would price a 1h slice larger than the writes it belongs to.
func TestStreamScanDerivesCacheWriteTotalFromBreakdown(t *testing.T) {
	var s streamScan
	s.Feed([]byte("event: message_start\n" +
		`data: {"type":"message_start","message":{"usage":{"input_tokens":1,` +
		`"cache_creation":{"ephemeral_1h_input_tokens":700,"ephemeral_5m_input_tokens":300}}}}` + "\n\n"))

	u := s.Usage()
	if u.CacheWrite != 1000 || u.CacheWrite1h != 700 {
		t.Errorf("cache write = %d (1h %d), want 1000 (1h 700)", u.CacheWrite, u.CacheWrite1h)
	}
}

// Counters repeated across message_start/message_delta must never regress to a later zero.
func TestStreamScanKeepsCountersMonotonic(t *testing.T) {
	var s streamScan
	s.Feed([]byte("event: message_start\n" +
		`data: {"type":"message_start","message":{"usage":{"input_tokens":500,"cache_read_input_tokens":40}}}` + "\n\n" +
		"event: message_delta\n" +
		`data: {"type":"message_delta","usage":{"output_tokens":12}}` + "\n\n"))

	u := s.Usage()
	if u.Input != 500 || u.CacheRead != 40 || u.Output != 12 {
		t.Errorf("usage = in %d / cr %d / out %d, want 500/40/12", u.Input, u.CacheRead, u.Output)
	}
}

func TestApplyUsagePrefersResponseModel(t *testing.T) {
	requested := "claude-fable-5"
	var report control.UsageReport
	// Server-side refusal fallback: the request asked for one model, another answered.
	applyUsage(&report, anthropic.Usage{Model: "claude-opus-4-8", Input: 7}, &requested)
	if report.Model == nil || *report.Model != "claude-opus-4-8" {
		t.Errorf("model = %v, want claude-opus-4-8 (the model that answered)", report.Model)
	}

	// No model in the response (error bodies, count_tokens) → fall back to the request.
	report = control.UsageReport{}
	applyUsage(&report, anthropic.Usage{}, &requested)
	if report.Model == nil || *report.Model != requested {
		t.Errorf("model = %v, want the request model %q", report.Model, requested)
	}
}

func TestErrScanDetectsRetryableFrame(t *testing.T) {
	var e anthropic.ErrScan
	e.Feed([]byte("event: error\n"))
	e.Feed([]byte(`data: {"type":"error","error":{"type":"overloaded_error","message":"x"}}` + "\n\n"))
	if e.Retryable() != "overloaded_error" {
		t.Errorf("retryable = %q, want overloaded_error", e.Retryable())
	}
}

func TestErrScanIgnoresTextMentioningError(t *testing.T) {
	var e anthropic.ErrScan
	// A normal content delta that merely quotes "overloaded_error" in assistant text — no
	// `event: error` field line, so it must not trip the scanner.
	e.Feed([]byte(`data: {"type":"content_block_delta","delta":{"text":"the term overloaded_error means..."}}` + "\n\n"))
	if e.Retryable() != "" {
		t.Errorf("retryable = %q, want empty", e.Retryable())
	}
}

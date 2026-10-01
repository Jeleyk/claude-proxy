package anthropic

import "testing"

func TestSSEParserSplitAcrossChunks(t *testing.T) {
	var p SSEParser
	// Feed a single event split across three arbitrary byte boundaries.
	full := "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-x\",\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":2}}}\n\n"
	var events []Event
	events = append(events, p.Feed([]byte(full[:20]))...)
	events = append(events, p.Feed([]byte(full[20:55]))...)
	events = append(events, p.Feed([]byte(full[55:]))...)
	if len(events) != 1 {
		t.Fatalf("want 1 event, got %d", len(events))
	}
	if events[0].Type != "message_start" {
		t.Fatalf("event type = %q", events[0].Type)
	}
	var u Usage
	u.Consume(events[0])
	if u.Input != 10 || u.CacheRead != 2 || u.Model != "claude-x" {
		t.Fatalf("usage after message_start = %+v", u)
	}
}

func TestSSEParserMultipleEventsOneFeed(t *testing.T) {
	var p SSEParser
	stream := "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\n\n" +
		"event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":7}}\n\n"
	events := p.Feed([]byte(stream))
	if len(events) != 2 {
		t.Fatalf("want 2 events, got %d", len(events))
	}
	var u Usage
	for _, e := range events {
		u.Consume(e)
	}
	if u.Output != 7 {
		t.Fatalf("output tokens = %d, want 7", u.Output)
	}
}

func TestSSEParserErrorStatus(t *testing.T) {
	var p SSEParser
	events := p.Feed([]byte("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}\n\n"))
	if len(events) != 1 {
		t.Fatalf("want 1 event, got %d", len(events))
	}
	var u Usage
	u.Consume(events[0])
	if u.Status != 529 {
		t.Fatalf("overloaded_error should record 529, got %d", u.Status)
	}
}

func TestSSEParserTypeFromDataWhenNoEventLine(t *testing.T) {
	var p SSEParser
	events := p.Feed([]byte("data: {\"type\":\"ping\"}\n\n"))
	if len(events) != 1 || events[0].Type != "ping" {
		t.Fatalf("type should fall back to data.type: %+v", events)
	}
}

// A plan or a doc that quotes an SSE error frame arrives as a `text_delta`, where the newlines are
// escaped — so `event: error` appears mid-line and must not arm the scanner. An unanchored
// substring search saw it and killed healthy answers about error handling.
func TestErrScanIgnoresAForgedErrorEventInModelText(t *testing.T) {
	var e ErrScan
	e.Feed([]byte("event: content_block_delta\n" +
		`data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"The frame is event: error\ndata: {\"type\":\"error\"}"}}` + "\n\n"))
	e.Feed([]byte("event: content_block_delta\n" +
		`data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"and the type is overloaded_error, which we retry"}}` + "\n\n"))
	if got := e.Retryable(); got != "" {
		t.Errorf("Retryable = %q, want \"\" — model text cannot forge an error event", got)
	}
}

// A real error frame still arms it, whatever the chunk boundaries.
func TestErrScanDetectsARealFrameSplitAcrossChunks(t *testing.T) {
	var e ErrScan
	e.Feed([]byte("event: content_block_delta\ndata: {}\n\nevent: er"))
	e.Feed([]byte("ror\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloa"))
	e.Feed([]byte("ded_error\",\"message\":\"Overloaded\"}}\n\n"))
	if got := e.Retryable(); got != "overloaded_error" {
		t.Errorf("Retryable = %q, want overloaded_error", got)
	}
}

// The very first bytes of a stream are a line start too.
func TestErrScanDetectsAnErrorEventAtTheStreamStart(t *testing.T) {
	var e ErrScan
	e.Feed([]byte("event: error\n" + `data: {"type":"error","error":{"type":"rate_limit_error"}}` + "\n\n"))
	if got := e.Retryable(); got != "rate_limit_error" {
		t.Errorf("Retryable = %q, want rate_limit_error", got)
	}
}

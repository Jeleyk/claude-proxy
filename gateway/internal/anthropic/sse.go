// Package anthropic holds the shared Anthropic Messages wire types + an incremental SSE parser
// used by both routing gateways (native passthrough and OpenAI translation).
package anthropic

import (
	"bytes"
	"encoding/json"
	"strings"
)

// Event is one parsed Anthropic SSE event: the `event:` type and the `data:` JSON payload.
type Event struct {
	Type string
	Data json.RawMessage
}

// SSEParser incrementally splits an Anthropic SSE byte stream into complete events. Feed may be
// called with arbitrary chunk boundaries; it buffers a partial trailing event until completed.
type SSEParser struct {
	buf []byte
}

// Feed appends bytes and returns every complete event now available.
func (p *SSEParser) Feed(b []byte) []Event {
	p.buf = append(p.buf, b...)
	var events []Event
	for {
		idx := bytes.Index(p.buf, []byte("\n\n"))
		if idx < 0 {
			break
		}
		block := p.buf[:idx]
		p.buf = p.buf[idx+2:]
		if ev, ok := parseBlock(block); ok {
			events = append(events, ev)
		}
	}
	return events
}

func parseBlock(block []byte) (Event, bool) {
	var eventType string
	var dataParts []string
	for _, raw := range strings.Split(string(block), "\n") {
		line := strings.TrimRight(raw, "\r")
		switch {
		case strings.HasPrefix(line, "event:"):
			eventType = strings.TrimSpace(line[len("event:"):])
		case strings.HasPrefix(line, "data:"):
			v := line[len("data:"):]
			v = strings.TrimPrefix(v, " ")
			dataParts = append(dataParts, v)
		default:
			// comment (":") or blank — ignore.
		}
	}
	data := strings.Join(dataParts, "\n")
	if data == "" && eventType == "" {
		return Event{}, false
	}
	// Anthropic always sets a "type" inside data; fall back to it when no event: line was seen.
	if eventType == "" {
		var probe struct {
			Type string `json:"type"`
		}
		if err := json.Unmarshal([]byte(data), &probe); err == nil {
			eventType = probe.Type
		}
	}
	return Event{Type: eventType, Data: json.RawMessage(data)}, true
}

// Usage accumulates token usage + model + a recorded status from a response (streamed or
// buffered). Everything Anthropic prices separately lives here, because the service turns this
// struct straight into money.
type Usage struct {
	Input, Output, CacheRead, CacheWrite int64
	// CacheWrite1h is the 1-hour-TTL slice of CacheWrite. Anthropic bills a 1h cache write at
	// 2× the input rate against 1.25× for the default 5-minute TTL, and Claude Code ≥2.1 writes
	// its main-loop prefix with ttl:"1h" — so without this split most cache-write spend is
	// under-counted by ~60%.
	CacheWrite1h int64
	// Server-side tool calls Anthropic bills per invocation rather than per token.
	WebSearch, WebFetch int64
	// Fast is set when the response was served in fast mode (`speed: "fast"`), which is a
	// premium price tier on the same model.
	Fast bool
	// Model as reported by the *response*: the model that actually answered, which is not always
	// the one the request asked for (server-side refusal fallback swaps it mid-call).
	Model  string
	Status int
}

// Consume folds one event's usage into u. Text/tool deltas carry no usage and are ignored here.
func (u *Usage) Consume(ev Event) {
	switch ev.Type {
	case "message_start":
		var p struct {
			Message struct {
				Model string    `json:"model"`
				Usage usageWire `json:"usage"`
			} `json:"message"`
			// Belt and braces: accept usage at the top level too, so a shape that ever drops the
			// `message` envelope under-reports nothing (merging by max makes this safe).
			Usage usageWire `json:"usage"`
		}
		if json.Unmarshal(ev.Data, &p) == nil {
			if p.Message.Model != "" {
				u.Model = p.Message.Model
			}
			u.merge(p.Message.Usage)
			u.merge(p.Usage)
		}
	case "message_delta":
		// message_delta carries the cumulative output count, and on current API versions repeats
		// the input/cache totals. Merging by max keeps both shapes correct.
		var p struct {
			Usage usageWire `json:"usage"`
		}
		if json.Unmarshal(ev.Data, &p) == nil {
			u.merge(p.Usage)
		}
	case "error":
		var p struct {
			Error struct {
				Type string `json:"type"`
			} `json:"error"`
		}
		if json.Unmarshal(ev.Data, &p) == nil {
			u.Status = statusForErrorType(p.Error.Type)
		}
	}
}

// merge folds one wire usage object in, taking the max per counter: the same counters are
// repeated across message_start/message_delta and must never regress to a later zero.
func (u *Usage) merge(w usageWire) {
	u.Input = maxInt64(u.Input, w.Input)
	u.Output = maxInt64(u.Output, w.Output)
	u.CacheRead = maxInt64(u.CacheRead, w.CacheRead)
	u.CacheWrite = maxInt64(u.CacheWrite, w.CacheCreate)
	if w.CacheCreation != nil {
		u.CacheWrite1h = maxInt64(u.CacheWrite1h, w.CacheCreation.Ephemeral1h)
		// Older/edge responses omit the flat total but carry the breakdown; keep them consistent
		// so the 1h slice can never exceed the total the service prices against.
		u.CacheWrite = maxInt64(u.CacheWrite, w.CacheCreation.Ephemeral1h+w.CacheCreation.Ephemeral5m)
	}
	if w.ServerToolUse != nil {
		u.WebSearch = maxInt64(u.WebSearch, w.ServerToolUse.WebSearch)
		u.WebFetch = maxInt64(u.WebFetch, w.ServerToolUse.WebFetch)
	}
	if w.Speed == "fast" {
		u.Fast = true
	}
}

// Normalize clamps the derived fields so downstream pricing can trust them blindly.
func (u *Usage) Normalize() {
	if u.CacheWrite1h > u.CacheWrite {
		u.CacheWrite1h = u.CacheWrite
	}
}

type usageWire struct {
	Input       int64 `json:"input_tokens"`
	Output      int64 `json:"output_tokens"`
	CacheRead   int64 `json:"cache_read_input_tokens"`
	CacheCreate int64 `json:"cache_creation_input_tokens"`
	// Per-TTL breakdown of cache_creation_input_tokens (the flat field stays the total).
	CacheCreation *struct {
		Ephemeral1h int64 `json:"ephemeral_1h_input_tokens"`
		Ephemeral5m int64 `json:"ephemeral_5m_input_tokens"`
	} `json:"cache_creation"`
	ServerToolUse *struct {
		WebSearch int64 `json:"web_search_requests"`
		WebFetch  int64 `json:"web_fetch_requests"`
	} `json:"server_tool_use"`
	Speed string `json:"speed"`
}

// ParseMessageJSON extracts usage + model from a buffered (non-streamed) Messages response.
// Unparseable bodies yield a zero Usage — accounting is best-effort and never fails a request.
func ParseMessageJSON(body []byte) Usage {
	var obj struct {
		Model string    `json:"model"`
		Usage usageWire `json:"usage"`
	}
	var u Usage
	if json.Unmarshal(body, &obj) != nil {
		return u
	}
	u.Model = obj.Model
	u.merge(obj.Usage)
	u.Normalize()
	return u
}

func maxInt64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}

// statusForErrorType maps an Anthropic mid-stream error type to the status recorded for the usage
// report, so the service parks a rate-limited/overloaded account.
func statusForErrorType(t string) int {
	switch t {
	case "overloaded_error":
		return 529
	case "rate_limit_error":
		return 429
	case "authentication_error", "permission_error":
		return 401
	default:
		return 0
	}
}

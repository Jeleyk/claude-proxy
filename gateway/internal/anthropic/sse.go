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

// Usage accumulates token usage + model + a recorded status from the event stream.
type Usage struct {
	Input, Output, CacheRead, CacheWrite int64
	Model                                string
	Status                               int
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
		}
		if json.Unmarshal(ev.Data, &p) == nil {
			if p.Message.Model != "" {
				u.Model = p.Message.Model
			}
			u.Input = p.Message.Usage.Input
			u.CacheRead = p.Message.Usage.CacheRead
			u.CacheWrite = p.Message.Usage.CacheCreate
			if p.Message.Usage.Output > 0 {
				u.Output = p.Message.Usage.Output
			}
		}
	case "message_delta":
		var p struct {
			Usage usageWire `json:"usage"`
		}
		if json.Unmarshal(ev.Data, &p) == nil && p.Usage.Output > 0 {
			u.Output = p.Usage.Output
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

type usageWire struct {
	Input       int64 `json:"input_tokens"`
	Output      int64 `json:"output_tokens"`
	CacheRead   int64 `json:"cache_read_input_tokens"`
	CacheCreate int64 `json:"cache_creation_input_tokens"`
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

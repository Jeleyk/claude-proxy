package openaigw

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"

	"claudeproxy/gateway/internal/routing"
)

// collectChunks parses the OpenAI SSE `data:` frames a streamWriter produced (ignoring [DONE]
// and keep-alive comments).
func collectChunks(t *testing.T, out string) []map[string]any {
	t.Helper()
	var chunks []map[string]any
	for _, block := range strings.Split(out, "\n\n") {
		line := strings.TrimSpace(block)
		if !strings.HasPrefix(line, "data:") {
			continue
		}
		payload := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
		if payload == "[DONE]" {
			continue
		}
		var m map[string]any
		if err := json.Unmarshal([]byte(payload), &m); err != nil {
			t.Fatalf("bad chunk json %q: %v", payload, err)
		}
		chunks = append(chunks, m)
	}
	return chunks
}

func firstDelta(chunk map[string]any) map[string]any {
	choices, _ := chunk["choices"].([]any)
	if len(choices) == 0 {
		return nil
	}
	c, _ := choices[0].(map[string]any)
	d, _ := c["delta"].(map[string]any)
	return d
}

func TestStreamTextAndToolCall(t *testing.T) {
	stream := strings.Join([]string{
		`event: message_start`,
		`data: {"type":"message_start","message":{"id":"m","model":"claude-x","usage":{"input_tokens":5}}}`, ``,
		`event: content_block_start`,
		`data: {"type":"content_block_start","index":0,"content_block":{"type":"text"}}`, ``,
		`event: content_block_delta`,
		`data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}`, ``,
		`event: content_block_stop`,
		`data: {"type":"content_block_stop","index":0}`, ``,
		`event: content_block_start`,
		`data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"f"}}`, ``,
		`event: content_block_delta`,
		`data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"a\":1}"}}`, ``,
		`event: message_delta`,
		`data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}`, ``,
		`event: message_stop`,
		`data: {"type":"message_stop"}`, ``,
	}, "\n")

	var buf bytes.Buffer
	tr := New()
	sw := tr.NewStreamWriter(&buf, routing.Prepared{State: &state{echoModel: "gpt-4o", id: "chatcmpl-x", created: 1, includeUsage: true}})
	// Feed in two arbitrary slices to exercise chunk-boundary handling.
	half := len(stream) / 2
	sw.Feed([]byte(stream[:half]))
	sw.Feed([]byte(stream[half:]))
	sw.Finish()

	out := buf.String()
	if !strings.HasSuffix(strings.TrimSpace(out), "data: [DONE]") {
		t.Fatalf("stream must end with [DONE]:\n%s", out)
	}
	chunks := collectChunks(t, out)
	if len(chunks) == 0 {
		t.Fatalf("no chunks produced")
	}

	// First delta carries the assistant role.
	if d := firstDelta(chunks[0]); d["role"] != "assistant" {
		t.Fatalf("first chunk should set role=assistant, got %v", d)
	}

	var sawText, sawToolStart, sawToolArgs, sawFinish, sawUsage bool
	for _, ch := range chunks {
		if ch["object"] != "chat.completion.chunk" {
			t.Fatalf("bad object: %v", ch["object"])
		}
		if u, ok := ch["usage"].(map[string]any); ok {
			sawUsage = true
			if u["completion_tokens"].(float64) != 9 {
				t.Fatalf("usage completion tokens = %v", u["completion_tokens"])
			}
		}
		d := firstDelta(ch)
		if d != nil {
			if d["content"] == "Hello" {
				sawText = true
			}
			if tcs, ok := d["tool_calls"].([]any); ok {
				tc := tcs[0].(map[string]any)
				fn, _ := tc["function"].(map[string]any)
				if tc["id"] == "toolu_1" && fn["name"] == "f" {
					sawToolStart = true
				}
				if fn != nil && fn["arguments"] == `{"a":1}` {
					sawToolArgs = true
				}
			}
		}
		if choices, ok := ch["choices"].([]any); ok && len(choices) > 0 {
			if choices[0].(map[string]any)["finish_reason"] == "tool_calls" {
				sawFinish = true
			}
		}
	}
	if !sawText || !sawToolStart || !sawToolArgs || !sawFinish || !sawUsage {
		t.Fatalf("missing signal: text=%v toolStart=%v toolArgs=%v finish=%v usage=%v", sawText, sawToolStart, sawToolArgs, sawFinish, sawUsage)
	}

	u := sw.Usage()
	if u.Input != 5 || u.Output != 9 || u.Model != "claude-x" {
		t.Fatalf("scanned usage wrong: %+v", u)
	}
}

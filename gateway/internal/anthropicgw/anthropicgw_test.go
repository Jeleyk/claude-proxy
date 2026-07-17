package anthropicgw

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"testing"

	"claudeproxy/gateway/internal/ccident"
	"claudeproxy/gateway/internal/routing"
)

func TestPrepareInjectsPromptAndKeepsPath(t *testing.T) {
	tr := New()
	rr := httptest.NewRecorder()
	prep, ok := tr.Prepare(rr, "POST", "/v1/messages", "/v1/messages?beta=true",
		[]byte(`{"model":"claude-x","stream":true,"messages":[{"role":"user","content":"hi"}]}`))
	if !ok {
		t.Fatalf("prepare failed")
	}
	if prep.UpstreamPath != "/v1/messages?beta=true" {
		t.Fatalf("upstream path not preserved: %s", prep.UpstreamPath)
	}
	if prep.RequestedModel != "claude-x" || !prep.Stream {
		t.Fatalf("model/stream wrong: %+v", prep)
	}
	var parsed struct {
		System []map[string]string `json:"system"`
	}
	_ = json.Unmarshal(prep.Body, &parsed)
	if len(parsed.System) == 0 || parsed.System[0]["text"] != ccident.SystemPrompt {
		t.Fatalf("prompt not injected: %s", prep.Body)
	}
}

func TestHandleLocalModels(t *testing.T) {
	tr := New()
	rr := httptest.NewRecorder()
	if !tr.HandleLocal(rr, "GET", "/v1/models") {
		t.Fatalf("models not handled")
	}
	var resp struct {
		Data []struct {
			Type string `json:"type"`
			ID   string `json:"id"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("bad json: %v", err)
	}
	if len(resp.Data) == 0 || resp.Data[0].Type != "model" {
		t.Fatalf("model list wrong: %s", rr.Body.String())
	}
}

func TestPassthroughStreamScansUsage(t *testing.T) {
	tr := New()
	var buf bytes.Buffer
	sw := tr.NewStreamWriter(&buf, routing.Prepared{})
	stream := "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-x\",\"usage\":{\"input_tokens\":8}}}\n\n" +
		"event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":4}}\n\n"
	sw.Feed([]byte(stream))
	sw.Finish()
	// Passthrough: the client bytes must equal the upstream bytes verbatim.
	if buf.String() != stream {
		t.Fatalf("passthrough altered the stream:\n%q", buf.String())
	}
	u := sw.Usage()
	if u.Input != 8 || u.Output != 4 || u.Model != "claude-x" || u.Status != 200 {
		t.Fatalf("usage wrong: %+v", u)
	}
}

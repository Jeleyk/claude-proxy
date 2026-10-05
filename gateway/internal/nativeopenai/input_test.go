package nativeopenai

import (
	"context"
	"encoding/json"
	"io"
	"reflect"
	"testing"

	"claudeproxy/gateway/internal/config"
)

func TestCodexStringInputUsesTypedMessageContent(t *testing.T) {
	for _, kind := range []string{"OAUTH", "OAUTH_STATIC", "API_KEY"} {
		t.Run(kind, func(t *testing.T) {
			h := NewHandler(&config.Config{}, nil)
			req, err := h.request(context.Background(), account(1, kind), prepared{body: map[string]json.RawMessage{"model": json.RawMessage(`"gpt-test"`), "input": json.RawMessage(`"hello"`)}})
			if err != nil {
				t.Fatal(err)
			}
			data, err := io.ReadAll(req.Body)
			if err != nil {
				t.Fatal(err)
			}
			var body map[string]json.RawMessage
			if err = json.Unmarshal(data, &body); err != nil {
				t.Fatal(err)
			}
			want := `[{"type":"message","role":"user","content":[{"type":"input_text","text":"hello"}]}]`
			if kind == "API_KEY" {
				want = `"hello"`
			}
			assertJSONEqual(t, body["input"], []byte(want))
		})
	}
}

func TestCodexEasyArrayMessagesKeepToolsAndTypedInput(t *testing.T) {
	raw := json.RawMessage(`[
  {"role":"user","content":"question"},
  {"type":"message","role":"assistant","content":"answer","id":"msg_history"},
  {"role":"developer","content":"instruction"},
  {"role":"user","content":[{"type":"input_image","image_url":"data:image/png;base64,YQ=="}]},
  {"type":"reasoning","encrypted_content":"opaque"},
  {"type":"function_call_output","call_id":"call1","output":"tool result"}
 ]`)
	want := `[
  {"type":"message","role":"user","content":[{"type":"input_text","text":"question"}]},
  {"type":"message","role":"assistant","content":[{"type":"output_text","text":"answer"}],"id":"msg_history"},
  {"type":"message","role":"developer","content":[{"type":"input_text","text":"instruction"}]},
  {"role":"user","content":[{"type":"input_image","image_url":"data:image/png;base64,YQ=="}]},
  {"type":"reasoning","encrypted_content":"opaque"},
  {"type":"function_call_output","call_id":"call1","output":"tool result"}
 ]`
	assertJSONEqual(t, codexInput(raw), []byte(want))
	h := NewHandler(&config.Config{}, nil)
	req, err := h.request(context.Background(), account(1, "API_KEY"), prepared{body: map[string]json.RawMessage{"input": raw}})
	if err != nil {
		t.Fatal(err)
	}
	var body map[string]json.RawMessage
	if err = json.NewDecoder(req.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	assertJSONEqual(t, body["input"], raw)
}

func assertJSONEqual(t *testing.T, got, want []byte) {
	t.Helper()
	var a, b any
	if err := json.Unmarshal(got, &a); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(want, &b); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(a, b) {
		t.Fatalf("got %s, want %s", got, want)
	}
}

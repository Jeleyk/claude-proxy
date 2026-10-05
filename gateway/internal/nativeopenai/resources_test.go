package nativeopenai

import (
	"encoding/json"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
)

func TestStoredAccountResourceReferencesAreRejectedBeforeUpstream(t *testing.T) {
	var called atomic.Int32
	f := setup(t, func(w http.ResponseWriter, r *http.Request) { called.Add(1) }, account(1, "API_KEY"))
	for _, body := range []string{
		`{"model":"gpt-test","input":[{"type":"item_reference","id":"msg_private"}]}`,
		`{"model":"gpt-test","input":[{"role":"user","content":[{"type":"input_file","file_id":"file_private"}]}]}`,
		`{"model":"gpt-test","input":[{"type":"message","role":"user","content":[{"type":"input_image","file_id":"file_private"}]}]}`,
		`{"model":"gpt-test","input":[],"tools":[{"type":"file_search","vector_store_ids":["vs_private"]}]}`,
		`{"model":"gpt-test","input":[],"tools":[{"type":"file_search"}]}`,
		`{"model":"gpt-test","input":[],"tools":[{"type":"code_interpreter","container":"cntr_private"}]}`,
		`{"model":"gpt-test","input":[],"tools":[{"type":"code_interpreter","container":{"type":"auto","file_ids":["file_private"]}}]}`,
		`{"model":"gpt-test","input":[{"type":"code_interpreter_call","container_id":"cntr_private"}]}`,
		`{"model":"gpt-test","input":[],"prompt":{"id":"pmpt_private"}}`,
	} {
		rec := f.call("POST", "/v1/responses", body)
		if rec.Code != 400 || !strings.Contains(rec.Body.String(), "unsupported_parameter") || !strings.Contains(rec.Body.String(), "inline input") {
			t.Fatal(body, rec.Code, rec.Body.String())
		}
		if strings.Contains(rec.Body.String(), "_private") {
			t.Fatal("resource id leaked in error")
		}
	}
	if called.Load() != 0 {
		t.Fatal("stored resource reached shared upstream")
	}
}

func TestInlineInputAndToolSchemasRemainAllowed(t *testing.T) {
	for _, body := range []string{
		`{"input":"Literal words file_id, vector_store_ids and item_reference are safe"}`,
		`{"input":[{"role":"user","content":[{"type":"input_text","text":"{\"type\":\"item_reference\",\"id\":\"test\"}"},{"type":"input_image","image_url":"data:image/png;base64,YQ=="},{"type":"input_file","filename":"note.txt","file_data":"data:text/plain;base64,YQ=="}]}]}`,
		`{"input":[],"tools":[{"type":"function","name":"load_local_file","parameters":{"type":"object","properties":{"file_id":{"type":"string"},"vector_store_ids":{"type":"array"}},"required":["file_id"]}}]}`,
		`{"input":[],"tools":[{"type":"custom","name":"test","format":{"type":"grammar","syntax":"lark","definition":"start: item_reference file_id"}}]}`,
		`{"input":[{"type":"function_call","name":"test","arguments":"{\"file_id\":\"local-file\"}"},{"type":"function_call_output","call_id":"call1","output":"{\"container_id\":\"local-container\"}"}]}`,
		`{"input":[],"tools":[{"type":"code_interpreter","container":{"type":"auto"}}]}`,
	} {
		var parsed map[string]json.RawMessage
		if err := json.Unmarshal([]byte(body), &parsed); err != nil {
			t.Fatal(err)
		}
		if field := storedResourceReference(parsed); field != "" {
			t.Fatal("safe content falsely rejected", field, body)
		}
	}
}

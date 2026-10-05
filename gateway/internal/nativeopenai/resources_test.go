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
		`{"model":"gpt-test","input":"Read mounted skill files","tools":[{"type":"shell","environment":{"type":"container_auto","skills":[{"type":"skill_reference","skill_id":"skill_private"}]}}]}`,
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

func TestUnsupportedNativeToolDefinitionsAreRejected(t *testing.T) {
	for _, body := range []string{
		`{"tools":[{"type":"unknown_hosted_tool"}]}`,
		`{"tools":[{"type":"mcp","connector_id":"connector_googlecalendar"}]}`,
		`{"tools":[{"type":"shell","environment":{"type":"container_auto"}}]}`,
		`{"tools":[{"type":"shell","environment":{"type":"container_reference","container_id":"cntr_private"}}]}`,
		`{"tools":[{"type":"shell"}]}`,
		`{"tools":[{"type":"tool_search"}]}`,
		`{"tools":[{"type":"namespace","name":"nested","tools":[{"type":"unknown_hosted_tool"}]}]}`,
		`{"tools":[{"type":"namespace","name":"nested","tools":[{"type":"namespace","name":"deep","tools":[{"type":"shell","environment":{"type":"container_auto"}}]}]}]}`,
		`{"input":[{"type":"additional_tools","role":"developer","tools":[{"type":"mcp","server_url":"https://example.invalid"}]}]}`,
		`{"input":[{"type":"tool_search_output","call_id":"call_1","tools":[{"type":"shell","environment":{"type":"container_auto"}}]}]}`,
		`{"input":[{"type":"additional_tools","role":"developer","tools":[{"type":"namespace","name":"nested","tools":[{"type":"skill_reference","skill_id":"skill_private"}]}]}]}`,
		`{"input":[{"type":"skill_reference","skill_id":"skill_private"}]}`,
		`{"input":[{"type":"message","content":[{"type":"input_text","text":"hello","skill_id":"skill_private"}]}]}`,
		`{"tools":[{"type":"code_interpreter","container":{"type":"auto","skill_ids":["skill_private"]}}]}`,
		`{"input":[{"type":"function_call_output","call_id":"call_1","output":[{"type":"input_file","file_id":"file_private"}]}]}`,
		`{"tools":{"type":"function","name":"malformed"}}`,
		`{"tools":[{}]}`,
		`{"tools":["function"]}`,
	} {
		t.Run(body, func(t *testing.T) {
			var parsed map[string]json.RawMessage
			if err := json.Unmarshal([]byte(body), &parsed); err != nil {
				t.Fatal(err)
			}
			if field := storedResourceReference(parsed); field == "" {
				t.Fatal("unsupported tool or stored resource was accepted")
			} else if strings.Contains(field, "_private") {
				t.Fatal("resource ID included in error", field)
			}
		})
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
		`{"input":[],"tools":[{"type":"function","name":"inspect","parameters":{"type":"object","properties":{"tools":{"type":"array","items":{"type":"object","properties":{"skill_id":{"type":"string"}}}}},"examples":[{"type":"skill_reference","skill_id":"local_value"}]}}]}`,
		`{"input":[],"tools":[{"type":"custom","name":"patch","format":{"type":"grammar","syntax":"lark","definition":"start: skill_reference skill_id"}}]}`,
		`{"input":[],"tools":[{"type":"local_shell"},{"type":"shell","environment":{"type":"local"}},{"type":"apply_patch"},{"type":"computer"},{"type":"computer_use_preview"},{"type":"web_search"},{"type":"web_search_preview"}]}`,
		`{"input":[],"tools":[{"type":"namespace","name":"tools","tools":[{"type":"function","name":"test","parameters":{"type":"object"}},{"type":"namespace","name":"nested","tools":[{"type":"custom","name":"patch"}]}]}]}`,
		`{"input":[{"type":"additional_tools","role":"developer","tools":[{"type":"function","name":"test","parameters":{"type":"object"}}]},{"type":"tool_search_output","call_id":"call_1","tools":[{"type":"custom","name":"patch"}]}]}`,
		`{"input":[{"type":"function_call_output","call_id":"call_1","output":{"file_id":"local-data","type":"skill_reference","tools":[{"type":"application_data"}]}}]}`,
		`{"input":[{"type":"custom_tool_call_output","call_id":"call_1","output":{"skill_ids":["local-data"]}}]}`,
		`{"input":[{"type":"function_call_output","call_id":"call_1","output":[{"type":"input_text","text":"skill_id is data"},{"type":"input_image","image_url":"data:image/png;base64,YQ=="}]}]}`,
		`{"input":[{"type":"message","role":"user","content":"hello","metadata":{"skill_id":"user-data","tools":[{"type":"user-data"}]}}]}`,
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

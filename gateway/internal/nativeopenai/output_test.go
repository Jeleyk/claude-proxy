package nativeopenai

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"

	"claudeproxy/gateway/internal/control"
)

func TestNonStreamingSparseTerminalPreservesOrderedOutput(t *testing.T) {
	for _, output := range []string{"", `,"output":[]`, `,"output":null`} {
		t.Run(fmt.Sprintf("output=%s", output), func(t *testing.T) {
			f := setup(t, func(w http.ResponseWriter, r *http.Request) {
				sse(w, `event: response.output_item.done
data: {"type":"response.output_item.done","output_index":2,"item":{"id":"fc_1","type":"function_call","name":"test_tool","call_id":"call_1","arguments":"{\"value\":1}"}}

event: response.output_item.done
data: {"type":"response.output_item.done","output_index":0,"item":{"id":"rs_1","type":"reasoning","summary":[{"type":"summary_text","text":"reasoning summary"}],"encrypted_content":"encrypted-reasoning"}}

event: response.output_item.done
data: {"type":"response.output_item.done","output_index":1,"item":{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello World"}]}}

event: response.completed
data: {"type":"response.completed","response":{"id":"resp_sparse","usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":60},"output_tokens":20}`+output+`}}

`)
			}, account(1, "OAUTH"))
			rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[],"stream":false}`)
			if rec.Code != 200 {
				t.Fatal(rec.Code, rec.Body.String())
			}
			var response struct {
				ID     string                       `json:"id"`
				Output []map[string]json.RawMessage `json:"output"`
			}
			if err := json.Unmarshal(rec.Body.Bytes(), &response); err != nil {
				t.Fatal(err)
			}
			if response.ID != "resp_sparse" || len(response.Output) != 3 {
				t.Fatal(rec.Body.String())
			}
			if string(response.Output[0]["type"]) != `"reasoning"` || string(response.Output[0]["encrypted_content"]) != `"encrypted-reasoning"` || !strings.Contains(string(response.Output[0]["summary"]), "reasoning summary") {
				t.Fatal("reasoning lost", rec.Body.String())
			}
			if string(response.Output[1]["type"]) != `"message"` || !strings.Contains(string(response.Output[1]["content"]), "Hello World") {
				t.Fatal("text lost", rec.Body.String())
			}
			if string(response.Output[2]["type"]) != `"function_call"` || string(response.Output[2]["name"]) != `"test_tool"` || string(response.Output[2]["call_id"]) != `"call_1"` || string(response.Output[2]["arguments"]) != `"{\"value\":1}"` {
				t.Fatal("tool lost", rec.Body.String())
			}
			report := f.report(t)
			if report.Input != 40 || report.CacheRead != 60 || report.Output != 20 {
				t.Fatal("usage changed", report)
			}
		})
	}
}

func TestSparseTerminalWithoutIndexesPreservesArrival(t *testing.T) {
	var output outputCollector
	if err := output.add(nil, json.RawMessage(`{"type":"message","content":[{"type":"output_text","text":"Hello"}]}`)); err != nil {
		t.Fatal(err)
	}
	if err := output.add(nil, json.RawMessage(`{"type":"message","content":[{"type":"output_text","text":"World"}]}`)); err != nil {
		t.Fatal(err)
	}
	data, err := output.complete(json.RawMessage(`{"id":"resp1"}`))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Index(string(data), "Hello") >= strings.Index(string(data), "World") {
		t.Fatal(string(data))
	}
}

func TestTerminalOutputRemainsAuthoritative(t *testing.T) {
	var output outputCollector
	if err := output.add(nil, json.RawMessage(`{"type":"message","content":[]}`)); err != nil {
		t.Fatal(err)
	}
	terminal := json.RawMessage(`{"id":"response","output":[{"id":"authoritative","type":"message","content":[]}]}`)
	data, err := output.complete(terminal)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != string(terminal) {
		t.Fatal("terminal output duplicated/replaced", string(data))
	}
}

func TestOutputAccumulatorBoundsBytesItemsAndReplacement(t *testing.T) {
	item := json.RawMessage(`{"type":"reasoning","encrypted_content":"` + strings.Repeat("x", 1<<20) + `"}`)
	var output outputCollector
	for i := 0; i < 31; i++ {
		if err := output.add(nil, item); err != nil {
			t.Fatal(err)
		}
	}
	if err := output.add(nil, item); err == nil {
		t.Fatal("aggregate output exceeded 32MiB")
	}
	remaining := maxResponse - output.size
	largeMetadata := json.RawMessage(`{"id":"response","metadata":"` + strings.Repeat("y", remaining) + `"}`)
	if _, err := output.complete(largeMetadata); err == nil {
		t.Fatal("terminal metadata bypassed total 32MiB cap")
	}
	var many outputCollector
	for i := 0; i < maxOutputItems; i++ {
		if err := many.add(nil, json.RawMessage(`{}`)); err != nil {
			t.Fatal(err)
		}
	}
	if err := many.add(nil, json.RawMessage(`{}`)); err == nil {
		t.Fatal("unbounded output item metadata")
	}
	var replaced outputCollector
	idx := 42
	first := json.RawMessage(`{"type":"message","content":[],"id":"old"}`)
	latest := json.RawMessage(`{"type":"message","content":[],"id":"new"}`)
	if err := replaced.add(&idx, first); err != nil {
		t.Fatal(err)
	}
	if err := replaced.add(&idx, latest); err != nil {
		t.Fatal(err)
	}
	data, err := replaced.complete(json.RawMessage(`{"id":"response"}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(replaced.items) != 1 || replaced.size != len(latest) || strings.Contains(string(data), "old") {
		t.Fatal("duplicate index grew output", string(data))
	}
}

func TestNamedRateLimitEventsDoNotOverwriteDefaultQuota(t *testing.T) {
	for _, name := range []string{`"metered_limit_name":"codex_other"`, `"limit_name":"codex-spark"`, `"metered_limit_name":"codex_other","limit_name":"codex"`, `"metered_limit_name":""`} {
		report := control.UsageReport{RatelimitHeaders: map[string]string{"x-codex-primary-used-percent": "23", "x-codex-primary-window-minutes": "300", "x-codex-primary-reset-at": "1999999999"}}
		scanRateEvent([]byte(`{"type":"codex.rate_limits",`+name+`,"rate_limits":{"primary":{"used_percent":100,"window_minutes":60,"reset_at":2000000000},"secondary":{"used_percent":100}}}`), &report)
		if report.RatelimitHeaders["x-codex-primary-used-percent"] != "23" || report.RatelimitHeaders["x-codex-primary-window-minutes"] != "300" || report.RatelimitHeaders["x-codex-primary-reset-at"] != "1999999999" || len(report.RatelimitHeaders) != 3 {
			t.Fatal("named quota flattened", name, report)
		}
	}
	for _, name := range []string{"", `,"limit_name":"codex"`, `,"metered_limit_name":" CODEX "`, `,"metered_limit_name":"codex","limit_name":"codex_other"`} {
		report := control.UsageReport{}
		scanRateEvent([]byte(`{"type":"codex.rate_limits"`+name+`,"rate_limits":{"primary":{"used_percent":30,"window_minutes":300,"reset_at":2000000000}}}`), &report)
		if report.RatelimitHeaders["x-codex-primary-used-percent"] != "30" {
			t.Fatal("default quota not parsed", name, report)
		}
	}
}

func TestNamedRateLimitStillReachesStreamingClient(t *testing.T) {
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Codex-Primary-Used-Percent", "12")
		sse(w, "event: codex.rate_limits\ndata: {\"type\":\"codex.rate_limits\",\"metered_limit_name\":\"codex_other\",\"rate_limits\":{\"primary\":{\"used_percent\":100}}}\n\n"+completed)
	}, account(1, "OAUTH"))
	rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[],"stream":true}`)
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), "codex_other") {
		t.Fatal(rec.Code, rec.Body.String())
	}
	if f.report(t).RatelimitHeaders["x-codex-primary-used-percent"] != "12" {
		t.Fatal("named SSE quota overwrote default header")
	}
}

package proxy

import (
	"io"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"claudeproxy/gateway/internal/control"
)

func TestMcpScanCountsOnlyMCPToolUse(t *testing.T) {
	var s mcpScan
	s.Feed([]byte("event: content_block_start\n" +
		`data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"mcp__github__get_issue","input":{}}}` + "\n\n"))
	s.Feed([]byte("event: content_block_start\n" +
		`data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tu_2","name":"Bash","input":{}}}` + "\n\n"))
	s.Feed([]byte("event: content_block_start\n" +
		`data: {"type":"content_block_start","index":3,"content_block":{"type":"tool_use","id":"tu_3","name":"mcp__github__get_issue","input":{}}}` + "\n\n"))

	if got := s.calls["mcp__github__get_issue"]; got != 2 {
		t.Errorf("mcp__github__get_issue = %d, want 2", got)
	}
	if _, ok := s.calls["Bash"]; ok {
		t.Errorf("non-MCP tool_use must not be counted: %v", s.calls)
	}
}

func TestMcpScanHandlesChunkBoundaryInsideEvent(t *testing.T) {
	var s mcpScan
	whole := "event: content_block_start\n" +
		`data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"mcp__ctx7__query-docs","input":{}}}` + "\n\n"
	for i := 0; i < len(whole); i += 7 { // feed in tiny slices, splitting the event arbitrarily
		end := min(i+7, len(whole))
		s.Feed([]byte(whole[i:end]))
	}
	if got := s.calls["mcp__ctx7__query-docs"]; got != 1 {
		t.Errorf("calls = %v, want one mcp__ctx7__query-docs", s.calls)
	}
}

func TestMcpScanIgnoresTextMentioningToolUse(t *testing.T) {
	var s mcpScan
	// A text delta whose *content* mentions tool_use/mcp__ must not count (the regex approach
	// would have false-positived here).
	s.Feed([]byte("event: content_block_delta\n" +
		`data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"use {\"type\":\"tool_use\",\"name\":\"mcp__fake__tool\"} blocks"}}` + "\n\n"))
	if len(s.calls) != 0 {
		t.Errorf("text delta must not produce calls: %v", s.calls)
	}
}

func TestRelaySSEReturnsMcpCalls(t *testing.T) {
	data := "event: message_start\n" +
		`data: {"type":"message_start","message":{"usage":{"input_tokens":10}}}` + "\n\n" +
		"event: content_block_start\n" +
		`data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu_1","name":"mcp__memory__search","input":{}}}` + "\n\n"
	rec := httptest.NewRecorder()
	_, calls, _ := relaySSEInterval(rec, io.NopCloser(strings.NewReader(data)), 200, "text/event-stream",
		func() (string, int) { return "", 0 }, time.Hour)

	if calls["mcp__memory__search"] != 1 {
		t.Errorf("calls = %v, want mcp__memory__search=1", calls)
	}
	if !strings.Contains(rec.Body.String(), "content_block_start") {
		t.Errorf("event bytes not relayed: %q", rec.Body.String())
	}
}

func TestFillUsageFromJSONCountsMcpCalls(t *testing.T) {
	body := `{"model":"claude-sonnet-5","content":[` +
		`{"type":"text","text":"hi"},` +
		`{"type":"tool_use","id":"tu_1","name":"mcp__github__get_issue","input":{}},` +
		`{"type":"tool_use","id":"tu_2","name":"Edit","input":{}}` +
		`],"usage":{"input_tokens":5,"output_tokens":7}}`
	var report control.UsageReport
	fillUsageFromJSON(&report, []byte(body))

	if report.McpCalls["mcp__github__get_issue"] != 1 || len(report.McpCalls) != 1 {
		t.Errorf("McpCalls = %v, want only mcp__github__get_issue=1", report.McpCalls)
	}
	if report.Input != 5 || report.Output != 7 {
		t.Errorf("usage = %d/%d, want 5/7", report.Input, report.Output)
	}
}

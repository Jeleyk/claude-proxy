package proxy

import (
	"encoding/json"
	"strings"

	"claudeproxy/gateway/internal/anthropic"
)

// mcpScan incrementally counts MCP tool invocations in a streamed Anthropic response. Claude
// Code exposes MCP tools to the model under names like "mcp__server__tool"; every invocation
// surfaces as a tool_use content block, announced by a content_block_start event. Counting is
// best-effort: it rides the structured SSE parser next to the regex usage scan and never
// affects the relay — unparseable input just leaves the map empty.
type mcpScan struct {
	parser anthropic.SSEParser
	calls  map[string]int64
}

// Feed scans another chunk of the stream, counting MCP tool_use block starts.
func (s *mcpScan) Feed(b []byte) {
	for _, ev := range s.parser.Feed(b) {
		if ev.Type != "content_block_start" {
			continue
		}
		var p struct {
			ContentBlock struct {
				Type string `json:"type"`
				Name string `json:"name"`
			} `json:"content_block"`
		}
		if json.Unmarshal(ev.Data, &p) != nil {
			continue
		}
		s.count(p.ContentBlock.Type, p.ContentBlock.Name)
	}
}

func (s *mcpScan) count(blockType, name string) {
	if blockType != "tool_use" || !strings.HasPrefix(name, "mcp__") {
		return
	}
	if s.calls == nil {
		s.calls = map[string]int64{}
	}
	s.calls[name]++
}

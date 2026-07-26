package proxy

import (
	"encoding/json"
	"strings"

	"claudeproxy/gateway/internal/anthropic"
)

// mcpScan counts MCP tool invocations in an Anthropic response. Two shapes exist and both are
// counted under the same name:
//
//   - client-side MCP (what Claude Code does today): the model calls an ordinary `tool_use`
//     block whose name the client namespaced as "mcp__server__tool";
//   - server-side MCP (the `mcp_servers` connector Claude Code 2.1 can enable): Anthropic runs
//     the server itself and emits an `mcp_tool_use` block carrying the bare tool name plus
//     `server_name`.
//
// Counting is best-effort: it rides the shared SSE parse and never affects the relay —
// unparseable input just leaves the map empty.
type mcpScan struct {
	calls map[string]int64
}

// Consume counts one parsed SSE event; only content_block_start announces a tool invocation.
func (s *mcpScan) Consume(ev anthropic.Event) {
	if ev.Type != "content_block_start" {
		return
	}
	var p struct {
		ContentBlock contentBlockHead `json:"content_block"`
	}
	if json.Unmarshal(ev.Data, &p) != nil {
		return
	}
	s.count(p.ContentBlock)
}

// contentBlockHead is the identifying head of a content block, shared by the streamed
// (content_block_start) and buffered (response `content[]`) paths.
type contentBlockHead struct {
	Type       string `json:"type"`
	Name       string `json:"name"`
	ServerName string `json:"server_name"`
}

func (s *mcpScan) count(b contentBlockHead) {
	key := mcpToolKey(b)
	if key == "" {
		return
	}
	if s.calls == nil {
		s.calls = map[string]int64{}
	}
	s.calls[key]++
}

// mcpToolKey returns the stats key for a content block, or "" when it is not an MCP call.
// Server-side calls are normalized into the same "mcp__server__tool" shape the client-side ones
// already use, so both aggregate into one row per tool instead of splitting the history.
func mcpToolKey(b contentBlockHead) string {
	switch b.Type {
	case "tool_use":
		if strings.HasPrefix(b.Name, "mcp__") {
			return b.Name
		}
	case "mcp_tool_use":
		switch {
		case b.Name == "":
			return ""
		case strings.HasPrefix(b.Name, "mcp__"):
			return b.Name
		case b.ServerName == "":
			return "mcp__" + b.Name
		default:
			return "mcp__" + b.ServerName + "__" + b.Name
		}
	}
	return ""
}

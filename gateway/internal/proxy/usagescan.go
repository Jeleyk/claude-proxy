package proxy

import (
	"claudeproxy/gateway/internal/anthropic"
)

// streamScan folds a streamed Anthropic response into everything the usage report needs: token
// counts (including the per-TTL cache-write split, server-tool calls and the fast-mode flag),
// the model that actually answered, and MCP tool-call counts.
//
// It drives ONE shared SSE parse. An earlier version scraped the raw bytes with regexes, which
// could only see four flat integers and picked the max match anywhere in the stream — wrong the
// moment a response carries per-attempt `usage.iterations` (server-side refusal fallback) and
// blind to `cache_creation.ephemeral_1h_input_tokens`, `server_tool_use` and `speed`. Parsing
// events structurally is both cheaper and the only way to price the response correctly.
type streamScan struct {
	parser anthropic.SSEParser
	usage  anthropic.Usage
	mcp    mcpScan
	blocks blockScan
}

// Feed scans another chunk of the stream. Chunk boundaries are handled by the parser, which
// buffers a partial trailing event until it completes.
func (s *streamScan) Feed(b []byte) {
	for _, ev := range s.parser.Feed(b) {
		s.usage.Consume(ev)
		s.mcp.Consume(ev)
		s.blocks.Consume(ev)
	}
}

// InToolInput reports whether a tool-input content block is open, i.e. whether upstream silence is
// the expected kind (Anthropic buffering a parameter value) rather than an abandoned stream.
func (s *streamScan) InToolInput() bool { return s.blocks.openToolInput }

// Usage returns the scanned usage, normalized so the service can price it without re-checking.
func (s *streamScan) Usage() anthropic.Usage {
	u := s.usage
	u.Normalize()
	return u
}

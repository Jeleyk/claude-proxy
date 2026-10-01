package proxy

import (
	"encoding/json"

	"claudeproxy/gateway/internal/anthropic"
)

// blockScan tracks whether the stream is currently inside an open tool-input content block.
//
// This is the one silence Anthropic produces on purpose. Unless a tool opts into eager input
// streaming, the API buffers and validates a tool parameter's *whole value* before sending it, and
// current models emit one complete key-value pair at a time — so a `Write` whose `content` is a
// 40 KB document opens its `content_block_start`, then sends nothing but pings for as long as it
// takes to generate that document. Measured on real sessions: 178s for a 42 KB plan, 191s for a
// 46 KB spec, 397s for a 109 KB file. The answer is alive the whole time.
//
// The stall watchdog cannot tell that silence apart from an abandoned stream by timing alone, so it
// asks here instead and grants a much larger budget while a tool input is being buffered.
type blockScan struct{ openToolInput bool }

// toolInputBlocks are the content-block types whose input arrives as buffered `input_json_delta`.
var toolInputBlocks = map[string]bool{"tool_use": true, "server_tool_use": true, "mcp_tool_use": true}

// Consume folds one event into the tracker.
func (b *blockScan) Consume(ev anthropic.Event) {
	switch ev.Type {
	case "content_block_start":
		var p struct {
			ContentBlock struct {
				Type string `json:"type"`
			} `json:"content_block"`
		}
		if json.Unmarshal(ev.Data, &p) == nil {
			b.openToolInput = toolInputBlocks[p.ContentBlock.Type]
		}
	case "content_block_stop", "message_delta", "message_stop":
		b.openToolInput = false
	}
}

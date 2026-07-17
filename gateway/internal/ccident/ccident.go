// Package ccident injects the Claude Code identity into an Anthropic Messages request so that
// OAuth subscription accounts accept traffic that did not originate from Claude Code. The
// load-bearing part is the system prompt: subscription tokens 400/401 unless the FIRST system
// block is exactly the Claude Code prompt (calibrated against live traffic — see CLAUDE.md).
package ccident

import "encoding/json"

// SystemPrompt is the exact first system block Anthropic requires for OAuth subscription auth.
const SystemPrompt = "You are Claude Code, Anthropic's official CLI for Claude."

type textBlock struct {
	Type string `json:"type"`
	Text string `json:"text"`
}

func ccBlock() json.RawMessage {
	b, _ := json.Marshal(textBlock{Type: "text", Text: SystemPrompt})
	return b
}

func textBlockRaw(text string) json.RawMessage {
	b, _ := json.Marshal(textBlock{Type: "text", Text: text})
	return b
}

// firstIsCC reports whether a system block is already the Claude Code prompt.
func firstIsCC(raw json.RawMessage) bool {
	var tb textBlock
	if err := json.Unmarshal(raw, &tb); err == nil {
		return tb.Text == SystemPrompt
	}
	return false
}

// normalizeBlock turns a bare JSON string system element into a text block; other shapes
// (already objects) pass through unchanged.
func normalizeBlock(raw json.RawMessage) json.RawMessage {
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		return textBlockRaw(s)
	}
	return raw
}

// InjectSystemPrompt rewrites the Anthropic Messages request `system` field into an array whose
// FIRST block is exactly [SystemPrompt], preserving any client-supplied system content as
// subsequent blocks. If the first block is already the Claude Code prompt it is left untouched.
// Malformed JSON is returned unchanged (the upstream returns a clear error).
func InjectSystemPrompt(body []byte) []byte {
	var obj map[string]json.RawMessage
	if err := json.Unmarshal(body, &obj); err != nil {
		return body
	}
	blocks := []json.RawMessage{ccBlock()}
	if raw, ok := obj["system"]; ok {
		var s string
		if err := json.Unmarshal(raw, &s); err == nil {
			// system was a plain string.
			if s != "" && s != SystemPrompt {
				blocks = append(blocks, textBlockRaw(s))
			}
		} else {
			var arr []json.RawMessage
			if err := json.Unmarshal(raw, &arr); err == nil {
				if len(arr) > 0 && firstIsCC(arr[0]) {
					return body // already injected — leave as-is
				}
				for _, el := range arr {
					blocks = append(blocks, normalizeBlock(el))
				}
			}
			// any other shape: ignore it and use just the Claude Code block.
		}
	}
	nb, err := json.Marshal(blocks)
	if err != nil {
		return body
	}
	obj["system"] = nb
	out, err := json.Marshal(obj)
	if err != nil {
		return body
	}
	return out
}

// SystemBlocks returns the Anthropic `system` array (Claude Code prompt first, then the given
// client system text if non-empty). Used by translators that build the Anthropic body from
// scratch (OpenAI) rather than editing existing JSON.
func SystemBlocks(clientSystem string) []json.RawMessage {
	blocks := []json.RawMessage{ccBlock()}
	if clientSystem != "" {
		blocks = append(blocks, textBlockRaw(clientSystem))
	}
	return blocks
}

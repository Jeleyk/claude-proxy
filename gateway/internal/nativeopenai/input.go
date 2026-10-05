package nativeopenai

import "encoding/json"

// The general Responses API accepts easy-message string shorthand. Codex's input wire uses
// typed message content arrays, so normalize only that shorthand; tool/reasoning items remain
// opaque and API-key requests never pass through this conversion.
func codexInput(raw json.RawMessage) json.RawMessage {
	var text string
	if json.Unmarshal(raw, &text) == nil {
		out, _ := json.Marshal([]map[string]any{{"type": "message", "role": "user", "content": []map[string]string{{"type": "input_text", "text": text}}}})
		return out
	}
	var items []json.RawMessage
	if json.Unmarshal(raw, &items) != nil {
		return raw
	}
	changed := false
	for i, item := range items {
		var message map[string]json.RawMessage
		if json.Unmarshal(item, &message) != nil || message == nil {
			continue
		}
		var role, kind, content string
		_ = json.Unmarshal(message["role"], &role)
		_ = json.Unmarshal(message["type"], &kind)
		if (kind != "" && kind != "message") || (role != "user" && role != "assistant" && role != "system" && role != "developer") {
			continue
		}
		if rawContent := message["content"]; len(rawContent) == 0 || rawContent[0] != '"' || json.Unmarshal(rawContent, &content) != nil {
			continue
		}
		contentType := "input_text"
		if role == "assistant" {
			contentType = "output_text"
		}
		message["type"] = json.RawMessage(`"message"`)
		message["content"], _ = json.Marshal([]map[string]string{{"type": contentType, "text": content}})
		items[i], _ = json.Marshal(message)
		changed = true
	}
	if !changed {
		return raw
	}
	out, _ := json.Marshal(items)
	return out
}

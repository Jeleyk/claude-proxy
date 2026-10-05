package nativeopenai

import "encoding/json"

// The current price table covers tokens, not OpenAI hosted tools or service tiers.
// Keep these requests usable for uncapped/personal accounts, but never claim their
// recorded token-only cost is complete. Capped shared accounts are denied by resolve.
func unmeteredRequest(body map[string]json.RawMessage) bool {
	var tier string
	if raw, ok := body["service_tier"]; ok && string(raw) != "null" {
		if json.Unmarshal(raw, &tier) != nil || (tier != "" && tier != "default") {
			return true
		}
	}
	var visit func(any) bool
	visit = func(value any) bool {
		switch v := value.(type) {
		case []any:
			for _, child := range v {
				if visit(child) {
					return true
				}
			}
		case map[string]any:
			kind, _ := v["type"].(string)
			for key, child := range v {
				if key == "parameters" || key == "input_schema" || key == "json_schema" || key == "metadata" {
					continue
				}
				if key == "output" && (kind == "function_call_output" || kind == "custom_tool_call_output") {
					if _, object := child.(map[string]any); object {
						continue
					}
				}
				if key == "tools" {
					tools, ok := child.([]any)
					if !ok {
						return true
					}
					for _, tool := range tools {
						definition, ok := tool.(map[string]any)
						if !ok {
							return true
						}
						switch definition["type"] {
						case "function", "custom", "local_shell", "apply_patch", "computer", "computer_use_preview", "namespace":
						case "shell":
							environment, _ := definition["environment"].(map[string]any)
							if environment["type"] != "local" {
								return true
							}
						default:
							return true
						}
					}
				}
				if visit(child) {
					return true
				}
			}
		}
		return false
	}
	for _, field := range []string{"tools", "input"} {
		var value any
		if raw, ok := body[field]; ok && json.Unmarshal(raw, &value) == nil {
			if visit(map[string]any{field: value}) {
				return true
			}
		}
	}
	return false
}

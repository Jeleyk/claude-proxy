package nativeopenai

import "encoding/json"

// Inference keys do not grant ownership of all resources stored in a shared upstream account.
// Inspect protocol fields, never literal text or function/custom-tool JSON schemas.
func storedResourceReference(body map[string]json.RawMessage) string {
	var visit func(any) string
	visitTools := func(value any) string {
		if value == nil {
			return ""
		}
		tools, ok := value.([]any)
		if !ok {
			return "tools"
		}
		for _, tool := range tools {
			definition, ok := tool.(map[string]any)
			if !ok {
				return "tools"
			}
			kind, _ := definition["type"].(string)
			switch kind {
			case "function", "custom", "local_shell", "shell", "apply_patch", "computer", "computer_use_preview",
				"web_search", "web_search_preview", "code_interpreter", "namespace":
				// Namespace definitions and dynamically supplied tools are visited recursively.
				// Unknown/hosted tool kinds require an ownership and metering review first.
			default:
				return "tools.type"
			}
			if field := visit(definition); field != "" {
				return field
			}
		}
		return ""
	}
	visit = func(value any) string {
		switch v := value.(type) {
		case []any:
			for _, child := range v {
				if field := visit(child); field != "" {
					return field
				}
			}
		case map[string]any:
			kind, _ := v["type"].(string)
			if kind == "item_reference" || kind == "skill_reference" {
				return kind
			}
			if kind == "file_search" {
				return "file_search"
			}
			for _, field := range []string{"file_id", "file_ids", "vector_store_id", "vector_store_ids", "container_id", "skill_id", "skill_ids"} {
				if child, ok := v[field]; ok && hasReferenceValue(child) {
					return field
				}
			}
			if kind == "code_interpreter" {
				container, _ := v["container"].(map[string]any)
				if v["container"] != "auto" && container["type"] != "auto" {
					return "container"
				}
			}
			if kind == "shell" {
				environment, _ := v["environment"].(map[string]any)
				if environment["type"] != "local" {
					return "shell.environment"
				}
			}
			for key, child := range v {
				// These subtrees describe user tools/data rather than hosted OpenAI resources.
				if key == "metadata" || (kind == "function" && (key == "parameters" || key == "input_schema" || key == "json_schema")) {
					continue
				}
				if key == "output" && (kind == "function_call_output" || kind == "custom_tool_call_output") {
					// Structured tool-result objects are opaque application data. Arrays may be
					// Responses multimodal content and must still be checked for file references.
					if _, object := child.(map[string]any); object {
						continue
					}
				}
				if key == "tools" {
					if field := visitTools(child); field != "" {
						return field
					}
					continue
				}
				if field := visit(child); field != "" {
					return field
				}
			}
		}
		return ""
	}
	for _, field := range []string{"input", "tools"} {
		var value any
		if raw, ok := body[field]; ok && json.Unmarshal(raw, &value) == nil {
			inspect := visit
			if field == "tools" {
				inspect = visitTools
			}
			if reference := inspect(value); reference != "" {
				return field + "." + reference
			}
		}
	}
	var prompt map[string]any
	if json.Unmarshal(body["prompt"], &prompt) == nil && hasReferenceValue(prompt["id"]) {
		return "prompt.id"
	}
	return ""
}

func hasReferenceValue(value any) bool {
	switch v := value.(type) {
	case nil:
		return false
	case string:
		return v != ""
	case []any:
		return len(v) > 0
	default:
		return true
	}
}

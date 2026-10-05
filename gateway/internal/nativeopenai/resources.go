package nativeopenai

import "encoding/json"

// Inference keys do not grant ownership of all resources stored in a shared upstream account.
// Inspect protocol fields, never literal text or function/custom-tool JSON schemas.
func storedResourceReference(body map[string]json.RawMessage) string {
	var visit func(any) string
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
			if kind == "item_reference" {
				return "item_reference"
			}
			if kind == "file_search" {
				return "file_search"
			}
			for _, field := range []string{"file_id", "file_ids", "vector_store_id", "vector_store_ids", "container_id"} {
				if child, ok := v[field]; ok && hasReferenceValue(child) {
					return field
				}
			}
			if kind == "code_interpreter" {
				if container, ok := v["container"].(string); ok && container != "auto" && container != "" {
					return "container"
				}
			}
			for key, child := range v {
				// These subtrees describe user tools/data rather than hosted OpenAI resources.
				if key == "parameters" || key == "input_schema" || key == "json_schema" || key == "metadata" {
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
			if reference := visit(value); reference != "" {
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

package nativeopenai

import (
	"bytes"
	"encoding/json"
	"errors"
	"sort"
)

const maxOutputItems = 4096

type outputItem struct {
	index    *int
	sequence int
	data     json.RawMessage
}

// Codex may emit output only in output_item.done; its terminal response can contain just an
// id and usage. Keep done items only for buffered clients, independently for each attempt.
type outputCollector struct {
	items   []outputItem
	indexed map[int]int
	size    int
}

func (c *outputCollector) add(index *int, item json.RawMessage) error {
	raw := bytes.TrimSpace(item)
	if len(raw) == 0 || raw[0] != '{' || !json.Valid(raw) || (index != nil && *index < 0) {
		return errors.New("invalid output item")
	}
	if index != nil && c.indexed != nil {
		if pos, ok := c.indexed[*index]; ok {
			size := c.size - len(c.items[pos].data) + len(raw)
			if size > maxResponse {
				return errors.New("output exceeds byte limit")
			}
			c.size = size
			c.items[pos].data = raw
			return nil
		}
	}
	if len(c.items) >= maxOutputItems || c.size+len(raw) > maxResponse {
		return errors.New("output exceeds limit")
	}
	if index != nil {
		if c.indexed == nil {
			c.indexed = make(map[int]int)
		}
		c.indexed[*index] = len(c.items)
	}
	c.items = append(c.items, outputItem{index: index, sequence: len(c.items), data: raw})
	c.size += len(raw)
	return nil
}

func (c *outputCollector) complete(response json.RawMessage) ([]byte, error) {
	var terminal map[string]json.RawMessage
	if json.Unmarshal(response, &terminal) != nil || terminal == nil {
		return nil, errors.New("invalid terminal response")
	}
	var output []json.RawMessage
	if raw, ok := terminal["output"]; ok {
		if json.Unmarshal(raw, &output) != nil {
			return nil, errors.New("invalid terminal output")
		}
		if len(output) > 0 {
			return readBounded(bytes.NewReader(response), maxResponse)
		}
	}
	// Reserve array punctuation and the surrounding response before allocating its serialization.
	if c.size+len(response)+len(c.items)*2+16 > maxResponse {
		return nil, errors.New("response exceeds byte limit")
	}
	ordered := append([]outputItem(nil), c.items...)
	sort.SliceStable(ordered, func(i, j int) bool {
		// Official fixtures also omit output_index: in that case preserve arrival order. Using
		// sequence as its fallback retains all items even when indexed/unindexed frames are mixed.
		left, right := ordered[i].sequence, ordered[j].sequence
		if ordered[i].index != nil {
			left = *ordered[i].index
		}
		if ordered[j].index != nil {
			right = *ordered[j].index
		}
		return left < right
	})
	output = make([]json.RawMessage, 0, len(ordered))
	for _, item := range ordered {
		output = append(output, item.data)
	}
	terminal["output"], _ = json.Marshal(output)
	data, err := json.Marshal(terminal)
	if err != nil {
		return nil, err
	}
	if len(data) > maxResponse {
		return nil, errors.New("response exceeds byte limit")
	}
	return data, nil
}

package openaigw

import (
	"encoding/json"
	"io"

	"claudeproxy/gateway/internal/anthropic"
	"claudeproxy/gateway/internal/routing"
)

// NewStreamWriter builds a per-request Anthropic-SSE → OpenAI-chunk translator.
func (t *Translator) NewStreamWriter(w io.Writer, p routing.Prepared) routing.StreamWriter {
	st, _ := p.State.(*state)
	if st == nil {
		st = &state{id: "chatcmpl-" + randHex(12)}
	}
	return &streamWriter{
		w:                w,
		st:               st,
		usage:            anthropic.Usage{Status: 200},
		toolIndexByBlock: map[int]int{},
	}
}

// streamWriter translates the Anthropic Messages SSE stream into OpenAI chat.completion.chunk
// SSE frames, tracking tool-call block indices and scanning usage as it goes.
type streamWriter struct {
	w                io.Writer
	st               *state
	parser           anthropic.SSEParser
	usage            anthropic.Usage
	roleSent         bool
	toolIndexByBlock map[int]int
	nextToolIndex    int
	finishReason     string
	errored          bool
	done             bool
}

func (s *streamWriter) Feed(p []byte) {
	for _, ev := range s.parser.Feed(p) {
		s.usage.Consume(ev)
		s.handle(ev)
	}
}

func (s *streamWriter) handle(ev anthropic.Event) {
	switch ev.Type {
	case "message_start":
		s.emitRole()
	case "content_block_start":
		var p struct {
			Index        int `json:"index"`
			ContentBlock struct {
				Type string `json:"type"`
				ID   string `json:"id"`
				Name string `json:"name"`
			} `json:"content_block"`
		}
		if json.Unmarshal(ev.Data, &p) != nil {
			return
		}
		if p.ContentBlock.Type == "tool_use" {
			s.emitRole()
			idx := s.nextToolIndex
			s.nextToolIndex++
			s.toolIndexByBlock[p.Index] = idx
			s.emitDelta(map[string]any{
				"tool_calls": []map[string]any{{
					"index":    idx,
					"id":       p.ContentBlock.ID,
					"type":     "function",
					"function": map[string]any{"name": p.ContentBlock.Name, "arguments": ""},
				}},
			}, "")
		}
	case "content_block_delta":
		var p struct {
			Index int `json:"index"`
			Delta struct {
				Type        string `json:"type"`
				Text        string `json:"text"`
				PartialJSON string `json:"partial_json"`
			} `json:"delta"`
		}
		if json.Unmarshal(ev.Data, &p) != nil {
			return
		}
		switch p.Delta.Type {
		case "text_delta":
			if p.Delta.Text != "" {
				s.emitRole()
				s.emitDelta(map[string]any{"content": p.Delta.Text}, "")
			}
		case "input_json_delta":
			if p.Delta.PartialJSON == "" {
				return
			}
			idx, ok := s.toolIndexByBlock[p.Index]
			if !ok {
				return
			}
			s.emitDelta(map[string]any{
				"tool_calls": []map[string]any{{
					"index":    idx,
					"function": map[string]any{"arguments": p.Delta.PartialJSON},
				}},
			}, "")
		}
	case "message_delta":
		var p struct {
			Delta struct {
				StopReason string `json:"stop_reason"`
			} `json:"delta"`
		}
		if json.Unmarshal(ev.Data, &p) == nil && p.Delta.StopReason != "" {
			s.finishReason = finishReasonFor(p.Delta.StopReason)
		}
	case "error":
		s.emitError(ev.Data)
	}
}

func (s *streamWriter) emitRole() {
	if s.roleSent {
		return
	}
	s.roleSent = true
	s.emitDelta(map[string]any{"role": "assistant"}, "")
}

// emitDelta writes one chat.completion.chunk carrying a single choice delta.
func (s *streamWriter) emitDelta(delta map[string]any, finish string) {
	choice := map[string]any{"index": 0, "delta": delta}
	if finish != "" {
		choice["finish_reason"] = finish
	} else {
		choice["finish_reason"] = nil
	}
	s.writeChunk([]map[string]any{choice}, nil)
}

func (s *streamWriter) writeChunk(choices []map[string]any, usage map[string]any) {
	chunk := map[string]any{
		"id":      s.st.id,
		"object":  "chat.completion.chunk",
		"created": s.st.created,
		"model":   s.st.echoModel,
		"choices": choices,
	}
	if usage != nil {
		chunk["usage"] = usage
	}
	b, _ := json.Marshal(chunk)
	_, _ = io.WriteString(s.w, "data: ")
	_, _ = s.w.Write(b)
	_, _ = io.WriteString(s.w, "\n\n")
}

func (s *streamWriter) emitError(data json.RawMessage) {
	var ae struct {
		Error struct {
			Type    string `json:"type"`
			Message string `json:"message"`
		} `json:"error"`
	}
	msg, kind := "upstream error", "api_error"
	if json.Unmarshal(data, &ae) == nil && ae.Error.Message != "" {
		msg = ae.Error.Message
		if ae.Error.Type != "" {
			kind = ae.Error.Type
		}
	}
	b, _ := json.Marshal(map[string]any{"error": map[string]any{"message": msg, "type": kind, "code": nil}})
	_, _ = io.WriteString(s.w, "data: ")
	_, _ = s.w.Write(b)
	_, _ = io.WriteString(s.w, "\n\n")
	s.errored = true
}

func (s *streamWriter) Finish() {
	if s.done {
		return
	}
	s.done = true
	if !s.errored {
		s.emitRole()
		finish := s.finishReason
		if finish == "" {
			finish = "stop"
		}
		s.emitDelta(map[string]any{}, finish)
		if s.st.includeUsage {
			s.writeChunk([]map[string]any{}, map[string]any{
				"prompt_tokens":     s.usage.Input + s.usage.CacheRead + s.usage.CacheWrite,
				"completion_tokens": s.usage.Output,
				"total_tokens":      s.usage.Input + s.usage.CacheRead + s.usage.CacheWrite + s.usage.Output,
			})
		}
	}
	_, _ = io.WriteString(s.w, "data: [DONE]\n\n")
}

func (s *streamWriter) Usage() routing.Usage {
	return routing.Usage{
		Input:      s.usage.Input,
		Output:     s.usage.Output,
		CacheRead:  s.usage.CacheRead,
		CacheWrite: s.usage.CacheWrite,
		Model:      s.usage.Model,
		Status:     s.usage.Status,
	}
}

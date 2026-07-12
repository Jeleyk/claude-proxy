package proxy

import (
	"regexp"
	"strconv"
)

// usageScan incrementally scans a streamed (SSE) Anthropic response for token usage, mirroring
// the Kotlin SseUsageScanner. Anthropic emits usage inside the stream: `message_start` carries
// `usage.input_tokens` (+ cache_* counters), and each `message_delta` carries a cumulative
// `usage.output_tokens`. We keep the max value seen per field. A small carry buffer bridges
// numbers split across chunk boundaries, so memory stays bounded regardless of length.
type usageScan struct {
	Input, Output, CacheRead, CacheWrite int64
	carry                                string
}

var (
	inputRe       = regexp.MustCompile(`"input_tokens"\s*:\s*(\d+)`)
	outputRe      = regexp.MustCompile(`"output_tokens"\s*:\s*(\d+)`)
	cacheReadRe   = regexp.MustCompile(`"cache_read_input_tokens"\s*:\s*(\d+)`)
	cacheCreateRe = regexp.MustCompile(`"cache_creation_input_tokens"\s*:\s*(\d+)`)
)

// Feed scans another chunk of the stream, updating the max-seen token counts.
func (s *usageScan) Feed(b []byte) {
	if len(b) == 0 {
		return
	}
	text := s.carry + string(b)
	s.Input = max64(s.Input, maxMatch(inputRe, text))
	s.Output = max64(s.Output, maxMatch(outputRe, text))
	s.CacheRead = max64(s.CacheRead, maxMatch(cacheReadRe, text))
	s.CacheWrite = max64(s.CacheWrite, maxMatch(cacheCreateRe, text))
	// Keep a tail large enough to hold any split key+number.
	if len(text) > 96 {
		s.carry = text[len(text)-96:]
	} else {
		s.carry = text
	}
}

func maxMatch(re *regexp.Regexp, text string) int64 {
	var m int64
	for _, g := range re.FindAllStringSubmatch(text, -1) {
		if v, err := strconv.ParseInt(g[1], 10, 64); err == nil && v > m {
			m = v
		}
	}
	return m
}

func max64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}

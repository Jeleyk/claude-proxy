package proxy

import (
	"fmt"
	"time"

	"claudeproxy/gateway/internal/control"
)

// midStreamError builds the client-facing SSE error frame when a retryable error surfaces
// mid-stream (after the 200 head is already out), a port of the Kotlin injectStreamError. When
// another candidate remains we send overloaded_error/529 (client retries now → lands on the next
// account); when this is the last candidate we send rate_limit_error/429 with a "retry in Ns"
// hint from the soonest reset across candidates. Account parking happens service-side, driven by
// the usage report the gateway sends with this recorded status.
func midStreamError(cands []control.Candidate, curIdx int) (frame string, recordStatus int) {
	var typ, msg string
	if curIdx < len(cands)-1 {
		typ = "overloaded_error"
		msg = "claude-proxy: account limit hit mid-stream, switching account — retry"
		recordStatus = 529
	} else {
		typ = "rate_limit_error"
		msg = "claude-proxy: all accounts rate-limited"
		if secs := soonestResetSeconds(cands, time.Now().Unix()); secs > 0 {
			msg += fmt.Sprintf("; retry in %ds", secs)
		}
		recordStatus = 429
	}
	frame = fmt.Sprintf("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"%s\",\"message\":\"%s\"}}\n\n", typ, msg)
	return frame, recordStatus
}

// soonestResetSeconds returns seconds until the earliest window reset across all candidates that
// is still in the future, or 0 if none is known. At least 1 when a future reset exists.
func soonestResetSeconds(cands []control.Candidate, nowEpoch int64) int64 {
	var soonest int64
	consider := func(epoch int64) {
		if epoch > nowEpoch && (soonest == 0 || epoch < soonest) {
			soonest = epoch
		}
	}
	for _, c := range cands {
		consider(c.FiveHourResetEpoch)
		consider(c.WeeklyResetEpoch)
	}
	if soonest == 0 {
		return 0
	}
	secs := soonest - nowEpoch
	if secs < 1 {
		secs = 1
	}
	return secs
}

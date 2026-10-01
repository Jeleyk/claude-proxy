package anthropic

import (
	"strings"
	"testing"
)

func TestSSEFramerReleasesOnlyCompleteEvents(t *testing.T) {
	var f SSEFramer

	if out := f.Feed([]byte("event: content_block_delta\ndata: {\"text\":\"hel")); len(out) != 0 {
		t.Errorf("released a half event: %q", out)
	}
	if f.Pending() == 0 {
		t.Error("Pending = 0 while half an event is buffered")
	}
	out := f.Feed([]byte("lo\"}\n\nevent: ping\ndata: {}\n\n"))
	want := "event: content_block_delta\ndata: {\"text\":\"hello\"}\n\nevent: ping\ndata: {}\n\n"
	if string(out) != want {
		t.Errorf("released %q, want %q", out, want)
	}
	if f.Pending() != 0 {
		t.Errorf("Pending = %d after a clean boundary, want 0", f.Pending())
	}
}

// A chunk that already ends on a boundary must pass straight through — the common case, and the
// one where an extra copy would cost the most.
func TestSSEFramerPassesAlignedChunksThrough(t *testing.T) {
	var f SSEFramer
	in := []byte("event: ping\ndata: {}\n\n")
	if out := f.Feed(in); string(out) != string(in) {
		t.Errorf("released %q, want %q", out, in)
	}
}

func TestSSEFramerKeepsTheTailAcrossManyChunks(t *testing.T) {
	var f SSEFramer
	var got strings.Builder
	for _, chunk := range []string{"eve", "nt: a\nda", "ta: 1\n", "\neven", "t: b\ndata: 2\n\n"} {
		got.Write(f.Feed([]byte(chunk)))
	}
	if want := "event: a\ndata: 1\n\nevent: b\ndata: 2\n\n"; got.String() != want {
		t.Errorf("relayed %q, want %q", got.String(), want)
	}
}

func TestSSEFramerHandlesCRLF(t *testing.T) {
	var f SSEFramer
	if out := f.Feed([]byte("event: a\r\ndata: 1\r\n\r\ndata: 2")); string(out) != "event: a\r\ndata: 1\r\n\r\n" {
		t.Errorf("released %q", out)
	}
	if f.Pending() != len("data: 2") {
		t.Errorf("Pending = %d, want %d", f.Pending(), len("data: 2"))
	}
}

func TestSSEFramerDiscardDropsThePendingFragment(t *testing.T) {
	var f SSEFramer
	f.Feed([]byte("event: x\ndata: {\"half\":"))
	f.Discard()
	if f.Pending() != 0 {
		t.Errorf("Pending = %d after Discard, want 0", f.Pending())
	}
	if out := f.Feed([]byte("event: y\ndata: {}\n\n")); string(out) != "event: y\ndata: {}\n\n" {
		t.Errorf("released %q after Discard", out)
	}
}

// Withholding bytes forever would be worse than the framing hazard it prevents: past the cap the
// framer gives up on finding a boundary and passes everything through.
func TestSSEFramerReleasesAnOversizedFragment(t *testing.T) {
	var f SSEFramer
	big := strings.Repeat("x", maxPendingFrame+1)
	if out := f.Feed([]byte(big)); len(out) != len(big) {
		t.Errorf("released %d bytes, want %d", len(out), len(big))
	}
	if f.Pending() != 0 {
		t.Errorf("Pending = %d, want 0", f.Pending())
	}
}

package nativeopenai

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"sync"
	"time"

	"claudeproxy/gateway/internal/control"
)

var (
	errAffinityUnknown     = errors.New("Encrypted conversation has no known account binding; start a new conversation with full plaintext input and a stable session ID")
	errAffinityUnavailable = errors.New("The account that owns this encrypted conversation is unavailable; wait for it to recover or start a new conversation with full plaintext input")
	errAffinityCapacity    = errors.New("Too many active OpenAI sessions; retry later")
)

// Affinity is deliberately local and bounded: only a token/session hash and upstream account
// ID survive between requests. Ciphertext is never retained or replayed to another account.
// Restart/idle expiry can lose a binding, in which case encrypted continuations fail closed.
type affinityStore struct {
	mu       sync.Mutex
	entries  map[[32]byte]*affinityEntry
	capacity int
	ttl      time.Duration
	now      func() time.Time
}

type affinityEntry struct {
	lock      chan struct{}
	users     int      // includes lock waiters; only a completely idle entry may expire/be evicted
	accountID int      // protected by the per-session lock
	identity  [32]byte // hash of stable upstream identity, never OAuth access tokens
	lastUsed  time.Time
}

type affinityLease struct {
	Waited bool // caller re-resolves authorization/credentials after waiting
	store  *affinityStore
	entry  *affinityEntry
	key    [32]byte
	once   sync.Once
}

func newAffinityStore() *affinityStore {
	return &affinityStore{entries: make(map[[32]byte]*affinityEntry), capacity: 4096,
		ttl: 24 * time.Hour, now: time.Now}
}

// Explicit proxy sessions override Codex's session-id; thread-id covers clients that send
// only that header. These values are for local affinity only and must never be forwarded.
func sessionID(r *http.Request) (string, error) {
	for _, name := range []string{"X-Proxy-Session-ID", "session-id", "thread-id"} {
		values := r.Header.Values(name)
		if len(values) == 0 {
			continue
		}
		if len(values) != 1 {
			return "", errors.New("Send exactly one session ID")
		}
		value := strings.TrimSpace(values[0])
		if len(value) == 0 || len(value) > 256 {
			return "", errors.New("Session ID must contain 1 to 256 visible ASCII characters")
		}
		for _, c := range value {
			if c < 0x21 || c > 0x7e {
				return "", errors.New("Session ID must contain 1 to 256 visible ASCII characters")
			}
		}
		return value, nil
	}
	return "", nil
}

func affinityKey(token, session string) [32]byte {
	h := sha256.New()
	var size [8]byte
	binary.BigEndian.PutUint64(size[:], uint64(len(token)))
	_, _ = h.Write(size[:])
	_, _ = h.Write([]byte(token))
	_, _ = h.Write([]byte(session))
	var key [32]byte
	copy(key[:], h.Sum(nil))
	return key
}

// acquire serializes the entire selection/forwarding/binding operation for one session.
// Context cancellation releases a waiter's reference without disturbing the active owner.
func (s *affinityStore) acquire(ctx context.Context, token, session string) (*affinityLease, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if session == "" {
		return &affinityLease{}, nil // plaintext SDK calls need not opt into session affinity
	}
	key := affinityKey(token, session)
	s.mu.Lock()
	now := s.now()
	for k, e := range s.entries {
		if e.users == 0 && !now.Before(e.lastUsed.Add(s.ttl)) {
			delete(s.entries, k)
		}
	}
	entry := s.entries[key]
	if entry == nil {
		if len(s.entries) >= s.capacity {
			var oldestKey [32]byte
			var oldest *affinityEntry
			for k, e := range s.entries {
				if e.users == 0 && (oldest == nil || e.lastUsed.Before(oldest.lastUsed)) {
					oldestKey, oldest = k, e
				}
			}
			if oldest == nil {
				s.mu.Unlock()
				return nil, errAffinityCapacity
			}
			delete(s.entries, oldestKey)
		}
		entry = &affinityEntry{lock: make(chan struct{}, 1), lastUsed: now}
		entry.lock <- struct{}{}
		s.entries[key] = entry
	}
	entry.users++
	s.mu.Unlock()

	waited := false
	select {
	case <-entry.lock:
	default:
		waited = true
		select {
		case <-ctx.Done():
			s.dropReference(key, entry)
			return nil, ctx.Err()
		case <-entry.lock:
		}
	}
	if err := ctx.Err(); err != nil {
		entry.lock <- struct{}{}
		s.dropReference(key, entry)
		return nil, err
	}
	return &affinityLease{store: s, entry: entry, key: key, Waited: waited}, nil
}

func (s *affinityStore) dropReference(key [32]byte, entry *affinityEntry) {
	s.mu.Lock()
	defer s.mu.Unlock()
	entry.users--
	entry.lastUsed = s.now()
	if entry.users == 0 && entry.accountID == 0 {
		delete(s.entries, key)
	}
}

func (l *affinityLease) Release() {
	if l == nil || l.entry == nil {
		return
	}
	l.once.Do(func() {
		// The mutex remains held until the accounting is updated, so eviction cannot race a
		// new waiter or an account binding written by this lease.
		l.store.mu.Lock()
		l.entry.lock <- struct{}{}
		l.entry.users--
		l.entry.lastUsed = l.store.now()
		if l.entry.users == 0 && l.entry.accountID == 0 {
			delete(l.store.entries, l.key)
		}
		l.store.mu.Unlock()
	})
}

// Bind is called only after successful JSON or client-visible response content. An early
// keepalive, quota notification or failed attempt must not take ownership of a conversation.
func (l *affinityLease) Bind(candidate control.Candidate) {
	if l != nil && l.entry != nil && candidate.AccountID > 0 {
		identity, ok := candidateIdentity(candidate)
		if !ok {
			l.entry.accountID = 0
			l.entry.identity = [32]byte{}
			return
		}
		l.entry.accountID = candidate.AccountID
		l.entry.identity = identity
	}
}

func candidateIdentity(candidate control.Candidate) ([32]byte, bool) {
	if candidate.Provider != "OPENAI" {
		return [32]byte{}, false
	}
	header := func(name string) string {
		for k, v := range candidate.AuthHeaders {
			if strings.EqualFold(k, name) {
				return v
			}
		}
		return ""
	}
	if candidate.Type == "OAUTH" || candidate.Type == "OAUTH_STATIC" {
		id := header("ChatGPT-Account-Id")
		if id == "" {
			id = candidate.AccountUUID
		}
		if id == "" {
			return [32]byte{}, false
		}
		return sha256.Sum256([]byte("OPENAI/OAUTH\x00" + id)), true
	}
	if candidate.Type == "API_KEY" {
		auth := header("Authorization")
		if auth == "" {
			return [32]byte{}, false
		}
		return sha256.Sum256([]byte("OPENAI/API_KEY\x00" + auth + "\x00" + header("OpenAI-Organization") + "\x00" + header("OpenAI-Project"))), true
	}
	return [32]byte{}, false
}

func (l *affinityLease) Candidates(candidates []control.Candidate, required bool) ([]control.Candidate, error) {
	owner := 0
	if l != nil && l.entry != nil {
		owner = l.entry.accountID
	}
	if required && owner == 0 {
		return nil, errAffinityUnknown
	}
	ownerIndex := -1
	for i, c := range candidates {
		if c.AccountID == owner && l != nil && l.entry != nil {
			identity, ok := candidateIdentity(c)
			if ok && identity == l.entry.identity {
				ownerIndex = i
				break
			}
		}
	}
	if required {
		if ownerIndex < 0 {
			return nil, errAffinityUnavailable
		}
		return []control.Candidate{candidates[ownerIndex]}, nil
	}
	if ownerIndex <= 0 {
		return candidates, nil
	}
	ordered := make([]control.Candidate, 0, len(candidates))
	ordered = append(ordered, candidates[ownerIndex])
	ordered = append(ordered, candidates[:ownerIndex]...)
	ordered = append(ordered, candidates[ownerIndex+1:]...)
	return ordered, nil
}

// Encrypted reasoning, encrypted tool arguments and compacted histories are upstream-account
// state even with store:false and full input. Treat unfamiliar shapes conservatively; never
// discard encrypted history to make a retry appear successful.
func requiresAffinity(body map[string]json.RawMessage) bool {
	var input any
	if json.Unmarshal(body["input"], &input) != nil {
		return true
	}
	var hasState func(any) bool
	hasState = func(value any) bool {
		switch v := value.(type) {
		case map[string]any:
			if kind, _ := v["type"].(string); kind == "compaction" || kind == "compaction_summary" {
				return true
			}
			for key, child := range v {
				if key == "encrypted_content" || key == "encrypted_function_args" {
					if child != nil && child != "" {
						return true
					}
				}
				if hasState(child) {
					return true
				}
			}
		case []any:
			for _, child := range v {
				if hasState(child) {
					return true
				}
			}
		}
		return false
	}
	return hasState(input)
}

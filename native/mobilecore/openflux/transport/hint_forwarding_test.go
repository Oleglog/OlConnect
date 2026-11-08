package transport

import (
	"sync"
	"testing"
)

// hintCarrier is a stand-in for a carrier that knows its own object envelope
// (boards: 48 KiB objects, 300 ms window).
type hintCarrier struct {
	sent   [][]byte
	got    func([]byte)
	mu     sync.Mutex
	closed bool
}

func (h *hintCarrier) Send(data []byte) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.sent = append(h.sent, append([]byte(nil), data...))
	return nil
}

func (h *hintCarrier) Receive(cb func([]byte)) { h.got = cb }

func (h *hintCarrier) Start() error { return nil }

func (h *hintCarrier) Stop() error {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.closed = true
	return nil
}

func (h *hintCarrier) IsConnected() bool { return true }

func (h *hintCarrier) PreferredBatchBytes() int { return 48000 }

func (h *hintCarrier) PreferredBatchLingerMs() int { return 300 }

// A carrier hint has to survive every wrapper in the chain. Boards runs only
// with -encryption-key, and while the hint was swallowed here the batched
// transport fell back to its 8 KiB / 5 ms defaults: objects carried ~1.6 KB of
// payload, and the AIMD pacer - which counts objects - held the tunnel at
// ~20 KB/s while the board reported no rate limiting at all.
func TestCarrierHintsSurviveTheWrappers(t *testing.T) {
	carrier := &hintCarrier{}

	enc, err := NewEncryptedTransport(carrier, "0123456789abcdef0123456789abcdef", "room", false)
	if err != nil {
		t.Fatalf("NewEncryptedTransport: %v", err)
	}
	if got := enc.PreferredBatchBytes(); got != 48000 {
		t.Fatalf("encrypted PreferredBatchBytes() = %d, want 48000", got)
	}
	if got := enc.PreferredBatchLingerMs(); got != 300 {
		t.Fatalf("encrypted PreferredBatchLingerMs() = %d, want 300", got)
	}

	legacy := NewCompressedTransport(enc)
	hinter, ok := legacy.(BatchSizeHinter)
	if !ok {
		t.Fatalf("compressed wrapper does not forward PreferredBatchBytes")
	}
	if got := hinter.PreferredBatchBytes(); got != 48000 {
		t.Fatalf("compressed PreferredBatchBytes() = %d, want 48000", got)
	}
	if lh, ok := legacy.(BatchLingerHinter); !ok || lh.PreferredBatchLingerMs() != 300 {
		t.Fatalf("compressed wrapper does not forward the linger hint")
	}
}

// A carrier with no opinion must still look like a wrapper that answers 0, so
// the batched transport keeps its defaults instead of being told "envelope of
// zero bytes".
func TestWrapperReportsNoHintWhenTheCarrierHasNone(t *testing.T) {
	plain := &hintCarrier{}
	plainHintless := &hintlessCarrier{inner: plain}

	enc, err := NewEncryptedTransport(plainHintless, "0123456789abcdef0123456789abcdef", "room", true)
	if err != nil {
		t.Fatalf("NewEncryptedTransport: %v", err)
	}
	if got := enc.PreferredBatchBytes(); got != 0 {
		t.Fatalf("PreferredBatchBytes() = %d, want 0 for a hintless carrier", got)
	}
	if got := enc.PreferredBatchLingerMs(); got != 0 {
		t.Fatalf("PreferredBatchLingerMs() = %d, want 0 for a hintless carrier", got)
	}
}

type hintlessCarrier struct{ inner Transport }

func (h *hintlessCarrier) Send(data []byte) error  { return h.inner.Send(data) }
func (h *hintlessCarrier) Receive(cb func([]byte)) { h.inner.Receive(cb) }
func (h *hintlessCarrier) Start() error            { return nil }
func (h *hintlessCarrier) Stop() error             { return nil }
func (h *hintlessCarrier) IsConnected() bool       { return true }

package transport

import (
	"testing"
	"time"
)

// deadLaneStub is a lane that never reads as connected, so the stripe's
// health watchdog sees every lane down.
type deadLaneStub struct{ roomTransportStub }

func (s *deadLaneStub) IsConnected() bool { return false }

// TestLanesDeadPastDeadline pins the telemost auto-restart path: without it a
// node kept running with every lane down and silently carried no traffic after
// the SFU session died.
func TestLanesDeadPastDeadline(t *testing.T) {
	st := NewStripedTransport([]Transport{&deadLaneStub{}})
	defer func() { _ = st.Stop() }()
	st.SetHealthDeadline(time.Second)

	start := time.Now()
	if st.lanesDeadPastDeadline(0, 2, start) {
		t.Fatal("fired on the first sample, before the deadline elapsed")
	}
	if st.lanesDeadPastDeadline(0, 2, start.Add(500*time.Millisecond)) {
		t.Fatal("fired before the deadline elapsed")
	}
	if !st.lanesDeadPastDeadline(0, 2, start.Add(2*time.Second)) {
		t.Fatal("did not fire after every lane stayed dead past the deadline")
	}
}

// TestLanesDeadRecoversOnAnyLiveLane pins that one live lane keeps the stripe
// alive and resets the dead-since timer.
func TestLanesDeadRecoversOnAnyLiveLane(t *testing.T) {
	st := NewStripedTransport([]Transport{&deadLaneStub{}})
	defer func() { _ = st.Stop() }()
	st.SetHealthDeadline(10 * time.Millisecond)

	start := time.Now()
	_ = st.lanesDeadPastDeadline(0, 1, start)
	if !st.lanesDeadPastDeadline(0, 1, start.Add(50*time.Millisecond)) {
		t.Fatal("expected the deadline to fire with the only lane down")
	}
	if st.lanesDeadPastDeadline(1, 1, start.Add(time.Minute)) {
		t.Fatal("a live lane must keep the stripe alive")
	}
	if st.lanesDeadPastDeadline(0, 1, start.Add(2*time.Minute)) {
		t.Fatal("the dead-since timer was not reset by the live lane")
	}
}

// TestHealthDeadlineDisabledByDefault pins that carriers other than Telemost
// never stop themselves.
func TestHealthDeadlineDisabledByDefault(t *testing.T) {
	st := NewStripedTransport([]Transport{&deadLaneStub{}})
	defer func() { _ = st.Stop() }()
	if st.healthDeadline != 0 {
		t.Fatalf("healthDeadline = %v, want 0 (disabled)", st.healthDeadline)
	}
	if st.lanesDeadPastDeadline(0, 1, time.Now().Add(24*time.Hour)) {
		t.Fatal("fired without a configured deadline")
	}
}

// TestControlFramesBypassTheBulkQueue pins that a full data queue does not
// hold a control frame: a stalled carrier used to strand the handshake behind
// hours of queued data.
func TestControlFramesBypassTheBulkQueue(t *testing.T) {
	b := NewBatchedTransport(&roomTransportStub{})
	for i := 0; i < batchQueueDepth; i++ {
		if err := b.Send([]byte("data")); err != nil {
			t.Fatalf("fill queue at %d: %v", i, err)
		}
	}
	if err := b.Send([]byte("data")); err == nil {
		t.Fatal("expected the data queue to be full")
	}
	if err := b.TrySend([]byte("ctrl")); err != nil {
		t.Fatalf("TrySend on a full data queue: %v", err)
	}
}

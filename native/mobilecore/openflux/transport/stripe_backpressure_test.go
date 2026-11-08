package transport

import (
	"strings"
	"testing"
	"time"
)

// The old box (rto << min(tries-1, 1)) resent one piece every ~1.2 s forever:
// the phone's session showed rtx 492 against tx 421 and a "4th send" every
// second. The wait has to double with every attempt and then stop at the cap.
func TestRtxWaitDoublesAndCaps(t *testing.T) {
	t.Parallel()
	q := &sender{srtt: 250 * time.Millisecond, rttvar: 20 * time.Millisecond}
	base := q.rto()
	if w := q.rtxWait(1); w != base {
		t.Fatalf("first retry waits %v, want the RTO %v", w, base)
	}
	if w := q.rtxWait(2); w != 2*base {
		t.Fatalf("second retry waits %v, want 2xRTO %v", w, 2*base)
	}
	if w := q.rtxWait(3); w != 4*base {
		t.Fatalf("third retry waits %v, want 4xRTO %v", w, 4*base)
	}

	// A slow link hits the ceiling instead of growing without bound.
	slow := &sender{srtt: 500 * time.Millisecond, rttvar: 50 * time.Millisecond}
	if w := slow.rtxWait(4); w != maxRtxWait {
		t.Fatalf("capped retry waits %v, want %v", w, maxRtxWait)
	}
	if w := slow.rtxWait(200); w != maxRtxWait {
		t.Fatalf("a piece retried many times waits %v, want %v", w, maxRtxWait)
	}
}

// The retransmission allowance is a rate, not a bank: a long quiet spell must
// not be spendable as one flood, and a lane refills slowly.
func TestRtxBudgetCapsTheFlood(t *testing.T) {
	t.Parallel()
	q := &sender{}
	now := time.Now()
	q.refillRtx(now)
	if q.rtxBudget != rtxBurst {
		t.Fatalf("first allowance = %v, want the burst %v", q.rtxBudget, rtxBurst)
	}

	q.rtxBudget = 0
	q.refillRtx(now.Add(100 * time.Millisecond))
	if q.rtxBudget > rtxPerSec*0.1+1e-9 {
		t.Fatalf("allowance after 100 ms = %v, want at most %v", q.rtxBudget, rtxPerSec*0.1)
	}

	q.rtxBudget = 0
	q.refillRtx(now.Add(time.Minute))
	if q.rtxBudget > rtxBurst {
		t.Fatalf("allowance after a quiet minute = %v, want at most %v", q.rtxBudget, rtxBurst)
	}
}

// A hold stops retransmissions only. New data must keep flowing: with both
// sides holding their data there is nothing left to ack, and the pair sits in a
// mutual mute - which is exactly what the stand showed (33-44 s of silence,
// then a moment of traffic, over and over).
func TestHoldStopsOnlyRetransmits(t *testing.T) {
	t.Parallel()
	q := newSenderWithQueuedPiece(t, time.Now())
	now := time.Now()

	q.setHold(true, now)
	if !q.enqueue([]byte("fresh")) {
		t.Fatal("enqueue refused on an empty queue")
	}
	out := q.pull(0, 10, now)
	if sentFrame(out, "lost") {
		t.Fatal("the held sender retransmitted a piece, the storm is not stopped")
	}
	if !sentAny(out) {
		t.Fatal("the held sender stopped sending new data: that is the mutual mute")
	}
}

// The hold expires on its own: retransmissions come back after silenceHold even
// though nothing has happened - no ack, no peer, no external event.
func TestHoldExpiresWithoutAnExternalEvent(t *testing.T) {
	t.Parallel()
	start := time.Now()
	q := newSenderWithQueuedPiece(t, start)

	q.setHold(true, start)
	if sentFrame(q.pull(0, 10, start), "lost") {
		t.Fatal("retransmission went out during the hold")
	}
	if sentFrame(q.pull(0, 10, start.Add(silenceHold+time.Millisecond)), "lost") {
		t.Fatal("retransmission did not resume after the hold expired")
	}
}

// newSenderWithQueuedPiece has one piece in flight, waiting to be retransmitted.
func newSenderWithQueuedPiece(t *testing.T, at time.Time) *sender {
	t.Helper()
	q := &sender{}
	q.init()
	q.srtt, q.rttvar = 200*time.Millisecond, 20*time.Millisecond
	q.seq, q.low = 200, 101
	e := &q.ring[101%txRingSize]
	*e = txEntry{seq: 101, buf: append([]byte{stripeMagic}, []byte("lost")...), ok: true, queued: true, lane: 0, at: at}
	q.rtxQ = []uint32{101}
	return q
}

func sentFrame(frames [][]byte, marker string) bool {
	for _, f := range frames {
		if strings.Contains(string(f), marker) {
			return true
		}
	}
	return false
}

func sentAny(frames [][]byte) bool {
	return len(frames) > 0
}

// Silence means quiet AND work outstanding: an idle tunnel is idle, not dead.
func TestPeerSilentNeedsInflightWork(t *testing.T) {
	t.Parallel()
	if !peerSilentNow(peerSilentAfter+time.Second, peerSilentMinInflight) {
		t.Fatal("quiet with pieces in flight should count as silent")
	}
	if peerSilentNow(peerSilentAfter+time.Second, peerSilentMinInflight-1) {
		t.Fatal("quiet with almost nothing in flight is idle, not silent")
	}
	if peerSilentNow(time.Second, 100) {
		t.Fatal("a second of quiet is not silence")
	}
}

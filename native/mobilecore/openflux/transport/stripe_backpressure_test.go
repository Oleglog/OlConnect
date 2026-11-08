package transport

import (
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

// While the peer is silent the sender keeps its data and retries: only the ack
// goes out, so the tunnel stops feeding the storm.
func TestSilentSenderSendsNothingButTheAck(t *testing.T) {
	t.Parallel()
	q := &sender{}
	q.init()
	q.ack = []byte("ack")
	q.setSilent(true)

	out := q.pull(0, 100, time.Now())
	if len(out) != 1 || string(out[0]) != "ack" {
		t.Fatalf("silent pull = %d frame(s), want only the ack", len(out))
	}

	q.setSilent(false)
	if !q.enqueue([]byte("frame")) {
		t.Fatal("enqueue refused on an empty queue")
	}
	if len(q.pull(0, 100, time.Now())) == 0 {
		t.Fatal("after the peer is back the sender still sends nothing")
	}
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

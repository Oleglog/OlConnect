package transport

import (
	"math"
	"testing"
	"time"
)

// A relay that refuses writes (Cups 429) should cost retransmissions before it
// costs new data: the allowance halves on a refusal and walks back up after.
func TestRtxScaleCutsAndGrows(t *testing.T) {
	t.Parallel()
	q := &sender{}
	q.init()
	if q.rtxScale != 1 {
		t.Fatalf("initial rtxScale = %v, want 1", q.rtxScale)
	}

	q.cutRtx()
	if math.Abs(q.rtxScale-0.5) > 1e-9 {
		t.Fatalf("rtxScale after one cut = %v, want 0.5", q.rtxScale)
	}
	q.growRtx()
	if q.rtxScale <= 0.5 || q.rtxScale > 1 {
		t.Fatalf("rtxScale after growth = %v, want within (0.5, 1]", q.rtxScale)
	}

	// The allowance itself follows the scale.
	q.rtxScale = 0.125
	q.rtxAt = time.Now().Add(-time.Second)
	q.rtxBudget = 0
	q.refillRtx(time.Now())
	if q.rtxBudget > rtxPerSec*0.125+0.001 {
		t.Fatalf("allowance after a second = %v, want at most %v", q.rtxBudget, rtxPerSec*0.125)
	}
}

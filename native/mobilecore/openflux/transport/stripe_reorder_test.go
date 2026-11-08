package transport

import (
	"testing"
	"time"
)

// A piece that arrives after the ones it belongs before is used, as long as the
// hole is still within the reorder horizon. The phone's session lost 114 of 418
// pieces (21%) to the old 5 s horizon, and every skip costs the tunnel's TCP a
// retransmit.
func TestLateWithinHorizonIsUsed(t *testing.T) {
	old := holeDeadline
	holeDeadline = 400 * time.Millisecond
	defer func() { holeDeadline = old }()

	got := make(chan byte, 16)
	r := newReorderBuffer(func(b []byte, gap bool) { got <- b[0] })

	r.push(10, 10, []byte{10})
	r.push(12, 10, []byte{12})
	time.Sleep(100 * time.Millisecond) // well inside the horizon
	r.push(11, 10, []byte{11})

	for _, want := range []byte{10, 11, 12} {
		select {
		case g := <-got:
			if g != want {
				t.Fatalf("delivered %d, want %d", g, want)
			}
		case <-time.After(2 * time.Second):
			t.Fatalf("piece %d never delivered", want)
		}
	}
	if _, sk, late, _, _ := r.snapshot(); sk != 0 || late != 0 {
		t.Fatalf("skipped=%d late=%d, want 0/0 for a piece inside the horizon", sk, late)
	}
}

// A steady stream that arrives with a jitter of one piece must not make the
// receiver give up on anything: this is the "skipped < 10%" case in miniature.
func TestSkippedStaysZeroWithJitter(t *testing.T) {
	old := holeDeadline
	holeDeadline = 10 * time.Second
	defer func() { holeDeadline = old }()

	delivered := make(chan struct{}, 128)
	r := newReorderBuffer(func(b []byte, gap bool) { delivered <- struct{}{} })

	const n = 100
	// Every piece swapped with its neighbour: a jitter of one piece, well
	// inside a horizon of 10 s at any real round trip.
	for i := uint32(1); i <= n; i += 2 {
		r.push(i+1, 1, []byte{byte(i + 1)})
		r.push(i, 1, []byte{byte(i)})
	}

	for i := 0; i < n; i++ {
		select {
		case <-delivered:
		case <-time.After(3 * time.Second):
			t.Fatalf("only %d of %d pieces delivered", i, n)
		}
	}
	d, sk, late, _, _ := r.snapshot()
	if d != n {
		t.Fatalf("delivered = %d, want %d", d, n)
	}
	if sk != 0 || late != 0 {
		t.Fatalf("skipped=%d late=%d, want 0/0 with jitter inside the horizon", sk, late)
	}
}

// Past the horizon the hole is given up on (that is what keeps a lost piece
// from holding a stream forever), and a piece arriving that late is counted
// late instead of being delivered out of order.
func TestHolePastHorizonIsSkipped(t *testing.T) {
	old := holeDeadline
	holeDeadline = 50 * time.Millisecond
	defer func() { holeDeadline = old }()

	got := make(chan byte, 16)
	r := newReorderBuffer(func(b []byte, gap bool) { got <- b[0] })
	done := make(chan struct{})
	go r.run(done)
	defer close(done)

	r.push(10, 10, []byte{10})
	r.push(12, 10, []byte{12})

	deadline := time.After(2 * time.Second)
	for i := 0; i < 2; i++ {
		select {
		case <-got:
		case <-deadline:
			t.Fatal("buffered pieces behind the hole were not delivered after the deadline")
		}
	}
	if _, sk, _, _, _ := r.snapshot(); sk != 1 {
		t.Fatalf("skipped = %d, want the one missing piece", sk)
	}

	r.push(11, 10, []byte{11})
	if _, _, late, _, _ := r.snapshot(); late != 1 {
		t.Fatalf("late = %d, want the piece that arrived after the skip", late)
	}
}

package transport

import "testing"

type roomTransportStub struct {
	receiver func([]byte)
	room     string
	onRoom   func(string)
	cookies  map[string]string
}

func (s *roomTransportStub) Start() error                  { return nil }
func (s *roomTransportStub) Stop() error                   { return nil }
func (s *roomTransportStub) Send([]byte) error             { return nil }
func (s *roomTransportStub) Receive(f func([]byte))         { s.receiver = f }
func (s *roomTransportStub) IsConnected() bool              { return true }
func (s *roomTransportStub) Stats() TransportStats          { return TransportStats{} }
func (s *roomTransportStub) RoomList() string               { return s.room }
func (s *roomTransportStub) OnRoomList(f func(string))       { s.onRoom = f }
func (s *roomTransportStub) PreferredBatchBytes() int        { return 1234 }
func (s *roomTransportStub) FetchCookies() (map[string]string, error) {
	return s.cookies, nil
}
func (s *roomTransportStub) ApplyCookies(v map[string]string) error {
	s.cookies = v
	return nil
}

func TestStripedRoomTransportForwardsControlMethods(t *testing.T) {
	inner := &roomTransportStub{room: "rooms", cookies: map[string]string{"old": "cookie"}}
	wrapped := NewStripedRoomTransport(inner)

	if got := wrapped.RoomList(); got != "rooms" {
		t.Fatalf("RoomList() = %q, want rooms", got)
	}
	called := false
	wrapped.OnRoomList(func(got string) { called = got == "new-rooms" })
	inner.onRoom("new-rooms")
	if !called {
		t.Fatal("OnRoomList callback was not forwarded")
	}
	if err := wrapped.ApplyCookies(map[string]string{"new": "cookie"}); err != nil {
		t.Fatalf("ApplyCookies: %v", err)
	}
	cookies, err := wrapped.FetchCookies()
	if err != nil || cookies["new"] != "cookie" {
		t.Fatalf("FetchCookies() = %v, %v", cookies, err)
	}
	if got := wrapped.PreferredBatchBytes(); got != 1234 {
		t.Fatalf("PreferredBatchBytes() = %d, want 1234", got)
	}
}

func TestStripedRoomTransportPreservesLargeBatchAndHandlesOutOfOrderRooms(t *testing.T) {
	innerA := &roomTransportStub{}
	innerB := &roomTransportStub{}
	tx := NewStripedRoomTransport(innerA)

	// Large 16 KB batch should not be split into 1294-byte Telemost pieces.
	large := make([]byte, 16000)
	for i := range large {
		large[i] = byte(i)
	}
	if err := tx.Send(large); err != nil {
		t.Fatalf("tx.Send(large): %v", err)
	}
	frames := tx.pull(0, 64)
	if len(frames) != 1 {
		t.Fatalf("16 KB batch split into %d pieces, want 1", len(frames))
	}

	// Simulate 4 pieces sent across 4 parallel rooms where room 3 finishes first.
	tx2 := NewStripedRoomTransport(innerA)
	rx2 := NewStripedRoomTransport(innerB)
	for i := 0; i < 4; i++ {
		if err := tx2.Send([]byte{byte(i + 1)}); err != nil {
			t.Fatalf("tx2.Send(%d): %v", i, err)
		}
	}
	sent := tx2.pull(0, 64)
	if len(sent) != 4 {
		t.Fatalf("pulled %d pieces, want 4", len(sent))
	}
	// Deliver piece 4 to rx2 first (out-of-order room completion) and feed its ACK to tx2.
	rx2.onLane(0, sent[3])
	ack := rx2.ro.ackFrame()
	if queuedRtx := tx2.snd.onAck(ack, tx2.snd.ring[tx2.snd.seq%txRingSize].at); queuedRtx {
		t.Fatalf("out-of-order room ACK falsely queued retransmissions for in-flight rooms")
	}
}

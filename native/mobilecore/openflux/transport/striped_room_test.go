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

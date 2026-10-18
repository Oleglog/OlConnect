package transport

// StripedRoomTransport adds the reliable striped link layer to a room-based
// carrier while keeping the carrier-specific control methods visible to the
// manager and mobile clients.
type StripedRoomTransport struct {
	*StripedTransport
	inner Transport
}

// NewStripedRoomTransport wraps a room-based carrier in a single stripe. A
// single lane still benefits from sequencing, reordering and retransmission.
func NewStripedRoomTransport(inner Transport) *StripedRoomTransport {
	return &StripedRoomTransport{
		StripedTransport: NewStripedTransport([]Transport{inner}),
		inner:            inner,
	}
}

func (s *StripedRoomTransport) RoomList() string {
	type roomLister interface{ RoomList() string }
	if inner, ok := s.inner.(roomLister); ok {
		return inner.RoomList()
	}
	return ""
}

func (s *StripedRoomTransport) OnRoomList(callback func(string)) {
	type roomListObserver interface{ OnRoomList(func(string)) }
	if inner, ok := s.inner.(roomListObserver); ok {
		inner.OnRoomList(callback)
	}
}

func (s *StripedRoomTransport) FetchCookies() (map[string]string, error) {
	type cookieFetcher interface {
		FetchCookies() (map[string]string, error)
	}
	if inner, ok := s.inner.(cookieFetcher); ok {
		return inner.FetchCookies()
	}
	return nil, nil
}

func (s *StripedRoomTransport) ApplyCookies(values map[string]string) error {
	type cookieApplier interface{ ApplyCookies(map[string]string) error }
	if inner, ok := s.inner.(cookieApplier); ok {
		return inner.ApplyCookies(values)
	}
	return nil
}

// PreferredBatchBytes preserves an optional carrier hint through the wrapper.
func (s *StripedRoomTransport) PreferredBatchBytes() int {
	if inner, ok := s.inner.(BatchSizeHinter); ok {
		return inner.PreferredBatchBytes()
	}
	return 0
}

package transport

import "time"

// maxRoomPieceBytes is the largest payload a single room stripe piece carries:
// it stays within uint16 wire framing (e.g. Cupsonline MaxPayloadBytes = 65535)
// after adding the 9-byte stripe header and 1-byte piece flag, so coalesced
// BatchedTransport frames ride as one piece instead of being chopped into
// 1294-byte Telemost RTP fragments.
const (
	maxRoomPieceBytes = 65535 - stripeHeader - 1
	roomAckInterval   = 50 * time.Millisecond
)

// StripedRoomTransport adds the reliable striped link layer to a room-based
// carrier while keeping the carrier-specific control methods visible to the
// manager and mobile clients.
type StripedRoomTransport struct {
	*StripedTransport
	inner Transport
}

// NewStripedRoomTransport wraps a room-based carrier in a single stripe.
// Because a room carrier (Avito, Cups.online) internally round-robins across
// multiple WebSocket rooms with independent RTTs, out-of-order room arrival
// must not trigger single-lane FIFO loss inference, and coalesced batches are
// kept whole up to maxRoomPieceBytes.
func NewStripedRoomTransport(inner Transport) *StripedRoomTransport {
	st := NewStripedTransport([]Transport{inner})
	st.ackEvery = roomAckInterval
	st.snd.pieceBytes = maxRoomPieceBytes
	st.snd.noOrderLoss = true
	return &StripedRoomTransport{
		StripedTransport: st,
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

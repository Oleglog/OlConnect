package openflux

import (
	"testing"

	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/transport"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/transport/yandex"
)

// The profile editor offers every carrier, so every carrier must reach its own
// branch of newInnerTransport. A missing branch falls through to the classic
// Yandex Docs transport, which points at a board/host that is not a Docs editor
// and hangs the connection screen instead of failing.
func TestEveryOfferedTransportHasItsOwnBranch(t *testing.T) {
	config := transport.DefaultConfig()

	boards := newInnerTransport("boards", "https://boards.yandex.ru/whiteboard/?hash=abc", config)
	if _, ok := boards.(*yandex.BoardsTransport); !ok {
		t.Fatalf("boards: newInnerTransport returned %T, want *yandex.BoardsTransport", boards)
	}

	docs := newInnerTransport("yandex", "https://docs.yandex.ru/docs/edit?doc=abc", config)
	if _, ok := docs.(*yandex.YandexDocsTransport); !ok {
		t.Fatalf("yandex: newInnerTransport returned %T, want *yandex.YandexDocsTransport", docs)
	}
}

func TestDetectTransportRecognisesBoards(t *testing.T) {
	cases := []struct {
		name string
		url  string
		want string
	}{
		{"board link", "https://boards.yandex.ru/whiteboard/?hash=ea1fa3fd647db29f766e06212a553539", "boards"},
		{"board link with locale host", "https://boards.yandex.ru/guest/?hash=abc", "boards"},
		{"volga document", "https://docs.yandex.ru/docs/edit/d/abc", "vyandex"},
	}
	for _, c := range cases {
		if got := detectTransport(c.url); got != c.want {
			t.Fatalf("%s: detectTransport() = %q, want %q", c.name, got, c.want)
		}
	}
}

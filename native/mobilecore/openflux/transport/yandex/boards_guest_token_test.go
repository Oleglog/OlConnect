package yandex

import (
	"errors"
	"fmt"
	"strings"
	"testing"
)

// The bodies below are verbatim replies of boards.yandex.ru on 2026-11-07 through
// POST /api with {"action":..., "content": base64(JSON)}, asked as a guest.
const (
	// get-whiteboard-info for a real hash whose owner switched guest access off.
	boardsClosedBoardBody = `{"redirect":"https://boards.yandex.ru/restrict?hash=ea1fa3fd647db29f766e06212a553539","error":{"code":212,"description":"Guest mode disabled"}}`
	// get-whiteboard-info for a well-formed hash that names no board.
	boardsNoSuchBoardBody = `{"error":{"code":250,"description":"Presentation not found: presentationHash=0123456789abcdef0123456789abcdef"}}`
	// get-whiteboard-info for an empty or 64-character hash: the format check.
	boardsBadHashBody = `{"error":{"code":200,"description":"hash"}}`
	// request-guest-token on a closed board: HTTP 200, no "error", no Set-Cookie,
	// just a redirect to the site root. This is why a status-code check alone
	// cannot tell a refusal from a success.
	boardsGuestTokenRefusedBody = `{"redirect":"https://boards.yandex.ru"}`
)

// A 200 carries the refusal in "error", so every 200 body has to be decoded
// before the caller reads the reply as a success.
func TestDecodeAPIResponseNamesTheRefusal(t *testing.T) {
	cases := []struct {
		name   string
		body   string
		code   int
		substr []string
	}{
		{
			name:   "guest mode disabled",
			body:   boardsClosedBoardBody,
			code:   212,
			substr: []string{"212", "Guest mode disabled", "closed to guests", "Доступ по ссылке"},
		},
		{
			name:   "board not found",
			body:   boardsNoSuchBoardBody,
			code:   250,
			substr: []string{"250", "no board"},
		},
		{
			name:   "bad hash",
			body:   boardsBadHashBody,
			code:   200,
			substr: []string{"200", "32-character"},
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := decodeAPIResponse("get-whiteboard-info", []byte(c.body))
			if err == nil {
				t.Fatalf("decodeAPIResponse() = nil error, want a refusal")
			}
			var apiErr *boardsAPIError
			if !errors.As(err, &apiErr) {
				t.Fatalf("errors.As(err, *boardsAPIError) = false, err = %v", err)
			}
			if apiErr.Code != c.code {
				t.Fatalf("Code = %d, want %d", apiErr.Code, c.code)
			}
			for _, want := range c.substr {
				if !strings.Contains(err.Error(), want) {
					t.Fatalf("Error() = %q, want %q in it", err.Error(), want)
				}
			}
		})
	}
}

// request-guest-token on a closed board answers 200 with a redirect and no
// token cookie: no refusal to decode, so the caller compares the redirect with
// the board it asked about instead of reporting a bare "token not found".
func TestDecodeAPIResponseAcceptsARedirectWithoutError(t *testing.T) {
	resp, err := decodeAPIResponse("request-guest-token", []byte(boardsGuestTokenRefusedBody))
	if err != nil {
		t.Fatalf("decodeAPIResponse() = %v, want nil: a redirect is not an error", err)
	}
	if resp.Redirect != "https://boards.yandex.ru" {
		t.Fatalf("Redirect = %q, want the site root", resp.Redirect)
	}
	if strings.Contains(resp.Redirect, "ea1fa3fd647db29f766e06212a553539") {
		t.Fatalf("Redirect = %q, want it not to name the board", resp.Redirect)
	}
}

func TestDecodeAPIResponseIgnoresNonJSONAndSuccess(t *testing.T) {
	cases := map[string]string{
		"html error page": `<html><body>502</body></html>`,
		"empty body":      "",
		"board info":      `{"presentation":{"properties":{"current_slide":"7a1"},"items":""},"socket_servers":[{"ip":"socket33.boards.yandex.ru"}]}`,
	}
	for name, body := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := decodeAPIResponse("get-whiteboard-info", []byte(body)); err != nil {
				t.Fatalf("decodeAPIResponse() = %v, want nil", err)
			}
		})
	}
}

// wbpost() re-sends the csrf it got from an earlier /api reply, so the guest
// join has to be able to read it off the board info reply.
func TestDecodeAPIResponseCarriesCSRF(t *testing.T) {
	resp, err := decodeAPIResponse("get-whiteboard-info",
		[]byte(`{"csrf":"d6b1a2","presentation":{"properties":{"current_slide":"7a1"}}}`))
	if err != nil {
		t.Fatalf("decodeAPIResponse() = %v, want nil", err)
	}
	if resp.CSRF != "d6b1a2" {
		t.Fatalf("CSRF = %q, want %q", resp.CSRF, "d6b1a2")
	}
}

// TestBoardsGuestLandingIsNotARefusal pins the difference between "you are not
// signed in" and "this board is closed": an anonymous info call is answered with
// code 204 and a redirect to /guest, and the guest session is minted right after
// by request-guest-token. Treating 204 as fatal made every board with link access
// open look closed (measured 2026-11-08). Code 212 with a redirect to /restrict
// stays a refusal.
func TestBoardsGuestLandingIsNotARefusal(t *testing.T) {
	cases := []struct {
		name string
		err  *boardsAPIError
		want bool
	}{
		{"code 204", &boardsAPIError{Code: 204, Redirect: "https://boards.yandex.ru/guest?hash=abc"}, true},
		{"redirect to guest without code", &boardsAPIError{Code: 0, Redirect: "https://boards.yandex.ru/guest?hash=abc"}, true},
		{"guest mode disabled", &boardsAPIError{Code: 212, Description: "Guest mode disabled", Redirect: "https://boards.yandex.ru/restrict?hash=abc"}, false},
		{"hash names no board", &boardsAPIError{Code: 250, Description: "Presentation not found"}, false},
		{"nil", nil, false},
	}
	for _, c := range cases {
		if got := boardsGuestLanding(c.err); got != c.want {
			t.Fatalf("%s: boardsGuestLanding() = %v, want %v", c.name, got, c.want)
		}
	}
	// errors.As must see the refusal through the wrapping the caller does.
	wrapped := fmt.Errorf("/api get-whiteboard-info: %w", &boardsAPIError{Code: 204})
	var apiErr *boardsAPIError
	if !errors.As(wrapped, &apiErr) || !boardsGuestLanding(apiErr) {
		t.Fatal("a wrapped 204 must still be recognised as the guest landing")
	}
}
// The anonymous info reply carries no board, so the dashboard and the shard
// only appear in the reply fetched with the guest token; the merge must take
// them without letting an absent field erase what the first reply knew.
func TestMergeBoardStateKeepsTokenScopedFields(t *testing.T) {
	state := map[string]string{"dashboard": "", "ws_host": "socket33.boards.yandex.ru"}
	mergeBoardState(state, map[string]string{
		"dashboard":     "3f9d53a03819d058cbcb2503d8413d39",
		"current_slide": "3f9d53a03819d058cbcb2503d8413d39",
		"ws_host":       "socket62.boards.yandex.ru",
		"csrf":          "",
	})
	if state["dashboard"] != "3f9d53a03819d058cbcb2503d8413d39" {
		t.Fatalf("dashboard = %q, want the slide from the token reply", state["dashboard"])
	}
	if state["ws_host"] != "socket62.boards.yandex.ru" {
		t.Fatalf("ws_host = %q, want the shard from the token reply", state["ws_host"])
	}
	if _, ok := state["csrf"]; ok {
		t.Fatalf("csrf = %q, want an empty field not to be copied", state["csrf"])
	}

	mergeBoardState(state, map[string]string{"dashboard": ""})
	if state["dashboard"] == "" {
		t.Fatalf("dashboard was erased by a reply that did not name one")
	}
}

// A dashboard room lives on one socket shard: two guests that dial the site
// host are load-balanced onto different front pods and never see each other's
// objects (measured 2026-11-08). So the board's own shard has to be tried
// first, ahead of whatever endpoint happened to work last time.
func TestSocketURLsPreferTheBoardsShard(t *testing.T) {
	tr := &BoardsTransport{}
	tr.goodSocketURL.Store("wss://boards.yandex.ru/socket.io/?EIO=4&transport=websocket")

	urls := tr.socketURLs(boardsInfo{wsHost: "socket62.boards.yandex.ru"})
	if len(urls) != 2 {
		t.Fatalf("socketURLs() = %v, want the shard and the site host", urls)
	}
	if urls[0] != "wss://socket62.boards.yandex.ru/socket.io/?EIO=4&transport=websocket" {
		t.Fatalf("first candidate = %q, want the board shard", urls[0])
	}
	if urls[1] != "wss://boards.yandex.ru/socket.io/?EIO=4&transport=websocket" {
		t.Fatalf("second candidate = %q, want the site host", urls[1])
	}

	// A remembered shard is still tried when the API names another one, and it
	// is not duplicated.
	tr.goodSocketURL.Store("wss://socket61.boards.yandex.ru/socket.io/?EIO=4&transport=websocket")
	urls = tr.socketURLs(boardsInfo{wsHost: "socket62.boards.yandex.ru"})
	if len(urls) != 3 {
		t.Fatalf("socketURLs() = %v, want shard, remembered shard and site host", urls)
	}
	if urls[1] != "wss://socket61.boards.yandex.ru/socket.io/?EIO=4&transport=websocket" {
		t.Fatalf("second candidate = %q, want the remembered shard", urls[1])
	}
}

// One modify-objects event carries the whole batch, and the board refuses a
// value above 48 KiB of payload (PAYLOAD_TOO_LARGE, measured 2026-11-08), so
// the batch hint must stay under that ceiling or a burst loses its tail.
func TestBoardsBatchFitsOneObject(t *testing.T) {
	if got := (&BoardsTransport{}).PreferredBatchBytes(); got <= 0 || got > boardsMaxObjectBytes {
		t.Fatalf("PreferredBatchBytes() = %d, want it within (0, %d]", got, boardsMaxObjectBytes)
	}
	if got := (&BoardsTransport{}).PreferredBatchLingerMs(); got < 0 {
		t.Fatalf("PreferredBatchLingerMs() = %d, want non-negative", got)
	}
}

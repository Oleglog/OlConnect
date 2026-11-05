package mailru

import (
	"bytes"
	"encoding/base64"
	"os"
	"reflect"
	"testing"
)

func TestNormalizeWeblink(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{"https://cloud.mail.ru/public/4R3g/5Vz4Wxyz", "4R3g/5Vz4Wxyz"},
		{"https://cloud.mail.ru/public/4R3g/5Vz4Wxyz/", "4R3g/5Vz4Wxyz"},
		{"https://cloud.mail.ru/public/4R3g/5Vz4Wxyz?weblink=4R3g%2F5Vz4Wxyz", "4R3g/5Vz4Wxyz"},
		{"https://cloud.mail.ru/public/4R3g/5Vz4Wxyz#heading", "4R3g/5Vz4Wxyz"},
		{"http://cloud.mail.ru/public/AbCd/EfGh", "AbCd/EfGh"},
		{"https://doc.mail.ru/public/4R3g/5Vz4Wxyz", "4R3g/5Vz4Wxyz"},
		{"https://docs.mail.ru/public/4R3g/5Vz4Wxyz", "4R3g/5Vz4Wxyz"},
		{"https://doc.mail.ru/d/4R3g/5Vz4Wxyz", "4R3g/5Vz4Wxyz"},
		{"https://doc.mail.ru/4R3g/5Vz4Wxyz", "4R3g/5Vz4Wxyz"},
		{"https://docs.mail.ru/4R3g/5Vz4Wxyz", "4R3g/5Vz4Wxyz"},
		{"/public/4R3g/5Vz4Wxyz", "4R3g/5Vz4Wxyz"},
		{"public/4R3g/5Vz4Wxyz", "4R3g/5Vz4Wxyz"},
		{"4R3g/5Vz4Wxyz", "4R3g/5Vz4Wxyz"},
		{"  https://cloud.mail.ru/public/4R3g/5Vz4Wxyz?foo=bar#baz  ", "4R3g/5Vz4Wxyz"},
	}

	for _, tc := range tests {
		actual := normalizeWeblink(tc.input)
		if actual != tc.expected {
			t.Errorf("normalizeWeblink(%q) = %q, expected %q", tc.input, actual, tc.expected)
		}
	}
}

func TestV2ChatFramesDistinguishEndpointsOnSameAccount(t *testing.T) {
	encode := func(sender, payload string) string {
		return chatDataTagV2 + sender + base64.StdEncoding.EncodeToString([]byte(payload))
	}
	frame := `42["message",{"type":"message","messages":[` +
		`{"message":"` + encode("2222222222", "peer") + `","useridoriginal":"shared-user"},` +
		`{"message":"` + encode("1111111111", "self") + `","useridoriginal":"shared-user"},` +
		`{"message":"` + chatDataTag + base64.StdEncoding.EncodeToString([]byte("legacy-self")) + `","useridoriginal":"shared-user"}` +
		`]}]`

	got := decodeChatDataFilter([]byte(frame), "1111111111", func(id string) bool {
		return id == "shared-user"
	})
	if len(got) != 1 || !reflect.DeepEqual(got[0], []byte("peer")) {
		t.Fatalf("decoded frames = %q, want only peer", got)
	}
}

// TestRotationDefaultsStayUnderTheAntiAbuseBudget pins the three constants that
// the R7 server's per-connection 4004 budget depends on. They were loosened in
// v1.0.18 ("unthrottle") and the carrier then got cut 13-36 s into every
// connection, authenticated sessions included.
func TestRotationDefaultsStayUnderTheAntiAbuseBudget(t *testing.T) {
	if defaultMailruMsgIntervalMs > 200 {
		t.Fatalf("msg interval %dms exceeds the measured burst-safe pacing", defaultMailruMsgIntervalMs)
	}
	if defaultMailruRotateMsgs > 60 {
		t.Fatalf("rotate after %d messages, budget is ~80-90 per connection", defaultMailruRotateMsgs)
	}
	if defaultMailruRotateMs > 25000 {
		t.Fatalf("rotate at %dms, the server drops a loaded connection at ~45s", defaultMailruRotateMs)
	}
}

// TestRotationAppliesToAuthenticatedSessions pins the guard that made cookie
// sessions skip rotation: the budget is per connection, not per account, so a
// cookie session must rotate like any other.
func TestRotationAppliesToAuthenticatedSessions(t *testing.T) {
	src := readTransportSource(t, "mailru.go")
	for _, forbidden := range []string{
		"!t.hasCookies.Load() && mailruRotateMsgs",
		"t.hasCookies.Load() {\n\t\t\t\tcontinue",
	} {
		if bytes.Contains(src, []byte(forbidden)) {
			t.Fatalf("rotation is skipped for cookie sessions again: %q", forbidden)
		}
	}
}

func readTransportSource(t *testing.T, name string) []byte {
	t.Helper()
	data, err := os.ReadFile(name)
	if err != nil {
		t.Fatalf("read %s: %v", name, err)
	}
	return data
}

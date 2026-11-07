package mailru

import (
	"bytes"
	"encoding/base64"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"
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
	// Measured 2026-11-07 over 22 harness runs: a connection survives 22-34
	// chat frames whatever the pacing, and a make-before-break swap only beats
	// the cut when rotateMsgs plus the frames spent dialling stays under ~22
	// (auth takes 0.5-1.5 s, so 3-10 frames at 150 ms). Rotating at 20 spent 27
	// frames and produced 10 kicks in a single run; at 8 it produced none. The
	// phone then showed the other edge of the same trade: dialling that often
	// is rate limited (http 429), so the budget is spread over a dial floor.
	if defaultMailruRotateMsgs > 18 {
		t.Fatalf("rotate after %d messages: the swap must land before the 22-34 frame budget runs out, including the frames spent dialling", defaultMailruRotateMsgs)
	}
	if mailruHardFrameCap <= defaultMailruRotateMsgs {
		t.Fatalf("hard frame cap %d does not exceed the rotation count %d: a deferred rotation would then write into the 4004 budget", mailruHardFrameCap, defaultMailruRotateMsgs)
	}
	if mailruHardFrameCap >= 22 {
		t.Fatalf("hard frame cap %d reaches the 22-frame floor of the measured 4004 budget", mailruHardFrameCap)
	}
	if defaultMailruRotateMs > 12000 {
		t.Fatalf("rotate at %dms, 500ms pacing needs 12s to stay under the budget", defaultMailruRotateMs)
	}
	// One connection's frame budget must last exactly one dial window: the
	// carrier answers a dial inside that window with http 429 (measured on the
	// phone: dials 4 s apart, "hit #1" within seconds), and pacing that finishes
	// the budget early leaves the tunnel idle for the rest of the window.
	if want := mailruDialFloor / time.Duration(mailruRotateMsgs); mailruMsgInterval != want {
		t.Fatalf("pacing %v does not match the dial floor over the frame budget (%v): one connection must take one dial window", mailruMsgInterval, want)
	}
	if authFrames := mailruRotateAuthWait / mailruMsgInterval; int64(authFrames)+mailruRotateMsgs > 22 {
		t.Fatalf("rotation at %d frames plus a worst-case auth of %d frames exceeds the 22-frame budget", mailruRotateMsgs, authFrames)
	}
	// Batches carry the tunnel data into that fixed message budget, but a chat
	// message is base64 (+33%) inside a JSON frame and the carrier stops
	// relaying large ones: 500 KB batches stalled a 1 MB download at ~220 KB,
	// 60 KB at 728 KB, while 32 KB shipped a page in 1.4 s. 48 KB is the
	// largest batch measured below that stall point.
	if mailruPreferredBatchBytes > 48_000 {
		t.Fatalf("batch of %d bytes: oversized chat messages wedge large transfers", mailruPreferredBatchBytes)
	}
	if mailruPreferredBatchBytes < 8_000 {
		t.Fatalf("batch of %d bytes: a segment per message drowns in per-message pacing", mailruPreferredBatchBytes)
	}
	if mailruPreferredBatchLingerMs < 5 {
		t.Fatalf("batch linger %dms: the 5 ms global default collects one or two segments at browsing rates", mailruPreferredBatchLingerMs)
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

// Test4004CooldownStaysOffByDefault pins the knob: upstream reconnects on the
// ordinary backoff, and the cooldown that was tried here made no observable
// difference while costing a 30 s dead window on every kick. It must stay
// reachable (behind the constant) but disabled.
func Test4004CooldownStaysOffByDefault(t *testing.T) {
	src := string(readTransportSource(t, "mailru.go"))
	if !strings.Contains(src, "mailruKickCooldown > 0 && strings.Contains(text, \"4004\")") {
		t.Fatal("the 4004 cooldown must stay behind its knob so it can be re-enabled")
	}
	if !strings.Contains(src, "wait > d") {
		t.Fatal("scheduleReconnect ignores the 4004 cooldown")
	}
	if mailruKickCooldown != 0 {
		t.Fatalf("kick cooldown = %v; upstream reconnects on the ordinary backoff and the "+
			"cooldown made no observable difference", mailruKickCooldown)
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

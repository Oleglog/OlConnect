package mailru

import (
	"encoding/base64"
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

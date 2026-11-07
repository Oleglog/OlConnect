package yandex

import (
	"errors"
	"strings"
	"testing"
)

// The Android client reads the captcha challenge URL out of the error text: it
// scans the raw message for "showcaptcha"/"checkcaptcha" and opens that page in
// a browser, because the interactive checkbox needs the browser's PGreed
// attestation and a Go-side PoW is refused with form-fb-hint=7.73. So the URL
// has to survive into Error() and the sentinel has to stay unwrappable.
func TestCaptchaErrorCarriesChallengeURL(t *testing.T) {
	const challenge = "https://docs.yandex.ru/showcaptchafast?d=ABC123&retpath=x"

	err := newCaptchaError("captcha POST status 403", challenge)

	if !errors.Is(err, ErrCaptchaRequired) {
		t.Fatalf("errors.Is(err, ErrCaptchaRequired) = false")
	}
	var cerr *CaptchaError
	if !errors.As(err, &cerr) {
		t.Fatalf("errors.As(err, *CaptchaError) = false")
	}
	if cerr.URL != challenge {
		t.Fatalf("CaptchaError.URL = %q, want %q", cerr.URL, challenge)
	}
	if !strings.Contains(err.Error(), "showcaptcha") {
		t.Fatalf("Error() = %q, want the challenge URL in it", err.Error())
	}
}

func TestAbsolutizeLocation(t *testing.T) {
	cases := []struct {
		loc, page, want string
	}{
		{"/showcaptcha?cc=1", "https://docs.yandex.ru/edit/d/abc", "https://docs.yandex.ru/showcaptcha?cc=1"},
		{"https://docs.yandex.ru/showcaptcha?cc=1", "https://docs.yandex.ru/edit/d/abc", "https://docs.yandex.ru/showcaptcha?cc=1"},
		{"/showcaptcha?cc=1", "", "/showcaptcha?cc=1"},
	}
	for _, c := range cases {
		if got := absolutizeLocation(c.loc, c.page); got != c.want {
			t.Fatalf("absolutizeLocation(%q, %q) = %q, want %q", c.loc, c.page, got, c.want)
		}
	}
}

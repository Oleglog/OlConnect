package mobilecore

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"

	xraynet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
)

func TestRejectsInvalidXrayConfig(t *testing.T) {
	if err := StartXray(t.TempDir(), "{"); err == nil {
		t.Fatal("StartXray accepted invalid JSON")
	}
	if IsXrayRunning() {
		t.Fatal("Xray remained running after invalid config")
	}
}

func TestStartXraySetsAssetDirectory(t *testing.T) {
	directory := t.TempDir()
	if err := StartXray(directory, "{"); err == nil {
		t.Fatal("StartXray accepted invalid JSON")
	}
	if value := os.Getenv("xray.location.asset"); value != directory {
		t.Fatalf("xray.location.asset = %q, want %q", value, directory)
	}
}

func TestStartXrayRequiresAssetDirectory(t *testing.T) {
	if err := StartXray("", "{}"); err == nil {
		t.Fatal("StartXray accepted an empty asset directory")
	}
}

func TestValidateXrayConfigRequiresAssetDirectory(t *testing.T) {
	if err := ValidateXrayConfig("", "{}"); err == nil {
		t.Fatal("ValidateXrayConfig accepted an empty asset directory")
	}
}

func TestStopXrayIsIdempotent(t *testing.T) {
	if err := StopXray(); err != nil {
		t.Fatalf("StopXray returned an error: %v", err)
	}
}

func TestStopProfileProbeIsIdempotent(t *testing.T) {
	if err := StopProfileProbe(); err != nil {
		t.Fatalf("StopProfileProbe returned an error: %v", err)
	}
}

func TestWaitXrayReadyRejectsInvalidArguments(t *testing.T) {
	if err := WaitXrayReady(0, 100); err == nil {
		t.Fatal("WaitXrayReady accepted an invalid port")
	}
	if err := WaitXrayReady(1080, 0); err == nil {
		t.Fatal("WaitXrayReady accepted an invalid timeout")
	}
}

func TestOpenFluxStartStop(t *testing.T) {
	if IsOpenFluxConnected() {
		t.Fatal("OpenFlux should not be connected initially")
	}
	StopOpenFlux()
	if IsOpenFluxConnected() {
		t.Fatal("OpenFlux should still be disconnected")
	}
}

func TestURLTestUsesDedicatedInboundAndHead(t *testing.T) {
	method := ""
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		method = request.Method
		response.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	inboundTag := ""
	elapsed, err := runURLTest(server.URL, 1000, func(ctx context.Context, destination xraynet.Destination) (net.Conn, error) {
		if inbound := session.InboundFromContext(ctx); inbound != nil {
			inboundTag = inbound.Tag
		}
		return (&net.Dialer{}).DialContext(ctx, "tcp", destination.NetAddr())
	})
	if err != nil {
		t.Fatal(err)
	}
	if elapsed < 0 {
		t.Fatalf("elapsed = %d, want >= 0", elapsed)
	}
	if method != http.MethodHead {
		t.Fatalf("method = %q, want HEAD", method)
	}
	if inboundTag != latencyTestInboundTag {
		t.Fatalf("inbound tag = %q, want %q", inboundTag, latencyTestInboundTag)
	}
}

func TestProfileProbeURLTestUsesRequestedInbound(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
		response.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	inboundTag := ""
	_, err := runURLTestWithInboundTag(server.URL, 1000, "profile-probe-2", func(ctx context.Context, destination xraynet.Destination) (net.Conn, error) {
		if inbound := session.InboundFromContext(ctx); inbound != nil {
			inboundTag = inbound.Tag
		}
		return (&net.Dialer{}).DialContext(ctx, "tcp", destination.NetAddr())
	})
	if err != nil {
		t.Fatal(err)
	}
	if inboundTag != "profile-probe-2" {
		t.Fatalf("inbound tag = %q, want %q", inboundTag, "profile-probe-2")
	}
}

func TestURLTestRejectsNonNoContentResponse(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
		response.WriteHeader(http.StatusBadGateway)
	}))
	defer server.Close()

	_, err := runURLTest(server.URL, 1000, func(ctx context.Context, destination xraynet.Destination) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp", destination.NetAddr())
	})
	if err == nil {
		t.Fatal("URL test accepted HTTP 502 as a successful latency result")
	}
}

func TestURLTestRejectsInvalidArguments(t *testing.T) {
	if _, err := UrlTest("not-a-url", 1000); err == nil {
		t.Fatal("UrlTest accepted an invalid URL")
	}
	if _, err := UrlTest("https://example.com", 0); err == nil {
		t.Fatal("UrlTest accepted an invalid timeout")
	}
	if _, err := ProfileProbeUrlTest("https://example.com", 1000, ""); err == nil {
		t.Fatal("ProfileProbeUrlTest accepted an empty inbound tag")
	}
}

func TestFatalErrors(t *testing.T) {
	fatal := []string{
		"load Xray config: invalid JSON",
		"create Xray instance: invalid outbound",
		"wait for olcRTC: failed to create link: carrier auth failed: unauthorized",
		"SOCKS5 authentication failed",
		"chacha20poly1305: message authentication failed",
	}
	for _, message := range fatal {
		if !IsFatalError(message) {
			t.Fatalf("IsFatalError(%q) = false", message)
		}
	}

	retryable := []string{
		"start Xray instance: network unreachable",
		"wait for Xray SOCKS: connection refused",
		"wait for olcRTC: context deadline exceeded",
		"invalid VPN datapath response",
	}
	for _, message := range retryable {
		if IsFatalError(message) {
			t.Fatalf("IsFatalError(%q) = true", message)
		}
	}
}

func TestVP8CompatibilityModes(t *testing.T) {
	for _, mode := range []string{"", "current", "CURRENT", "legacy", " LEGACY "} {
		if err := registerVP8Transport(mode); err != nil {
			t.Fatalf("registerVP8Transport(%q) returned an error: %v", mode, err)
		}
	}
	if err := registerVP8Transport("future"); err == nil {
		t.Fatal("registerVP8Transport accepted an unsupported mode")
	}
}

// TestVP8TransportModeUsesCoreTransport pins the mapping that decides which VP8
// data plane the phone actually runs. The regression it guards is not visible in
// the core version: mobilecore used to register the vendored legacyvp8channel
// copy for "current" as well, so bumping the core (v1.7.43) still shipped a
// transport predating the bounded RTP reassembly wait. On a line with any packet
// loss that turned into ~28 s of carrier readiness and tunnel streams the server
// counted as in=0 out=0.
func TestVP8TransportModeUsesCoreTransport(t *testing.T) {
	for _, mode := range []string{"", "current", "CURRENT", " current "} {
		got, err := vp8TransportMode(mode)
		if err != nil {
			t.Fatalf("vp8TransportMode(%q) returned an error: %v", mode, err)
		}
		if got != vp8TransportCore {
			t.Fatalf("vp8TransportMode(%q) = %q, want %q: the shipped default must run the core transport", mode, got, vp8TransportCore)
		}
	}
	for _, mode := range []string{"legacy", "LEGACY", " legacy "} {
		got, err := vp8TransportMode(mode)
		if err != nil {
			t.Fatalf("vp8TransportMode(%q) returned an error: %v", mode, err)
		}
		if got != vp8TransportLegacy {
			t.Fatalf("vp8TransportMode(%q) = %q, want %q", mode, got, vp8TransportLegacy)
		}
	}
}

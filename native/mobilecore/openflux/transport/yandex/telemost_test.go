package yandex

import (
	"context"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/pion/webrtc/v4"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/netbind"
)

func TestPlanVideoTrackPreservesBundleAndExtractSDPUfrag(t *testing.T) {
	raw := strings.Join([]string{
		"v=0",
		"o=- 1 1 IN IP4 127.0.0.1",
		"s=-",
		"t=0 0",
		"a=group:BUNDLE 0 1 2",
		"m=audio 9 UDP/TLS/RTP/SAVPF 111",
		"a=mid:0",
		"a=ice-ufrag:ufrag123",
		"a=setup:actpass",
		"m=video 9 UDP/TLS/RTP/SAVPF 96",
		"a=mid:1",
		"a=sendrecv",
		"a=msid:stream1 track1",
		"a=ssrc:12345678 cname:test-cname",
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
		"a=mid:2",
		"",
	}, "\r\n")

	plan, err := planVideoTrack(raw, modeSingle, kindCamera)
	if err != nil {
		t.Fatalf("planVideoTrack: %v", err)
	}
	if !strings.Contains(plan.sdp, "a=group:BUNDLE 0 1 2\r\n") {
		t.Fatalf("expected original BUNDLE line to remain intact, got:\n%s", plan.sdp)
	}
	if !strings.Contains(plan.sdp, "a=content:speaker,main\r\n") {
		t.Fatalf("expected a=content:speaker,main in planned video SDP, got:\n%s", plan.sdp)
	}
}

func TestBoundPionNetProtectsSocketsAndProvidesInterfaces(t *testing.T) {
	var protectedCount atomic.Int32
	netbind.SetProtector(func(fd int) bool {
		if fd > 0 {
			protectedCount.Add(1)
		}
		return true
	})
	defer netbind.SetProtector(nil)

	pc, err := netbind.ListenPacket(context.Background(), "udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("netbind.ListenPacket: %v", err)
	}
	_ = pc.Close()
	if protectedCount.Load() == 0 {
		t.Fatalf("expected SetProtector callback to be invoked for ListenPacket")
	}

	pn := newBoundPionNet()
	ifaces, err := pn.Interfaces()
	if err != nil {
		t.Fatalf("boundPionNet.Interfaces: %v", err)
	}
	for _, ifc := range ifaces {
		if isTunnelInterfaceName(ifc.Name) {
			t.Fatalf("expected tunnel interface %q to be filtered out", ifc.Name)
		}
	}

	se := newTelemostSettingEngine()
	api := webrtc.NewAPI(webrtc.WithSettingEngine(se))
	peer, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatalf("NewPeerConnection with boundPionNet: %v", err)
	}
	_ = peer.Close()
}

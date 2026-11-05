# Changelog

## Unreleased

## 1.7.11 — 2026-11-05

- CI: the release build now reads the mobilecore pins from `native/mobilecore/go.mod` instead of a hardcoded copy, which failed the artifact check on every core bump.
- Same transport and core changes as 1.7.10, first published here.

## 1.7.10 — 2026-11-05

- Transport: the KCP length prefix and payload now go out in one write. Two writes under a mutex stopped concurrent senders interleaving but did not make the pair atomic against failure: if the prefix landed and the payload did not, the stream could not resynchronise, KCP reported nothing, and the tunnel silently stopped carrying data.
- Transport: bound the reassembled VP8 frame (4 payloads). The buffer only resets on a start-of-partition bit, a sequence gap or the marker bit, so a peer that never sets the marker could grow it without limit.
- Transport: emit the epoch keepalive immediately when a peer session is created, so the peer latches our epoch without waiting for the next idle tick.
- OpenFlux: Telemost asks the SFU for a 1920x1080 sharing slot (0.45 → 1.15 MB/s per lane in the upstream measurement), gains a 60 s lane health deadline so a dead SFU session restarts instead of hanging, sends control frames ahead of the bulk queue, and uses a current Firefox UA for Avito (it answers the old Chrome/137 one with 403).
- Core pin raised to `Oleglog/Olcrtc_manager` at `60015f0`: single-write KCP framing, ICE server normalisation for goolom, fast publisher reconnects, and the client reconnect serialisation.

## 1.5.0 — 2026-09-09

- Tunnel & Web Surfing: restore tunnel MTU to 1500 to match Android VpnService MTU, completely resolving issues with stalled websites or timeouts on packets > 1400 bytes (TLS 1.3 ClientHello with post-quantum ML-KEM/Kyber keys in Chrome, large HTTP payloads).
- Transport: remove spurious keepalive injection from eager VP8 writer loop in `legacyvp8channel`, keeping RTP timestamps linear and avoiding WebRTC SFU track stalls.
- UI: modern floating navigation bar (Material 3 NavigationBar) with haptic feedback.
- UI: grouped quick settings card for Auto Failover and UDP Relay with silent state toggles.
- UI: dynamic latency pill badge with colored quality tiers (green < 100ms, amber < 200ms, coral >= 200ms).
- UI: traffic statistics redesign — full-width segmented Today/Month toggle, clean labeled metrics, removed recent sessions clutter, eliminated 1-second state jitter.
- UI: streamlined diagnostics dialog with clear non-technical terminology and outlined action buttons.
- UDP Relay: full support for SOCKS5 UDP ASSOCIATE over smux multiplexer with persistent NAT table on server and friendly UI toggles.
- CI: enabled mobilecore AAR caching and parallel Gradle builds.
- Core: raised core pin to OlCRTC Server `server-v2.0.0` at `c267dd3`.

## 1.4.17 — 2026-09-09

- Fix: restore tunnel MTU to 1500 to match Android VpnService MTU, fixing websites stalling or timing out due to packet drops on segments > 1400 bytes (TLS ClientHello with post-quantum ML-KEM/Kyber, large HTTP requests).
- Transport: remove spurious keepalive injection from eager VP8 writer loop, keeping RTP timestamps linear and avoiding SFU track stalls.
- Core pin raised to `Oleglog/Olcrtc_manager` at `3eaf8dd` (server-v1.9.79).

## 1.4.16 — 2026-09-08

- UI: modern compact floating navigation bar (66dp height, 24dp icons, 32dp pill indicator) with haptic feedback.
- UI: grouped quick settings card for Auto Failover and UDP Relay with silent state toggles.
- UI: dynamic latency pill badge with colored quality tiers (green < 100ms, amber < 200ms, coral >= 200ms).
- UI: traffic statistics redesign — full-width segmented Today/Month toggle, clean labeled metrics, removed recent sessions clutter, fixed 1-second state jitter.
- UI: streamlined diagnostics dialog with clear non-technical terminology and outlined action buttons.
- CI: enabled mobilecore AAR caching and parallel Gradle builds (release build under 5 minutes); output release as draft.

## 1.4.15 — 2026-09-08

- UDP relay: user-friendly UI toggle descriptions (removed technical server version requirements and error codes from strings), MTU lowered to 1400 in HEV tunnel config to prevent datagram fragmentation and dropped packets.
- Core pin raised to `Oleglog/Olcrtc_manager` at `a2adb35` (server-v1.9.78): optimized NAT table locking (IP parsing and DNS resolution outside table mutex), UDP session datagram counters logged on finish.

## 1.4.14 — 2026-09-07

- Fixed the UDP relay being dead on device (server pair: server-v1.9.77). Two root bugs in the relay protocol: the client shipped raw SOCKS5 datagrams with the source address as destination (the target lives inside the RFC 1928 datagram header), so the server dialed its own loopback and every datagram died; and the server returned at most one reply per datagram, which cannot carry RTP/QUIC streams. The core pin now carries the SOCKS5 datagram codec (fragment/truncation refusal, IPv4/IPv6/domain targets, reply re-wrapping) and a NAT table with persistent per-destination sockets, per-socket reply pumps, idle reaping and bidirectional 30 s keepalives so muted calls do not kill the relay.
- Diagnostics: the "olcRTC runtime" line now shows the effective `udp=on/off` state so a broken relay is visible in the exported log instead of apps silently falling back to TCP.

## 1.4.13 — 2026-09-07

- Speed: VP8 writer now drains queued KCP frames immediately instead of waiting for the next frame tick (port of the server-v1.9.76 eager-drain), and standard TCP/WS outbounds enable TCP Fast Open — one RTT less on every fresh connect.
- UDP relay (opt-in, server-v1.9.76+): new UDP toggle on the connection screen routes app UDP through the tunnel via SOCKS5 UDP ASSOCIATE; off by default, the server refuses with 0x07 while disabled. Latency probes use the same setting. (Relay itself was broken on device; the fix ships in 1.4.14.)
- Usability: VP8 fps/batch advanced fields replaced by a speed preset dropdown (Economy 30/8, Balanced 60/32, Maximum 120/64); existing raw values are preserved until edited.
- Core pin raised to the `Oleglog/Olcrtc_manager` fork at `2dc984e` (UDP ASSOCIATE relay + eager VP8 writer) via a same-module-path `replace`; legacy 32-byte VP8 transport ported to the new engine API.

## 1.4.12 — 2026-09-06

- Fixed in-app update failing with "SHA256SUMS.txt is missing" when the update check ran while the VPN was up: UpdateCheckWire packed only the selected APK across the AIDL boundary and dropped the rest of the release assets, so the installer could not find the checksum file. The full asset list now survives the VPN-proxy path.

## 1.4.11 — 2026-09-06

- Security (issues #49, #50, #51): hardware-backed keystore (StrongBox preferred, TEE fallback), clipboard handling warns about secrets, wipes only its own content after 60s and marks it sensitive on Android 13+; diagnostics redactor covers camelCase JSON/query keys and Go core `name=value` fields without touching plain prose.
- Compatibility (issue #52): profile editor filters transports by provider (telemost/wbstream → vp8channel, jitsi → datachannel), matching the server-side matrix.
- Reliability (issues #47, #48, #53): multi-host tunnel health probes (DNS+HTTPS, single TunnelHealthPolicy), front-door network check in Settings diagnostics, opt-in auto failover across profiles (2 attempts per profile, 1 cycle, favorites → last successful → rest).
- Removed stale authToken references from tests left by the earlier token cleanup.

## 1.4.10 — 2026-08-14

- Fixed "APK signing certificate mismatch" during in-app updates by auto-extracting the signing certificate SHA-256 from the built release APK and embedding it in release metadata. Updates no longer depend on a manually configured secret.

## 1.4.9 — 2026-08-14

- Core wire-format compatibility (legacy 32-byte vs current 36-byte VP8) is now auto-detected from the `core=` parameter the manager pins into each subscription URI, instead of a manual toggle in the profile editor. Pulling a QR from `Olcrtc_manager` sets `core=legacy` and heals to it on the next subscription refresh; `olcrtc-panel-lite` and bare URIs default to `current`. Removed the per-profile compatibility selector from the editor; existing stored values imported from the URI are preserved unchanged.

## 1.4.8 — 2026-08-13

- Fixed Android Jitsi staying stuck before MUC join on devices where `VpnService.protect(fd)` alone does not pick the working physical network after the TUN is up. The socket protector now also binds each carrier/Xray socket to the current underlying `Network` via `Network.bindSocket` on a duplicated fd (the original fd stays owned by Go), so config discovery, XMPP WebSocket and Colibri traffic follow the chosen physical route instead of the empty TUN route. The path is fail-closed: a failed protect, missing network or failed bind surfaces immediately as a `socket route bind failed` warning instead of the previous uninformative 30 s `config.js` timeout, and a one-shot `socket route protect+bind active` line confirms the route on the next device log.

## 1.4.7 — 2026-08-13

- Fixed Android Jitsi reconnect loops before MUC join by upgrading the official core and routing config discovery, XMPP WebSocket/BOSH, and Colibri WebSocket through the session's VPN-protected HTTP client. The Oleglog/j fork retains guest `anonymousdomain` handling and the earlier ICE-discovery URL fixes.

## 1.4.6 — 2026-08-13

- Jitsi ready timeout raised to 45 s (the same budget as WBStream) instead of the 15 s default. The Jitsi handshake is multi-stage (MUC join → Jingle session-initiate → bridge negotiation) and previously tripped false "start timed out" failures on slow rooms before the carrier reached ready.
- Rebuilt the bundled mobilecore against the Oleglog/j fork carrying two upstream ICE-disco fixes cherry-picked over the existing anonymousdomain handling: `a5b03af` normalizes ICE service URLs advertised over XEP-0215 disco, `9ac7664` rejects malformed colon ICE hosts. Wired through a `replace github.com/zarazaex69/j => github.com/Oleglog/j` in mobilecore's `go.mod` so CI `go mod tidy` resolves the fork; the anonymousdomain path for guest vhosts (e.g. `guest.meet.jit.si`) is preserved.

## 1.4.5 — 2026-07-31

- Re-importing a subscription via a bare `/open` deep link (the web "open in app" page, no mirror fields) no longer wipes the stored Yandex mirror. A repeated open used to overwrite `mirrorType/Url/Key` with null, dropping the only fallback that works when the primary server is down or blocked by allow-lists — now the stored mirror is preserved unless a fresh QR/bootstrap bundle supplies a new one.

## 1.4.2 — 2026-07-29

- Subscription refresh now races the primary host under a hard 2s deadline and fails over to the Yandex mirror within ~2s instead of waiting out the full HTTP timeouts when the primary is unreachable. Mitigated the long "stuck on primary" delay when the city-list subscription host is down.
- Lowered subscription HTTP connect/read timeouts from 15s to 5s; the primary payload is a small plain-text file.
- Bumped GitHub release retention from 5 to 15 published releases for rollback/regression access.

## Unreleased

- Added profile import, subscription parsing, multipart QR, GZIP bundle and mirror primitives.
- Added VPN lifecycle, native session rollback, routing presets and per-app routing storage.
- Added diagnostics redaction and local diagnostic log storage.
- Added connection session persistence and a basic statistics screen.
- Added GitHub release parsing and ABI-specific update asset selection primitives.
- Fixed Android CI issues around URI parsing, minSdk-compatible URL decoding, foreground service type, optional camera feature and package visibility lint.

## 1.4.1 — 2026-07-26

- Fixed "APK signing certificate mismatch" when updating in-app: the expected certificate digest is now normalized (colons and spaces stripped, lowercased) so both plain hex and keytool colon-separated formats are accepted. When no certificate SHA-256 is configured, the check falls back to comparing against the currently installed app's own signing certificate instead of skipping the check entirely.

## 1.4.0 — 2026-07-26

- Added a download progress bar to the in-app APK update dialog, showing percent and MB transferred.
- All Standard (VLESS/VMess/Trojan/SS) profiles are now pinged simultaneously instead of in sequential batches of four; olcRTC profiles remain sequential.

## 1.3.9 — 2026-07-26

- Reduced tunnel health probe interval from 60 s to 180 s to lower idle background traffic through the VPN tunnel.
- Refreshed the VPN connections list immediately on tab resume so newly added subscriptions and QR connections appear without restarting the app.

## 1.3.8 — 2026-07-23

- Preserved fast WBStream carrier authentication failures during native readiness checks so fatal errors stop automatic reconnect loops instead of being replaced with `mobilecore is not running`.

## 1.3.7 — 2026-07-23

- Rebuilt the bundled mobilecore AAR from official olcRTC commit `42ae4e0c6a1a`, including its isolated control-plane KCP session for current VP8 connections.
- Forced release builds to compile mobilecore from the pinned source even when a cached AAR exists.
- Added CI and release checks that verify every bundled `libgojni.so` uses the pinned official core and contains no legacy fork dependencies.

## 1.3.6 — 2026-07-22

- Updated the bundled official olcRTC core to commit `42ae4e0c6a1a` and removed the client fork replacements.
- Added a per-profile `current` / `legacy` compatibility selector for the 36-byte and 32-byte VP8 wire formats.
- Migrated existing local and subscription profiles to `legacy` while new profiles default to `current`.
- Added the compatibility mode to exported olcRTC URIs, subscription persistence and diagnostics.
- Added GitHub Actions validation for the native core, dependency graph, Android unit tests, lint, APK assembly and instrumentation tests.

## 1.3.5 — 2026-07-20

- Refined the full client UI with edge-to-edge layouts, a calmer wordmark, consistent cards and a centered four-item bottom navigation.
- Reworked connection selection into a vertical list: selecting a profile never reconnects an active VPN, and the primary action explicitly switches to a different profile.
- Added a compact “test all” action with parallel checks for standard profiles, sequential carrier checks and real per-card latency/unavailable states.
- Added independent System, Neutral, Bronze, Black and Monochrome palettes, accent colors, a soft connection glow slider and Clean/Glow/Drift atmosphere controls.
- Improved statistics, subscription loading/error states, app selection spacing and adaptive/notification icons.

## 1.3.3 — 2026-07-20

- Replaced horizontal connection cards with a compact vertical list and corrected bottom-navigation sizing, labels and optical icon alignment.
- Added real parallel latency checks for up to four standard profiles, with sequential checks for olcRTC carriers and live results in each card.
- Prevented profile taps from reconnecting an active VPN automatically and kept the connected-session ping as a separate action.

## 1.0.0

Initial public release target. Not released yet.

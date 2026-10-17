# OlConnect Android Client v1.6.7 Release Notes

## Summary of Changes

### 1. New OpenFlux Transports Added to UI and Engine
- Added full support for all covert transports in the OpenFlux profile editor and runtime:
  - **Авто (автоопределение)**: Automatically identifies transport based on URL patterns.
  - **Яндекс Документы (Волга)** (`vyandex`): Yandex Docs Volga collaborative editor with built-in SmartCaptcha SHA-256 PoW challenge solver.
  - **Яндекс Документы (Классический)** (`yandex`): ONLYOFFICE collaborative editing transport with automatic fallback to Volga if `officeActionData.balancer_url` is missing.
  - **Mail.ru Документы** (`mailru`): Modern chat-channel (`OFX1`) transport with automated CSRF token retrieval (`fetchMailruCSRFToken`) and make-before-break rotation to eliminate 403 token and 4004 access deny disconnections.
  - **Яндекс Телемост** (`telemost`): Tunnels packet frames through Yandex Telemost conference rooms over WebRTC VP8 data/video channels.
  - **ЧУПС Онлайн (cups.online)** (`cupsonline`): Tunnels packets through cups.online live coding interview rooms with multi-lane `StripedTransport` reliability.
  - **Битрикс24** (`bitrix24`): Tunnels packets through Bitrix24 portal chat channels (`*.bitrix24.ru`).
  - **Авито** (`avito`): Tunnels packets through Avito live chat channels with `StripedTransport`.

### 2. Core Fixes for Connection Stability
- **Mail.ru infinite spinner fixed**: Previously, `mailru_docs.go` failed to retrieve CSRF tokens and received HTTP 403 on session initialization, causing the client to hang indefinitely while the server never saw incoming traffic. Now automatically retrieves CSRF tokens and uses `OFX1` chat messaging.
- **Yandex Volga connection drops fixed**: Previously, Volga failed when Yandex served SmartCaptcha challenges to mobile or datacenter IPs, causing client timeouts and profile failovers. Now includes embedded automated Proof-of-Work solver to solve SmartCaptcha in milliseconds.
- **OpenFluxUri serialization & roundtrip**: Expanded `openflux://` URI parsing and serialization to seamlessly encode and decode all transport types.

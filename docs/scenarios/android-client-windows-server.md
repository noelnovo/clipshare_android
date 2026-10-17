# Android Client → Windows server

## Wi-Fi Direct with mTLS

1. Windows: daemon 2.2.0+, `[wifi_direct].enabled = true`,
   `[server].bind = "0.0.0.0"`, `[tls].enabled = true`,
   `[tls].verify_hostname = false`.
2. Windows: issue `pixel-9 --type client`, export `.p12` + QR PNG.
3. Pixel: **Client**, host blank, port `40403`, **Wi-Fi Direct** on,
   **TLS (wss)** on.
4. Import QR with **Scan client secret** or `.p12` with
   **Select client secret** first.
5. Wi-Fi radio on, mobile data may stay on, no AP join needed.

Server details: see server repo
`docs/scenarios/windows-android-wifi-direct-mtls.md`.

## LAN

Same TLS identity flow. For plaintext LAN testing only, turn both server TLS
and Client TLS off. Android refuses Wi-Fi Direct plaintext.

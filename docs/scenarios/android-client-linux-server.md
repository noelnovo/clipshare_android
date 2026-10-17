# Android Client → Linux server

Linux must form the P2P group with OS tools first. Daemon binds `0.0.0.0`,
`[wifi_direct].enabled = true`, `[tls].enabled = true`,
`verify_hostname = false` for mTLS.

Issue `pixel-9 --type client` on Linux, export QR/`.p12`, import on Pixel,
enable **Wi-Fi Direct** + **TLS (wss)**. Server details: server repo
`docs/scenarios/linux-android-wifi-direct.md`.

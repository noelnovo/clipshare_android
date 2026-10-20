# Android Client → Windows server

## Wi-Fi Direct with mTLS

1. Windows: daemon 2.2.0+, `[wifi_direct].enabled = true`,
   `[wifi_direct].role = "host"`, `[server].bind = "0.0.0.0"`,
   `[tls].enabled = true`, `[tls].verify_hostname = false`.
2. Windows: issue `pixel-9 --type client`, export `.p12` + QR PNG.
3. Pixel: choose **App mode → Wi-Fi Direct**.
4. Import the Windows bundle with **Scan certificate/key QR** or
   **Import certificate/key** under **Trusted Certificates/Keys**, then name it
   (for example `Windows desktop`).
5. Wi-Fi radio on, mobile data may stay on, no AP join needed. Start sync:
   the phone hosts/waits and lists `DESKTOP-...` under **Nearby devices**.
6. Tap `DESKTOP-...` to connect, or let **Auto-connect** reconnect to the last
   used Windows host. The role is automatic; there is no host/client switch.

Server details: see server repo
`docs/scenarios/windows-android-wifi-direct-mtls.md`.

## LAN

Same TLS identity flow. For plaintext LAN testing only, turn both server TLS
and Client TLS off. Android refuses Wi-Fi Direct plaintext.

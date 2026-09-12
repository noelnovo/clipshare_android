# ClipShare (Android)

ClipShare keeps your clipboard in sync across devices on your local network. It speaks a simple
JSON-over-WebSocket protocol and can interoperate with the ClipShare Go desktop daemon or with
other ClipShare Android devices.

## What it does

Every device runs in one of two roles:

- **Client** — connects to a server (a desktop daemon or another phone) and pushes/pulls
  clipboard content to/from it.
- **Server** — accepts connections from clients and relays clipboard content between them.

Copy something on any connected device and it becomes available on every other connected device —
text and images alike. The app runs a foreground service so it keeps working in the background, and
an optional accessibility service lets it capture copies made in *other* apps while the phone is
not in the foreground.

Discovery works automatically over your LAN using mDNS (`_clipshare._tcp`) and UDP beacon
announcements, so there is normally no IP address to type in. Wi-Fi Direct can also be enabled for
direct Android-to-Android sharing when no router is available.

## Workflows

### Desktop → Phone (phone as Client)

The classic setup: a Go daemon runs on a desktop machine, and your phone joins it as a client.

1. On the phone, open **Settings → App mode → Client**.
2. Leave **Discovery** enabled (default). The phone will advertise on mDNS/UDP and list the desktop
   daemon under **Devices** on the home screen.
3. Tap the discovered device to connect, or set **Server (IP or hostname)** + **Server port**
   manually in **Settings**.
4. Optional: if the daemon uses a shared **token** or **TLS**, configure those too (see
   `docs/configuration.md`).

Now copying on the desktop lands on the phone's clipboard and vice-versa.

### Phone → Phone (one phone as Server)

With two (or more) Android devices, designate one as the server and the rest as clients.

1. On the server phone, open **Settings → App mode → Server**. It will listen for connections and
   advertise itself over mDNS/UDP beacons.
2. On every client phone, open **Settings → App mode → Client**. Enable **Discovery** (default)
   and tap the server phone when it appears under **Devices**.
3. If discovery does not find it (e.g. mDNS is blocked on your network), enter the server phone's
   IP and the **Server port** manually.

Anything a client copies is relayed through the server phone to every other connected client.

> Server mode also works with a desktop daemon as the client, if the daemon supports connecting to
> a remote server — the wire protocol is the same.

### Mixed / multiple devices

Any number of clients can connect to one server. Devices do not need to be the same platform: a
desktop daemon and several phones can all join the same server.

## Features

- **Two-way clipboard sync** — copy on one device, paste on the other
- **Images** — images copied on one device arrive as clipboard images on the other (paste in
  WhatsApp, Google Docs, Gmail, ...)
- **Zero-config setup** — discovers servers automatically on your LAN
- **Auto-discovery** — mDNS (`_clipshare._tcp`) and UDP beacon announcements (port `40404`); the
  announced `tls` flag switches the client to `wss` automatically
- **Server mode** — a phone can act as the hub for phone-to-phone sync
- **Wi-Fi Direct** — optional Android-to-Android discovery and sharing without a router
- **Foreground service** — keeps the connection alive and writes incoming clipboard content in the
  background
- **Quick Settings tile** — toggle sync from the notification shade
- **Manual connect** — fall back to entering a host and port by hand
- **Clipboard history** — recent items are stored locally and shown in the app (clearable)
- **Mutual TLS** — import the `.p12` the desktop exports (`clipshare cert export`) or scan the single
  QR a server shows (`**Show client cert QR**`): it carries a fresh client certificate **and** the
  server CA, so one scan installs the cert for `wss` and trusts the server. Server TLS always
  requires a client certificate signed by the server's CA (mutual TLS). Hostname verification is on
  by default; turn it off in Settings to trust the CA only, so connections keep working across
  wifi/DHCP changes with no re-import.
- **Whitelist mode** — mirrors the daemon's `connection.mode = "whitelist"`: no scanning, only the
  listed IPs, and the server identity is verified against the whitelist entry
- **Background capture** — an accessibility service (optional, opt-in) lets copies made in *other
  apps* sync while the app is in the background, by reading the selected text from accessibility
  events (Android 10+ forbids background clipboard reads, so this is best effort for text)

## Requirements

- Android 8.0 (API 26) or newer
- At least one server on your network: a ClipShare desktop daemon, or another ClipShare device in
  **Server** mode

## Building

Requires JDK 17 and the Android SDK (compileSdk 36).

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## Running

Install the APK on your device and open the app. On first launch it will ask for notification
permission (needed for the background sync service). Then either:

1. Tap a discovered device from the list to connect, or
2. Enter the server's address manually in **Settings** (leave it empty to rely on discovery).

See [docs/configuration.md](docs/configuration.md) for every setting explained.

## Protocol

The app talks to the daemon via WebSocket at `ws://<host>:40403/ws` (the port is discoverable from
mDNS/beacons). When mutual TLS is enabled the URL becomes `wss://` and a client certificate is
presented. The server cert is validated against the imported CA; the dialed IP is not compared to
the cert's SANs, so a device stays reachable after a network change. Messages are JSON objects
with a `type` and `data` field:

- `hello` — client announces itself on connect (`name`, `platform`, `version`)
- `clipboard` — a text payload with `text`, `ts`, and `from`, or an image payload with `data`
  (base64 PNG/JPEG), `mime`, `ts`, and `from`
- `file` — a file share with `name`, `data` (base64), `mime`, `size`, `ts`, and `from`
- `ping` / `pong` — keepalive
- `error` — server-side error (`code`, `msg`)

An optional `?token=` query parameter is appended when a token is configured.

## Notes

- Android 10+ restricts reading the clipboard to the foreground app (and since Android 11 even an
  enabled accessibility service is no longer exempt), so copies made in *other* apps are only pushed
  in the background via the optional accessibility service, which reads the selected text from
  accessibility events rather than the clipboard (best effort). Incoming clips from the network are
  written by the service and always work.
- `android:usesCleartextTraffic` is enabled for plain `ws://` traffic on the LAN.
- Logs and clipboard history are stored locally and trimmed to stay within configurable size
  limits (logs in Settings → Advanced; history in Settings → History).

## License

Not specified yet.

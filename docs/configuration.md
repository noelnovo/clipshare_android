# ClipShare configuration

This document explains every setting in the ClipShare Android app and the behavior behind it.

The app works in one of two roles — **Client** or **Server** — chosen in
**Settings → General → App mode**. All settings sections (**General**, **Client**, **Server**,
**Advanced**, **History**) are always visible regardless of the mode; irrelevant settings are simply
ignored while that mode is inactive.

---

## How devices find each other

Devices advertise themselves and are discovered over your LAN using two mechanisms:

| Mechanism | Defaults |
| --- | --- |
| mDNS | service type `_clipshare._tcp` |
| UDP beacons | broadcast to `255.255.255.255:40404` every ~1 s |
| Server listen port | `40403` (`ws://<host>:40403/ws`) |

When **Wi-Fi Direct** is enabled in Settings, Android first searches for
ClipShare's Android P2P service and then can search for a Windows Wi-Fi Direct
peer. If exactly one peer is visible, the app selects it automatically; if
several are visible, enter the Windows computer name (run `hostname` on Windows)
in Client settings to select the intended peer. Connecting forms a direct
WPA2-protected group; the Pixel can keep mobile data as its internet connection
while ClipShare sends clipboard traffic over the P2P link. Android may request
Nearby devices permission (and location permission/location services on older
Android versions). LAN discovery remains available independently.

See [connection scenarios](connection-scenarios.md) for Windows/Linux server,
Android server, Wi-Fi Direct, TLS, and plaintext setup steps.

Discovery is on by default, so in most cases there is nothing to configure: a client shows servers
under **Devices** on the home screen and you tap one to connect. The beacon advertises the server's
port and whether it uses TLS; the client switches to `wss` automatically when the server announces
TLS.

The **Server (IP or hostname)** field is *not* required: leave it empty for pure discovery. If it is
filled, the client connects to that host first and only falls back to discovered servers when that
host is unreachable. Tapping a device in the list is always ephemeral — it never rewrites the
saved host field.

---

## General

### Device name

The name this device presents when it connects (`hello` message) or when it serves others. Defaults
to the device model (e.g. `Pixel 9`). Other devices see this name in their device list and history.

### App mode

- **Client** — this device connects to a server (desktop daemon or another phone).
- **Server** — this device listens for clients and relays clipboard content between them.

Changing the mode takes effect when you **Save**; if sync is running it is restarted automatically.

---

## Client

Settings used when this device is a **Client**.

### Server (IP or hostname)

The address of the server to connect to. A hostname works too, as long as it resolves on your
network. **Leave empty for pure discovery**; when filled, the client prefers this host and only
falls back to discovery when it is unreachable. This is not a fallback list — it is a *preferred*
target.

### Wi-Fi Direct peer name (Windows)

When connecting directly to the Windows daemon over Wi-Fi Direct, leave this
blank to connect automatically when exactly one P2P peer is nearby. If multiple
peers are visible, enter the Windows computer name (the output of `hostname` in
Command Prompt) to select it. Leave the Server (IP or hostname) field blank.
After the group forms, the client connects to the configured server port over
the Wi-Fi Direct network.

### Server port

The TCP port of the server's WebSocket endpoint. Default `40403`. Used both for manual connects and
as the port to dial when a whitelist entry has no port.

### Shared token (optional)

A shared secret, if the server requires one. When set, it is appended to the WebSocket URL as
`?token=...`. The server rejects connections whose token does not match. This is separate from the
**Server token** that a server in this app requires from its own clients.

### Discovery

Default **on**. Turns mDNS + UDP beacon scanning on/off. Disable it if you only ever connect
manually or via whitelist and do not want the scanning traffic.

### TLS (wss)

Default **off**. LAN discovery follows the server's advertised TLS setting.
Manual targets and Windows Wi-Fi Direct peer fallback use this switch to select
`wss://`. Wi-Fi Direct refuses to connect unless TLS is enabled and a client
certificate issued by the target server's CA is imported. A CA certificate
alone is not a client identity.

### Client certificates

`.p12` bundles (exported by the desktop daemon's `clipshare cert export`, or received from an
Android server's client-cert QR). Every imported certificate is offered during the TLS handshake,
so the server can select the one signed by its own CA (mutual TLS). The CA inside a `.p12` is
trusted automatically — there is no separate "trusted CA" list to maintain.

Certificates can be imported two ways:

- **Import .p12** — pick a `.p12` file from the device.
- **Scan QR certificate** — scan the single QR code a server shows (**Show client cert QR**). The QR
  encodes a compact gzip-compressed bundle holding the private key, the leaf certificate **and** the
  issuing CA. One scan installs the client certificate for mutual TLS **and** auto-trusts the CA —
  there is no separate CA QR and no extra trust step. The app rebuilds the `.p12` locally.

---

## Server

Settings used when this device is a **Server**.

### Server port

The port the built-in server listens on (`40403` by default). Clients must use the same port unless
they learn it from a beacon.

### Server token (optional)

A shared secret that clients must include as `?token=...` when connecting. Blank means no token is
required. This is separate from the **Shared token** a client sends.

### Bind address

Whether the server listens on **Any**, **IPv4** only, or **IPv6** only. Defaults to **Any**.

### TLS (wss)

Default **off**. When enabled, the app generates a local CA + server certificate pair and serves
`wss://` instead of `ws://`. **Mutual TLS is always required**: the server accepts only client
certificates signed by its own CA, so a connecting device must import a client certificate issued
by this server (via the client-cert QR below) or trust this server's CA. The certificate is not
bound to any particular LAN IP, so it keeps working across wifi/DHCP changes.

Certificate management:

- **Regenerate cert** — replaces the stored CA + server certificate pair (e.g. to rotate before
  expiry). Re-issue client certificates afterwards.
- **Copy CA** — copies the CA certificate (PEM) to the clipboard so you can paste it into a file.
- **Share CA file** — sends the CA certificate to another app/device as a file.
- **Show client cert QR** — generates a *fresh* client certificate signed by this server's CA, along
  with the CA itself, and shows both as a single QR code. Scanning it with another device's **Scan QR
  certificate** installs the client certificate **and** trusts this server's CA in one step. Each tap
  issues a new key.

> The server-side certificate password is fixed (`clipshare`) and used only to protect the local
> PKCS#12 store; it is not a connection credential.

### Client certificate retention

Client certificate bundles are only used to authenticate to *other* servers, so they have no value
while this device runs as a server. When the device is in server mode, its stored client
certificates are **deleted after 7 days** (counted from the last time server mode was saved). While
they are still present, the settings screen shows a warning with the deletion date; a client that
wants to connect over TLS must scan a fresh client-cert QR from this server's screen.

---

## Wi-Fi Direct with mutual TLS

Wi-Fi Direct supplies a direct WPA2-protected link; mTLS separately authenticates
both ClipShare endpoints and encrypts the WebSocket traffic. The Android Client
TLS switch is global, so enabling it also secures LAN connections from this
client.

### Android Client → Windows or Linux server

1. On the Windows/Linux server, create a private CA once and issue the server
   identity and a client identity for the Pixel. On Linux, run these commands
   as the daemon user; on Windows, run them in a normal PowerShell session:

   ```sh
   clipshare cert init                                  # skip if a CA already exists
   clipshare cert issue --name desktop --type server
   clipshare cert issue --name pixel-9 --type client
   clipshare cert qr --name pixel-9 --type client --out pixel-9-client.png
   ```

   Keep the CA private key on the server. Share only the client-cert QR or
   exported client `.p12` with the Pixel. The Windows installer creates these
   artifacts on a fresh install; the QR is saved under
   `%APPDATA%\clipshare\certs\android-client.png`.
2. Enable `[tls].enabled = true` in the desktop daemon config and restart it.
   For a dynamic Wi-Fi Direct group-owner address, set
   `[tls].verify_hostname = false`; this still validates the certificate chain
   against the CA. On Linux, first create the P2P group with the OS networking
   tools; the daemon advertises on the resulting interface.
3. On the Pixel, use **Settings → Client → Scan client secret** to scan the QR
   (or **Select client secret** to import the `.p12`), enable **TLS (wss)** and
   **Wi-Fi Direct**, leave the manual server host blank, and set the optional
   Windows peer name only if multiple P2P peers are nearby.

The server presents its server certificate and requires the Pixel's
CA-signed client certificate. The Android client trusts the CA bundled with
the imported credential and presents the client identity during the TLS
handshake.

### Android Client → Android Server

1. Set one phone to **Server** mode, enable **Wi-Fi Direct** and **Server TLS**,
   and save. The server generates its CA and server identity.
2. On that server phone, open **Settings → Server → Show client secret**.
3. On the other phone, select **Client** mode, enable **Wi-Fi Direct** and
   **TLS (wss)**, then use **Scan client secret** to scan the server phone's QR.
   Leave the manual server host blank and start sync.

The QR contains a fresh client private key, a client certificate issued by the
Android server's CA, and that CA certificate. The server accepts only clients
issued by its own CA. Regenerating the Android server's CA requires issuing and
sharing new client credentials.

> **Plaintext policy status:** TLS-enabled servers already reject clients
> without an acceptable client certificate. The Android client is being updated
> to refuse Wi-Fi Direct endpoints that do not negotiate mTLS; until that
> transport-specific guard is shipped, leave TLS enabled on both sides for
> every Wi-Fi Direct session.

---

## Advanced

Settings that most users never touch.

### Clipboard poll interval (ms)

How often the foreground clipboard watcher re-checks the clipboard (default `700`, clamped to
`200`–`10000`). Lower is more responsive; higher uses less battery.

### Beacon port

The UDP port the server announces on (`40404` by default) — this is the *listening* port the client
also opens to receive beacons. Only change it if you have changed the port on every server you use.

### Connection mode

- **Discover** (default) — scan via mDNS/beacons and connect to whatever shows up (subject to the
  host-field preference above).
- **Whitelist** — do not scan. Try each whitelisted IP in turn until one connects, and verify the
  server's announced name against the whitelist entry.

#### Whitelist entries

Each entry has an optional **Name** and an **IP**. Matching is on name *or* IP:

- The client dials `whitelist-IP : server-port`.
- When the server announces its name after connecting, the client checks it against the entry.
  An entry with a blank name accepts any server at that IP; an entry with a name rejects a server
  that announces something different.
- After a failed attempt the client waits ~1.5 s and advances to the next candidate.

### IP version

Filter discovered servers by address family (**Any**, **IPv4**, **IPv6**). Manual entries are never
filtered.

### Verify hostname

Default **on** (strict). When enabled, the server certificate's SANs must match the dialed
address. Turn it off to verify the server certificate against the trusted CA only — hostname/IP-
address matching is skipped, keeping connections working when a device moves to another wifi/DHCP
network; there is no need to regenerate or re-import certificates when the LAN address changes.

### Enable logs

Default **on**. Controls whether the **Logs** screen and bottom-navigation entry are shown. Logs are
still recorded in the background when this is off; you just do not see the screen. The
**Max log file size (KB)** setting below only appears when logs are enabled.

### Max log file size (KB)

Upper bound for the on-disk log file (default `256`, allowed `16`–`4096`). When appending a new log
line would exceed the limit, the oldest lines are dropped to make room. The in-memory view is
additionally capped at 500 lines.

---

## History

### Max history entries

Upper bound for the local clipboard/command history (default `50`, allowed `10`–`1000`). The newest
entries are kept; the oldest are dropped as new ones arrive. History is stored as JSON in the app's
SharedPreferences and is independent of the log file.

---

## Background capture

By default, Android 10+ only lets the *foreground* app read the clipboard, and since Android 11 the
platform no longer exempts accessibility services from that rule (`ClipboardService` only allows
the focused app, the default IME, or apps with privileged permissions). So on Android 10+ a normal
app **cannot read the clipboard in the background** — a background read is refused by the system,
visible in logcat as `ClipboardService: Denying clipboard access to ...`.

Enable **Background capture** in **Settings → Background capture** (opens the system Accessibility
settings) to let the app's accessibility service capture copies made in other apps anyway:

- **Android 10+** — the service reads the text out of `TYPE_VIEW_TEXT_SELECTION_CHANGED`
  accessibility events, which carry the source field's text and the selected range. No clipboard
  read is involved, so this is not blocked. It is *best effort*: it treats "selected" as "copied",
  works reliably in standard text fields, and is less reliable in WebViews/Chrome. Images cannot
  be captured in the background on modern Android at all.
- **Android 9 and below** — background clipboard reads are unrestricted, so the service falls back
  to polling the clipboard periodically (~0.7 s), which also covers images.

In both cases the service only pushes when the sync service is connected, and it skips while the
ClipShare app itself is in the foreground (the built-in foreground watcher already covers that).
Deduplication is shared between the foreground watcher and the accessibility service, so the same
text is never sent twice when the app moves between foreground and background.

Some devices disable accessibility services after a reboot — re-enable it if background capture
stops working.

---

## Diagnostics

- The **Logs** screen (hidden unless **Enable logs** is on in Advanced) shows the last log lines,
  with **Clear** (wipe the log) and **Share** (export the log to another app). Logs are useful to
  troubleshoot connection or clipboard issues.
- **Clear history** on the home screen wipes the local clipboard/command history.

---

## Quick Settings tile & notification

- Add the **ClipShare** tile to the notification shade to start/stop sync without opening the app.
  The tile shows **Connected** / **Idle** state.
- The foreground service posts an ongoing notification ("ClipShare sync"). It reflects the current
  state: connecting, connected, reconnecting, server running with N clients, etc. Android shows the
  notification while the service runs; it is required for background operation.

---

## Behavior notes

- **Loopback protection** — content that arrives from the network is written to the local clipboard
  but never sent back out, so there is no echo between devices.
- **Deduplication** — each copy is pushed at most once, regardless of whether it is first observed
  by the foreground watcher or the accessibility service.
- **Images** — images are downscaled and compressed so the resulting base64 JSON payload stays
  within a size budget (default ~10 MB). JPEGs are progressively re-compressed; large PNGs fall
  back to JPEG. On arrival, images are written to the clipboard as file URIs.
- **Reconnect** — the client reconnects with exponential backoff (1 s → 10 s) and sends a keepalive
  ping every ~30 s. If the server *rejects* the connection (e.g. a wrong shared token, close code
  1008), the client stops retrying and shows the server's reason as an error instead of looping.
- **Cleartext** — plain `ws://` on the LAN is allowed (`android:usesCleartextTraffic`), which is
  required for unencrypted sync. For anything sensitive, use TLS (with mutual TLS on server mode).

---

## Defaults summary

| Setting | Default |
| --- | --- |
| App mode | Client |
| Server port | 40403 |
| Beacon port | 40404 |
| mDNS service | `_clipshare._tcp` |
| Discovery | on |
| Connection mode | Discover |
| TLS (wss) | off |
| Verify hostname | on |
| Token | empty |
| Server token | empty |
| Enable logs | on |
| Max log file size | 256 KB (16–4096) |
| Max history entries | 50 (10–1000) |
| Clipboard poll interval | 700 ms (200–10000) |
| Image payload budget | ~10 MB (internal) |
| Background capture | off (opt-in) |

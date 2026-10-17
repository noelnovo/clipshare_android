# ClipShare connection scenarios

ClipShare uses a client/server WebSocket protocol over either a LAN or a
Wi-Fi Direct link. The link itself is bidirectional, but ClipShare still needs
one endpoint listening as the **Server** and another initiating as the
**Client**. After the WebSocket opens, both sides can send clipboard data.

## Per-pairing guides

- [Android Client → Windows server](scenarios/android-client-windows-server.md)
- [Android Client → Linux server](scenarios/android-client-linux-server.md)
- [Android ↔ Android](scenarios/android-android.md)

## Android Client → Windows server over Wi-Fi Direct

1. On Windows, run the ClipShare daemon. Version 2.2.0 and later advertises a
   Wi-Fi Direct group-owner peer; the WebSocket listener is on port `40403`.
2. On the Pixel, enable the Wi-Fi radio while leaving mobile data enabled. Do
   not join a Wi-Fi access point for the direct-link test.
3. In ClipShare **Settings → Client**, select **Client**, leave **Server (IP or
   hostname)** blank, set port `40403`, and enable **Wi-Fi Direct**. The peer
   name can be blank when only the Windows PC is nearby; it is used to select
   the right peer if several are found.
4. Start sync. After the phone reports a connection, use the main screen's
   **Text to push → Send to server** controls for a simple plain-text test.

Windows and Android must use the same TLS setting. For a secure setup, follow
the mTLS steps below before connecting. On a fresh Windows installer install,
the installer creates a server CA/certificate and a client QR at
`%APPDATA%\clipshare\certs\android-client.png`. Upgrades preserve an existing
config and do not switch TLS on automatically.

## Android Client → Linux server over Wi-Fi Direct

The Linux daemon does not create a P2P group. First use Linux's Wi-Fi stack or
another platform tool to form/join a Wi-Fi Direct group and bring up the P2P
interface. Configure the daemon to listen on all addresses (`server.bind =
"0.0.0.0"`) and advertise on that P2P interface. Then follow the Android
Client steps above. The optional peer name may be needed if the phone sees
multiple P2P devices.

## Android Client → Windows/Linux server on a LAN

Use Client mode and leave discovery enabled. Configure the server TLS setting
and Android Client TLS setting consistently. For mTLS, import a client
certificate issued by the server; a CA certificate by itself is not a client
identity. The Android Client TLS switch is global and therefore also secures
LAN connections when enabled.

## Android Server ↔ Android Client over Wi-Fi Direct

1. On the server phone, select **Server** mode, enable **Wi-Fi Direct**, and
   enable **Server TLS** for mTLS. Save and start sync; Android creates the
   group and listens on port `40403`.
2. On the client phone, select **Client** mode, enable **Wi-Fi Direct**, and
   enable Client TLS for mTLS. Leave the server host blank and start sync.
3. For mTLS, scan **Settings → Server → Show client secret** on the client with
   **Settings → Client → Scan client secret**. The QR issues a fresh client
   identity signed by the server phone's CA and imports that CA for server
   verification.

The client can send text to the server, and the server can broadcast clipboard
updates back to connected clients. For a plaintext test, leave both Server TLS
and Client TLS off and use a LAN. The Android app refuses a Wi-Fi Direct
ClipShare connection without mTLS.

## Android Server with desktop clients

An Android phone in Server mode can accept multiple WebSocket clients and relay
clipboard updates between them. This works over a LAN. A Windows daemon's
current built-in Wi-Fi Direct role is group owner; it does not automatically
join an Android-created group as a P2P client. A Linux desktop must join the
Android group using its OS networking tools and configure the daemon's static
`peers` address. Use a desktop-to-Android mTLS identity issued by that Android
server if the desktop is configured as an mTLS client.

## Desktop ↔ desktop

Windows/Linux daemons can relay to configured `peers` over a LAN. For mTLS,
both daemons must trust the same CA and have server/client-capable identities.
Windows-to-Windows or Windows-to-Linux Wi-Fi Direct group negotiation is not
fully automated: Windows currently advertises as a group owner, while Linux
group setup is OS-managed. The server repository documents these in
`docs/connectivity-scenarios.md`.

## mTLS requirements and plaintext

mTLS requires **both**:

- the server's `tls.enabled = true` (desktop config) or **Server TLS** (Android
  Server mode), which makes that server require a CA-signed client certificate;
- Client TLS enabled in the Android app and a client `.p12`/QR imported from
  that server's CA.

For Windows/Linux servers, see the server repository's
`docs/configuration.md` section **Mutual TLS (mTLS)** and
`docs/connectivity-scenarios.md`.
For an Android Server, its **Show client secret** QR is the issuance mechanism.
Disable hostname verification for dynamic P2P IPs, while retaining CA chain
verification.

For LAN discovery, the app follows the server's TLS advertisement. The Client
TLS switch controls manual targets and Windows peer fallback. Android 1.1.6 and
later fails closed for Wi-Fi Direct unless the endpoint uses TLS and the client
has an imported client certificate. Do not treat WPA2 link encryption as a
replacement for mTLS.

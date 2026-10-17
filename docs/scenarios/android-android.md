# Android ↔ Android

One phone Server, one Client.

## Wi-Fi Direct with mTLS

Server: **Server** + **Wi-Fi Direct** + **Server TLS**.
Client: **Client** + **Wi-Fi Direct** + **TLS (wss)**.
Scan server **Show client secret** with client **Scan client secret**.

## LAN plaintext test

Both TLS off, same LAN. Wi-Fi Direct plaintext is refused by Android 1.1.6+.

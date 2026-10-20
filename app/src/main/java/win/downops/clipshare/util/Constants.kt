package win.downops.clipshare.util

/**
 * Application-wide constants. Values that affect behavior and may need to
 * match the desktop daemon (ports, service type, protocol strings) live here.
 * File-local constants are kept in their respective files.
 */
object Constants {
    object Discovery {
        const val SERVICE_TYPE = "_clipshare._tcp"
        const val DEFAULT_SERVER_PORT = 40403
        const val DEFAULT_BEACON_PORT = 40404
        const val DATAGRAM_BIND_ADDR = "0.0.0.0"
        const val BEACON_BROADCAST_ADDR = "255.255.255.255"
        const val BEACON_INTERVAL_MS = 1_000L
    }

    object Protocol {
        object Msg {
            const val HELLO = "hello"
            const val CLIPBOARD = "clipboard"
            const val PING = "ping"
            const val PONG = "pong"
            const val ERROR = "error"
        }

        object Content {
            const val TEXT = "text"
            const val IMAGE = "image"
        }

        object Scheme {
            const val WS = "ws"
            const val WSS = "wss"
        }

        const val WS_PATH = "/ws"
        const val PLATFORM_ANDROID = "android"
        const val PLATFORM_DESKTOP = "desktop"
    }

    object WebSocket {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val OPEN_TIMEOUT_MS = 15_000L
        const val PING_INTERVAL_MS = 30_000L
        const val BACKOFF_INITIAL_MS = 1_000L
        const val BACKOFF_MAX_MS = 10_000L

        /** RFC 6455 close code used by servers to reject a connection (e.g. token mismatch). */
        const val POLICY_REJECTION_CODE = 1008

        /** Error shown when the server rejects the connection; the server's reason is appended. */
        const val CONNECTION_REJECTED = "Connection rejected by server"
    }

    object Whitelist {
        const val RETRY_DELAY_MS = 1_500L
    }

    object Network {
        const val MIN_PORT = 1
        const val MAX_PORT = 65535
    }

    object Clipboard {
        const val SYNC_POLL_MS = 700L
        const val MIN_POLL_MS = 200L
        const val MAX_POLL_MS = 10_000L

        /**
         * Label used for clipboard items written by the app itself (received
         * remote content, history copy-back). The capture paths skip clips with
         * this label so the app does not echo its own writes back to peers.
         */
        const val INTERNAL_CLIP_LABEL = "clipshare_internal"
    }

    object Mime {
        const val IMAGE_PNG = "image/png"
        const val IMAGE_JPEG = "image/jpeg"
        const val IMAGE_JPG = "image/jpg"
        const val GENERIC = "application/octet-stream"
    }

    object Image {
        const val DEFAULT_MAX_PAYLOAD_KB = 10240
        const val MIN_MAX_PAYLOAD_KB = 8
        const val MAX_MAX_PAYLOAD_KB = 2048
        const val MAX_DIMENSION = 1280
        const val MIN_DIMENSION = 320
        const val COMPRESSION_ATTEMPTS = 5
        const val JPEG_QUALITY_START = 90
        const val JPEG_QUALITY_STEP = 10
        const val JPEG_QUALITY_MIN = 30
        const val BASE64_OVERHEAD_FACTOR = 0.75
        const val DECODE_MAX_DIMENSION = 2048

        // Preview shown in the history list.
        const val PREVIEW_MAX_DIMENSION = 256
        const val PREVIEW_QUALITY = 80
        const val PREVIEW_MAX_PAYLOAD_KB = 64
    }

    object History {
        const val DEFAULT_MAX_ENTRIES = 50
        const val MIN_MAX_ENTRIES = 5
        const val MAX_MAX_ENTRIES = 1000
        const val IMAGE_DIR = "clipshare_history_images"
        const val PREVIEW_DIR = "clipshare_history_previews"
    }

    object Navigation {
        const val MAIN = "main"
        const val CAPTURE = "capture"
        const val LOGS = "logs"
        const val SETTINGS = "settings"
    }

    object Log {
        const val MAX_ENTRIES = 500
        const val MAX_MESSAGE_LENGTH = 2000
        const val DEFAULT_MAX_FILE_KB = 256
        const val MIN_MAX_FILE_KB = 16
        const val MAX_MAX_FILE_KB = 4096
    }

    object Notification {
        const val CHANNEL_ID = "clipshare_sync"
        const val ID = 1
    }

    object Pkcs12 {
        const val PASSWORD = "clipshare"
    }

    object Certs {
        /**
         * Client certificate bundles are only used to authenticate to other
         * servers. In server mode they hold no value, so they are deleted after
         * this retention period to reduce the app's secret surface.
         */
        const val SERVER_MODE_CLIENT_CERT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }

    object Validation {
        const val DEVICE_NAME_BLANK = "Device name cannot be blank"
        const val HISTORY_ENTRIES_RANGE = "Max history entries must be between %d and %d"
        const val LOG_FILE_SIZE_RANGE = "Max log file size must be between %d and %d KB"
        const val CLIPBOARD_POLL_RANGE = "Clipboard poll interval must be between %d and %d ms"
        const val IMAGE_PAYLOAD_RANGE = "Max image payload must be between %d and %d KB"
        const val PORT_RANGE = "Port must be between %d and %d"
    }

    object App {
        const val VERSION = "1.2.0"
    }
}

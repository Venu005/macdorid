import Foundation

public enum ProtocolInfo {
    public static let version = 1
    public static let serviceType = "_macdroid._tcp"
    /// Features this app implements, sent in `hello` (spec §2.1).
    public static let features: [String] = [Feature.files, Feature.notifications, Feature.clipboard, Feature.ring, Feature.links, Feature.media]
}

/// Names used in `hello.features`. A feature is active only when both sides list it.
public enum Feature {
    public static let files = "files"
    public static let notifications = "notifications"
    public static let clipboard = "clipboard"
    public static let ring = "ring"
    public static let links = "links"
    public static let media = "media"
}

public struct Hello: Codable, Equatable, Sendable {
    public var `protocol`: Int
    public var app: String
    public var device: String
    public var features: [String]

    public init(protocol protocolVersion: Int = ProtocolInfo.version, app: String, device: String, features: [String]) {
        self.protocol = protocolVersion
        self.app = app
        self.device = device
        self.features = features
    }

    /// Older apps may leave `features` out; that means none (spec §2.1).
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.protocol = try container.decode(Int.self, forKey: .`protocol`)
        self.app = try container.decode(String.self, forKey: .app)
        self.device = try container.decode(String.self, forKey: .device)
        self.features = try container.decodeIfPresent([String].self, forKey: .features) ?? []
    }
}

public struct DeviceStatus: Codable, Equatable, Sendable {
    public var battery: Int
    public var charging: Bool

    public init(battery: Int, charging: Bool) {
        self.battery = battery
        self.charging = charging
    }
}

public struct PairRequest: Codable, Equatable, Sendable {
    public var code: String
    public var deviceName: String
    public var model: String

    public init(code: String, deviceName: String, model: String) {
        self.code = code
        self.deviceName = deviceName
        self.model = model
    }
}

public struct PairResponse: Codable, Equatable, Sendable {
    public var macName: String

    public init(macName: String) { self.macName = macName }
}

public struct ServerInfo: Codable, Equatable, Sendable {
    public var macName: String
    public var `protocol`: Int

    public init(macName: String, protocol protocolVersion: Int = ProtocolInfo.version) {
        self.macName = macName
        self.protocol = protocolVersion
    }
}

/// What a transfer is for. Absent on the wire means `.file` (spec §8.9).
public enum TransferPurpose: String, Codable, Sendable {
    case file
    case clipboard
}

public struct TransferOffer: Codable, Equatable, Sendable {
    public var transferId: String
    public var name: String
    public var size: Int64
    public var mime: String
    /// nil for files (kept off the wire); `.clipboard` for clipboard images (spec §2.3).
    public var purpose: TransferPurpose?

    public init(transferId: String, name: String, size: Int64, mime: String, purpose: TransferPurpose? = nil) {
        self.transferId = transferId
        self.name = name
        self.size = size
        self.mime = mime
        self.purpose = purpose
    }
}

/// Body of `transfer.accept` and `transfer.cancel`.
public struct TransferID: Codable, Equatable, Sendable {
    public var transferId: String

    public init(transferId: String) { self.transferId = transferId }
}

public enum TransferRejectReason: String, Codable, Sendable {
    case noSpace = "no_space"
    case declined
    case invalid
}

public struct TransferReject: Codable, Equatable, Sendable {
    public var transferId: String
    public var reason: TransferRejectReason

    public init(transferId: String, reason: TransferRejectReason) {
        self.transferId = transferId
        self.reason = reason
    }
}

public struct TransferFinish: Codable, Equatable, Sendable {
    public var transferId: String
    /// Lowercase hex SHA-256 of the whole file.
    public var sha256: String

    public init(transferId: String, sha256: String) {
        self.transferId = transferId
        self.sha256 = sha256
    }
}

public enum TransferResultError: String, Codable, Sendable {
    case checksumMismatch = "checksum_mismatch"
    case noSpace = "no_space"
    case io
}

public struct TransferResult: Codable, Equatable, Sendable {
    public var transferId: String
    public var ok: Bool
    public var error: TransferResultError?

    public init(transferId: String, ok: Bool, error: TransferResultError? = nil) {
        self.transferId = transferId
        self.ok = ok
        self.error = error
    }
}

// MARK: Small wins (Plan 5)

public enum RingAction: String, Codable, Sendable { case start, stop }

public struct DeviceRing: Codable, Equatable, Sendable {
    public var action: RingAction
    public init(action: RingAction) { self.action = action }
}

public struct RingState: Codable, Equatable, Sendable {
    public var ringing: Bool
    public init(ringing: Bool) { self.ringing = ringing }
}

public struct LinkOpen: Codable, Equatable, Sendable {
    public var url: String
    public init(url: String) { self.url = url }
}

/// What's playing on the phone. `active == false` means nothing; the other fields are then nil (omitted on the wire).
public struct MediaState: Codable, Equatable, Sendable {
    public var active: Bool
    public var packageName: String?
    public var appName: String?
    public var title: String?
    public var artist: String?
    public var durationMs: Int64?
    public var positionMs: Int64?
    /// Phone epoch milliseconds when `positionMs` was read.
    public var positionAt: Int64?
    public var playing: Bool?
    public var canPrevious: Bool?
    public var canNext: Bool?
    public var canSeek: Bool?
    public var artHash: String?

    enum CodingKeys: String, CodingKey {
        case active, packageName = "package", appName, title, artist, durationMs, positionMs, positionAt, playing, canPrevious, canNext, canSeek, artHash
    }

    public init(
        active: Bool, packageName: String? = nil, appName: String? = nil, title: String? = nil, artist: String? = nil,
        durationMs: Int64? = nil, positionMs: Int64? = nil, positionAt: Int64? = nil, playing: Bool? = nil,
        canPrevious: Bool? = nil, canNext: Bool? = nil, canSeek: Bool? = nil, artHash: String? = nil
    ) {
        self.active = active
        self.packageName = packageName
        self.appName = appName
        self.title = title
        self.artist = artist
        self.durationMs = durationMs
        self.positionMs = positionMs
        self.positionAt = positionAt
        self.playing = playing
        self.canPrevious = canPrevious
        self.canNext = canNext
        self.canSeek = canSeek
        self.artHash = artHash
    }

    public static let idle = MediaState(active: false)
}

public struct MediaArt: Codable, Equatable, Sendable {
    public var hash: String
    /// Base64 JPEG, at most 300 px on the longest side.
    public var jpeg: String
    public init(hash: String, jpeg: String) {
        self.hash = hash
        self.jpeg = jpeg
    }
}

public enum MediaCommandKind: String, Codable, Sendable { case play, pause, next, previous, seek }

public struct MediaCommand: Codable, Equatable, Sendable {
    public var command: MediaCommandKind
    /// Seek only.
    public var positionMs: Int64?
    public init(command: MediaCommandKind, positionMs: Int64? = nil) {
        self.command = command
        self.positionMs = positionMs
    }
}

// MARK: Clipboard (spec §2.3)

public enum ClipboardLimits {
    /// A `clipboard.set` frame, encoded, must fit the WebSocket frame limit (spec §8.1).
    public static let maxFrameBytes = 1 << 20
    /// Clipboard images, as PNG.
    public static let maxImageBytes = 10 * 1024 * 1024
}

public enum ClipboardKind: String, Codable, Sendable {
    case text
}

public struct ClipboardSet: Codable, Equatable, Sendable {
    public var itemId: String
    public var kind: ClipboardKind
    public var text: String

    public init(itemId: String, kind: ClipboardKind = .text, text: String) {
        self.itemId = itemId
        self.kind = kind
        self.text = text
    }
}

// MARK: Notifications (spec §2.2)

public struct NotificationButton: Codable, Equatable, Sendable {
    /// Index in the Android notification's `actions`.
    public var index: Int
    public var title: String

    public init(index: Int, title: String) {
        self.index = index
        self.title = title
    }
}

public struct NotificationPosted: Codable, Equatable, Sendable {
    /// Android's `StatusBarNotification.key`; opaque to the Mac.
    public var key: String
    public var packageName: String
    public var appName: String
    public var title: String
    public var text: String
    /// Epoch milliseconds on the phone.
    public var postedAt: Int64
    public var actions: [NotificationButton]
    public var canReply: Bool
    /// 16 lowercase hex characters, or empty when the phone had no icon.
    public var iconHash: String
    public var isUpdate: Bool

    enum CodingKeys: String, CodingKey {
        case key, packageName = "package", appName, title, text, postedAt, actions, canReply, iconHash, isUpdate
    }

    public init(
        key: String, packageName: String, appName: String, title: String, text: String, postedAt: Int64,
        actions: [NotificationButton], canReply: Bool, iconHash: String, isUpdate: Bool
    ) {
        self.key = key
        self.packageName = packageName
        self.appName = appName
        self.title = title
        self.text = text
        self.postedAt = postedAt
        self.actions = actions
        self.canReply = canReply
        self.iconHash = iconHash
        self.isUpdate = isUpdate
    }
}

/// Body of `notification.removed` and `notification.dismiss`.
public struct NotificationKey: Codable, Equatable, Sendable {
    public var key: String

    public init(key: String) { self.key = key }
}

public struct NotificationSync: Codable, Equatable, Sendable {
    /// Every forwarded notification still showing on the phone.
    public var keys: [String]

    public init(keys: [String]) { self.keys = keys }
}

public struct NotificationIcon: Codable, Equatable, Sendable {
    public var hash: String
    /// Base64 PNG, at most 96×96.
    public var png: String

    public init(hash: String, png: String) {
        self.hash = hash
        self.png = png
    }
}

public struct PhoneApp: Codable, Equatable, Sendable, Identifiable {
    public var packageName: String
    public var appName: String
    public var iconHash: String
    public var muted: Bool

    public var id: String { packageName }

    enum CodingKeys: String, CodingKey {
        case packageName = "package", appName, iconHash, muted
    }

    public init(packageName: String, appName: String, iconHash: String, muted: Bool) {
        self.packageName = packageName
        self.appName = appName
        self.iconHash = iconHash
        self.muted = muted
    }
}

public struct NotificationApps: Codable, Equatable, Sendable {
    /// The phone's "Show on your Mac" switch.
    public var forwarding: Bool
    /// The phone has notification access.
    public var access: Bool
    public var apps: [PhoneApp]

    public init(forwarding: Bool, access: Bool, apps: [PhoneApp]) {
        self.forwarding = forwarding
        self.access = access
        self.apps = apps
    }
}

public struct NotificationReply: Codable, Equatable, Sendable {
    public var key: String
    public var text: String

    public init(key: String, text: String) {
        self.key = key
        self.text = text
    }
}

/// Body of `notification.action`.
public struct NotificationActionRequest: Codable, Equatable, Sendable {
    public var key: String
    public var actionIndex: Int

    public init(key: String, actionIndex: Int) {
        self.key = key
        self.actionIndex = actionIndex
    }
}

public struct NotificationMute: Codable, Equatable, Sendable {
    public var packageName: String
    public var muted: Bool

    enum CodingKeys: String, CodingKey {
        case packageName = "package", muted
    }

    public init(packageName: String, muted: Bool) {
        self.packageName = packageName
        self.muted = muted
    }
}

public enum NotificationErrorReason: String, Codable, Sendable {
    case gone
    case failed
}

public struct NotificationError: Codable, Equatable, Sendable {
    public var key: String
    public var reason: NotificationErrorReason

    public init(key: String, reason: NotificationErrorReason) {
        self.key = key
        self.reason = reason
    }
}

/// A control-channel message.
public enum Message: Equatable, Sendable {
    case hello(Hello)
    case deviceStatus(DeviceStatus)
    case transferOffer(TransferOffer)
    case transferAccept(TransferID)
    case transferReject(TransferReject)
    case transferFinish(TransferFinish)
    case transferResult(TransferResult)
    case transferCancel(TransferID)
    case notificationPosted(NotificationPosted)
    case notificationRemoved(NotificationKey)
    case notificationSync(NotificationSync)
    case notificationIcon(NotificationIcon)
    case notificationApps(NotificationApps)
    case notificationReply(NotificationReply)
    case notificationAction(NotificationActionRequest)
    case notificationDismiss(NotificationKey)
    case notificationMute(NotificationMute)
    case notificationError(NotificationError)
    case clipboardSet(ClipboardSet)
    case deviceRing(DeviceRing)
    case ringState(RingState)
    case linkOpen(LinkOpen)
    case mediaState(MediaState)
    case mediaArt(MediaArt)
    case mediaCommand(MediaCommand)
    /// A type this version doesn't understand. Decoded so callers can ignore it; never encoded.
    case unknown(type: String)
}

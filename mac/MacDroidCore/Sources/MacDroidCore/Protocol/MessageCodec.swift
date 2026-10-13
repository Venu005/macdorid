import Foundation

public enum MessageCodecError: Error, Equatable {
    case malformed
    case cannotEncodeUnknown(String)
}

public enum MessageCodec {
    private struct Header: Decodable { let type: String }
    private struct Incoming<Body: Decodable>: Decodable { let body: Body }
    private struct Outgoing<Body: Encodable>: Encodable {
        let v: Int
        let type: String
        let id: String
        let body: Body
    }

    public static func encode(_ message: Message, id: String = UUID().uuidString.lowercased()) throws -> String {
        switch message {
        case .hello(let body): return try encode(type: "hello", id: id, body: body)
        case .deviceStatus(let body): return try encode(type: "device.status", id: id, body: body)
        case .transferOffer(let body): return try encode(type: "transfer.offer", id: id, body: body)
        case .transferAccept(let body): return try encode(type: "transfer.accept", id: id, body: body)
        case .transferReject(let body): return try encode(type: "transfer.reject", id: id, body: body)
        case .transferFinish(let body): return try encode(type: "transfer.finish", id: id, body: body)
        case .transferResult(let body): return try encode(type: "transfer.result", id: id, body: body)
        case .transferCancel(let body): return try encode(type: "transfer.cancel", id: id, body: body)
        case .notificationPosted(let body): return try encode(type: "notification.posted", id: id, body: body)
        case .notificationRemoved(let body): return try encode(type: "notification.removed", id: id, body: body)
        case .notificationSync(let body): return try encode(type: "notification.sync", id: id, body: body)
        case .notificationIcon(let body): return try encode(type: "notification.icon", id: id, body: body)
        case .notificationApps(let body): return try encode(type: "notification.apps", id: id, body: body)
        case .notificationReply(let body): return try encode(type: "notification.reply", id: id, body: body)
        case .notificationAction(let body): return try encode(type: "notification.action", id: id, body: body)
        case .notificationDismiss(let body): return try encode(type: "notification.dismiss", id: id, body: body)
        case .notificationMute(let body): return try encode(type: "notification.mute", id: id, body: body)
        case .notificationError(let body): return try encode(type: "notification.error", id: id, body: body)
        case .unknown(let type): throw MessageCodecError.cannotEncodeUnknown(type)
        }
    }

    public static func decode(_ text: String) throws -> Message {
        let data = Data(text.utf8)
        let decoder = JSONDecoder()
        do {
            switch try decoder.decode(Header.self, from: data).type {
            case "hello": return .hello(try decoder.decode(Incoming<Hello>.self, from: data).body)
            case "device.status": return .deviceStatus(try decoder.decode(Incoming<DeviceStatus>.self, from: data).body)
            case "transfer.offer": return .transferOffer(try decoder.decode(Incoming<TransferOffer>.self, from: data).body)
            case "transfer.accept": return .transferAccept(try decoder.decode(Incoming<TransferID>.self, from: data).body)
            case "transfer.reject": return .transferReject(try decoder.decode(Incoming<TransferReject>.self, from: data).body)
            case "transfer.finish": return .transferFinish(try decoder.decode(Incoming<TransferFinish>.self, from: data).body)
            case "transfer.result": return .transferResult(try decoder.decode(Incoming<TransferResult>.self, from: data).body)
            case "transfer.cancel": return .transferCancel(try decoder.decode(Incoming<TransferID>.self, from: data).body)
            case "notification.posted": return .notificationPosted(try decoder.decode(Incoming<NotificationPosted>.self, from: data).body)
            case "notification.removed": return .notificationRemoved(try decoder.decode(Incoming<NotificationKey>.self, from: data).body)
            case "notification.sync": return .notificationSync(try decoder.decode(Incoming<NotificationSync>.self, from: data).body)
            case "notification.icon": return .notificationIcon(try decoder.decode(Incoming<NotificationIcon>.self, from: data).body)
            case "notification.apps": return .notificationApps(try decoder.decode(Incoming<NotificationApps>.self, from: data).body)
            case "notification.reply": return .notificationReply(try decoder.decode(Incoming<NotificationReply>.self, from: data).body)
            case "notification.action": return .notificationAction(try decoder.decode(Incoming<NotificationActionRequest>.self, from: data).body)
            case "notification.dismiss": return .notificationDismiss(try decoder.decode(Incoming<NotificationKey>.self, from: data).body)
            case "notification.mute": return .notificationMute(try decoder.decode(Incoming<NotificationMute>.self, from: data).body)
            case "notification.error": return .notificationError(try decoder.decode(Incoming<NotificationError>.self, from: data).body)
            case let other: return .unknown(type: other)
            }
        } catch {
            throw MessageCodecError.malformed
        }
    }

    private static func encode<Body: Encodable>(type: String, id: String, body: Body) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(Outgoing(v: ProtocolInfo.version, type: type, id: id, body: body))
        return String(decoding: data, as: UTF8.self)
    }
}

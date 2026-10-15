import Foundation

public enum MessageCodecError: Error, Equatable {
    case malformed
    case cannotEncodeUnknown(String)
}

public enum MessageCodec {
    private struct Header: Decodable { let type: String }
    private struct Empty: Codable {}
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
        case .clipboardSet(let body): return try encode(type: "clipboard.set", id: id, body: body)
        case .deviceRing(let body): return try encode(type: "device.ring", id: id, body: body)
        case .ringState(let body): return try encode(type: "device.ringState", id: id, body: body)
        case .linkOpen(let body): return try encode(type: "link.open", id: id, body: body)
        case .mediaState(let body): return try encode(type: "media.state", id: id, body: body)
        case .mediaArt(let body): return try encode(type: "media.art", id: id, body: body)
        case .mediaCommand(let body): return try encode(type: "media.command", id: id, body: body)
        case .photosAlbums(let body): return try encode(type: "photos.albums", id: id, body: body)
        case .photosAlbumList(let body): return try encode(type: "photos.albumList", id: id, body: body)
        case .photosQuery(let body): return try encode(type: "photos.query", id: id, body: body)
        case .photosPage(let body): return try encode(type: "photos.page", id: id, body: body)
        case .photosThumbs(let body): return try encode(type: "photos.thumbs", id: id, body: body)
        case .photosThumb(let body): return try encode(type: "photos.thumb", id: id, body: body)
        case .photosFetch(let body): return try encode(type: "photos.fetch", id: id, body: body)
        case .photosFetchFailed(let body): return try encode(type: "photos.fetchFailed", id: id, body: body)
        case .photosChanged: return try encode(type: "photos.changed", id: id, body: Empty())
        case .mirrorStart(let body): return try encode(type: "mirror.start", id: id, body: body)
        case .mirrorState(let body): return try encode(type: "mirror.state", id: id, body: body)
        case .mirrorStop(let body): return try encode(type: "mirror.stop", id: id, body: body)
        case .mirrorKeyframe(let body): return try encode(type: "mirror.keyframe", id: id, body: body)
        case .mirrorPing(let body): return try encode(type: "mirror.ping", id: id, body: body)
        case .mirrorPong(let body): return try encode(type: "mirror.pong", id: id, body: body)
        case .inputPointer(let body): return try encode(type: "input.pointer", id: id, body: body)
        case .inputScroll(let body): return try encode(type: "input.scroll", id: id, body: body)
        case .inputKey(let body): return try encode(type: "input.key", id: id, body: body)
        case .inputText(let body): return try encode(type: "input.text", id: id, body: body)
        case .inputNotice(let body): return try encode(type: "input.notice", id: id, body: body)
        case .callState(let body): return try encode(type: "call.state", id: id, body: body)
        case .callAction(let body): return try encode(type: "call.action", id: id, body: body)
        case .callDial(let body): return try encode(type: "call.dial", id: id, body: body)
        case .callError(let body): return try encode(type: "call.error", id: id, body: body)
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
            case "clipboard.set": return .clipboardSet(try decoder.decode(Incoming<ClipboardSet>.self, from: data).body)
            case "device.ring": return .deviceRing(try decoder.decode(Incoming<DeviceRing>.self, from: data).body)
            case "device.ringState": return .ringState(try decoder.decode(Incoming<RingState>.self, from: data).body)
            case "link.open": return .linkOpen(try decoder.decode(Incoming<LinkOpen>.self, from: data).body)
            case "media.state": return .mediaState(try decoder.decode(Incoming<MediaState>.self, from: data).body)
            case "media.art": return .mediaArt(try decoder.decode(Incoming<MediaArt>.self, from: data).body)
            case "media.command": return .mediaCommand(try decoder.decode(Incoming<MediaCommand>.self, from: data).body)
            case "photos.albums": return .photosAlbums(try decoder.decode(Incoming<PhotosAlbumsRequest>.self, from: data).body)
            case "photos.albumList": return .photosAlbumList(try decoder.decode(Incoming<PhotosAlbumList>.self, from: data).body)
            case "photos.query": return .photosQuery(try decoder.decode(Incoming<PhotosQuery>.self, from: data).body)
            case "photos.page": return .photosPage(try decoder.decode(Incoming<PhotosPage>.self, from: data).body)
            case "photos.thumbs": return .photosThumbs(try decoder.decode(Incoming<PhotosThumbs>.self, from: data).body)
            case "photos.thumb": return .photosThumb(try decoder.decode(Incoming<PhotosThumb>.self, from: data).body)
            case "photos.fetch": return .photosFetch(try decoder.decode(Incoming<PhotosFetch>.self, from: data).body)
            case "photos.fetchFailed": return .photosFetchFailed(try decoder.decode(Incoming<PhotosFetchFailed>.self, from: data).body)
            case "photos.changed": return .photosChanged
            case "mirror.start": return .mirrorStart(try decoder.decode(Incoming<MirrorStart>.self, from: data).body)
            case "mirror.state": return .mirrorState(try decoder.decode(Incoming<MirrorStateUpdate>.self, from: data).body)
            case "mirror.stop": return .mirrorStop(try decoder.decode(Incoming<MirrorSessionRef>.self, from: data).body)
            case "mirror.keyframe": return .mirrorKeyframe(try decoder.decode(Incoming<MirrorSessionRef>.self, from: data).body)
            case "mirror.ping": return .mirrorPing(try decoder.decode(Incoming<MirrorPing>.self, from: data).body)
            case "mirror.pong": return .mirrorPong(try decoder.decode(Incoming<MirrorPong>.self, from: data).body)
            case "input.pointer": return .inputPointer(try decoder.decode(Incoming<InputPointer>.self, from: data).body)
            case "input.scroll": return .inputScroll(try decoder.decode(Incoming<InputScroll>.self, from: data).body)
            case "input.key": return .inputKey(try decoder.decode(Incoming<InputKey>.self, from: data).body)
            case "input.text": return .inputText(try decoder.decode(Incoming<InputText>.self, from: data).body)
            case "input.notice": return .inputNotice(try decoder.decode(Incoming<InputNotice>.self, from: data).body)
            case "call.state": return .callState(try decoder.decode(Incoming<CallState>.self, from: data).body)
            case "call.action": return .callAction(try decoder.decode(Incoming<CallAction>.self, from: data).body)
            case "call.dial": return .callDial(try decoder.decode(Incoming<CallDial>.self, from: data).body)
            case "call.error": return .callError(try decoder.decode(Incoming<CallError>.self, from: data).body)
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

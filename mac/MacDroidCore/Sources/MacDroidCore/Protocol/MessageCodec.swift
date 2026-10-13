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

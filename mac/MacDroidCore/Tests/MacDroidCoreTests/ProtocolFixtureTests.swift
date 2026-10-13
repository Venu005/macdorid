import Foundation
import Testing
@testable import MacDroidCore

@Suite struct ProtocolFixtureTests {
    @Test func helloFixtureDecodes() throws {
        let message = try MessageCodec.decode(try Fixtures.text("hello.json"))
        #expect(message == .hello(Hello(app: "0.1.0", device: "Nothing Phone (3)", features: [])))
    }

    @Test func deviceStatusFixtureDecodes() throws {
        let message = try MessageCodec.decode(try Fixtures.text("device.status.json"))
        #expect(message == .deviceStatus(DeviceStatus(battery: 82, charging: true)))
    }

    static let messageFixtures = [
        "hello.json", "device.status.json",
        "transfer.offer.json", "transfer.accept.json", "transfer.reject.json", "transfer.finish.json",
        "transfer.result.json", "transfer.result.failed.json", "transfer.cancel.json",
    ]

    @Test(arguments: messageFixtures)
    func fixturesRoundTrip(name: String) throws {
        let text = try Fixtures.text(name)
        let reencoded = try MessageCodec.encode(try MessageCodec.decode(text), id: try Fixtures.id(of: text))
        #expect(try Fixtures.jsonObject(reencoded) == Fixtures.jsonObject(text))
    }

    @Test func transferFixturesDecode() throws {
        let id = "0b7e4f52-8c1d-4e0a-9f3b-2d6a1c5e7f90"
        #expect(try MessageCodec.decode(Fixtures.text("transfer.offer.json")) == .transferOffer(TransferOffer(transferId: id, name: "IMG_2041.jpg", size: 3_145_728, mime: "image/jpeg")))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.accept.json")) == .transferAccept(TransferID(transferId: id)))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.reject.json")) == .transferReject(TransferReject(transferId: id, reason: .noSpace)))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.finish.json")) == .transferFinish(TransferFinish(transferId: id, sha256: "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.result.json")) == .transferResult(TransferResult(transferId: id, ok: true)))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.result.failed.json")) == .transferResult(TransferResult(transferId: id, ok: false, error: .checksumMismatch)))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.cancel.json")) == .transferCancel(TransferID(transferId: id)))
    }

    @Test func unknownRejectReasonIsMalformed() {
        let text = #"{"v":1,"type":"transfer.reject","id":"x","body":{"transferId":"t","reason":"bored"}}"#
        #expect(throws: MessageCodecError.malformed) { try MessageCodec.decode(text) }
    }

    @Test func unknownTypeIsReportedNotRejected() throws {
        #expect(try MessageCodec.decode(try Fixtures.text("unknown-type.json")) == .unknown(type: "future.thing"))
    }

    @Test(arguments: ["not json", "{}", #"{"type":"hello","body":{"protocol":"x"}}"#, "[1,2]"])
    func malformedFramesThrow(text: String) {
        #expect(throws: MessageCodecError.malformed) { try MessageCodec.decode(text) }
    }

    @Test func encodingUnknownThrows() {
        #expect(throws: MessageCodecError.cannotEncodeUnknown("x")) { try MessageCodec.encode(.unknown(type: "x")) }
    }

    @Test func pairBodiesMatchFixtures() throws {
        let request = try JSONDecoder().decode(PairRequest.self, from: Data(try Fixtures.text("pair.request.json").utf8))
        #expect(request == PairRequest(code: "MZXW6YTBOI2DGNBVGY3TQOJQGE", deviceName: "My Phone", model: "Nothing Phone (3)"))
        let response = try JSONDecoder().decode(PairResponse.self, from: Data(try Fixtures.text("pair.response.json").utf8))
        #expect(response == PairResponse(macName: "MacBook Pro"))
    }
}

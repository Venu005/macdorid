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

    @Test(arguments: ["hello.json", "device.status.json"])
    func fixturesRoundTrip(name: String) throws {
        let text = try Fixtures.text(name)
        let reencoded = try MessageCodec.encode(try MessageCodec.decode(text), id: try Fixtures.id(of: text))
        #expect(try Fixtures.jsonObject(reencoded) == Fixtures.jsonObject(text))
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

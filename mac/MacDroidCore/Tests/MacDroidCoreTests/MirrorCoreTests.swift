import Foundation
import Testing
@testable import MacDroidCore

private struct FrameFixture: Decodable { let type: UInt8; let key: Bool; let captureMicros: Int64; let payload: String; let hex: String }
private struct FrameFixtures: Decodable { let frames: [FrameFixture] }

extension Data {
    init(hexString: String) {
        self.init()
        var index = hexString.startIndex
        while index < hexString.endIndex {
            let next = hexString.index(index, offsetBy: 2)
            append(UInt8(hexString[index..<next], radix: 16)!)
            index = next
        }
    }
}

@Suite struct MirrorFrameTests {
    @Test func framesMatchTheSharedFixture() throws {
        let fixtures = try JSONDecoder().decode(FrameFixtures.self, from: Data(Fixtures.text("mirror-frames.json").utf8))
        for f in fixtures.frames {
            let frame = MirrorFrame(type: try #require(MirrorFrameType(rawValue: f.type)), keyFrame: f.key, captureMicros: f.captureMicros, payload: Data(hexString: f.payload))
            #expect(frame.encoded() == Data(hexString: f.hex))
            #expect(try MirrorFrame.decode(Data(hexString: f.hex)) == frame)
        }
    }

    @Test func badFramesAreRejected() {
        #expect(throws: MirrorFrameError.tooShort) { try MirrorFrame.decode(Data([1, 0, 0])) }
        #expect(throws: MirrorFrameError.unknownType(9)) { try MirrorFrame.decode(Data([9] + Array(repeating: 0, count: 11))) }
        #expect(throws: MirrorFrameError.tooLarge) { try MirrorFrame.decode(Data(count: MirrorFrame.maxSize + 1)) }
    }
}

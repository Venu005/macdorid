import CoreMedia
import Foundation
import Testing
@testable import MacDroidCore

/// Groups the fixture's NALs the way the phone sends them: (config = SPS+PPS Annex-B) and one access unit per slice.
func h264FixtureFrames() throws -> [(config: Data?, frame: Data?)] {
    let data = try Data(contentsOf: Fixtures.directory.appending(path: "h264/sample.h264"))
    let start = Data([0, 0, 0, 1])
    var out: [(config: Data?, frame: Data?)] = []
    var params = Data()
    for nal in H264.nalUnits(in: data) {
        switch H264.type(of: nal) {
        case 7, 8: params.append(start + nal)
        case 1, 5:
            if !params.isEmpty {
                out.append((params, nil))
                params = Data()
            }
            out.append((nil, start + nal))
        default: break // SEI and others aren't sent
        }
    }
    return out
}

@Suite struct H264Tests {
    @Test func splitsAnnexBWithThreeAndFourByteStartCodes() {
        let data = Data([0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5])
        #expect(H264.nalUnits(in: data) == [Data([0x67, 1, 2]), Data([0x68, 3]), Data([0x65, 4, 5])])
        #expect(H264.type(of: Data([0x65])) == 5)
        #expect(H264.isKeyFrame(data))
        #expect(!H264.isKeyFrame(Data([0, 0, 1, 0x41, 9])))
    }

    @Test func fixtureDecodesIntoFormatsAndSampleBuffers() throws {
        let frames = try h264FixtureFrames()
        #expect(frames.filter { $0.frame != nil }.count == 20)
        #expect(frames.filter { $0.config != nil }.count == 2)
        var format: CMVideoFormatDescription?
        var keys = 0
        for f in frames {
            if let config = f.config {
                let sets = try #require(H264.parameterSets(in: config))
                format = try H264.formatDescription(sps: sets.sps, pps: sets.pps)
            }
            if let frame = f.frame {
                let buffer = try H264.sampleBuffer(accessUnit: frame, format: try #require(format))
                #expect(CMSampleBufferGetNumSamples(buffer) == 1)
                let attachments = try #require(CMSampleBufferGetSampleAttachmentsArray(buffer, createIfNecessary: false) as? [[CFString: Any]])
                #expect(attachments.first?[kCMSampleAttachmentKey_DisplayImmediately] as? Bool == true)
                if H264.isKeyFrame(frame) { keys += 1 }
            }
        }
        #expect(keys == 4)
    }

    // Review Focus #3
    @Test func aNewConfigReplacesTheFormat() throws {
        let configs = try h264FixtureFrames().compactMap(\.config)
        let first = try H264.formatDescription(sps: try #require(H264.parameterSets(in: configs[0])).sps, pps: try #require(H264.parameterSets(in: configs[0])).pps)
        let second = try H264.formatDescription(sps: try #require(H264.parameterSets(in: configs[1])).sps, pps: try #require(H264.parameterSets(in: configs[1])).pps)
        #expect(H264.dimensions(of: first) == (320, 240))
        #expect(H264.dimensions(of: second) == (240, 320))
    }

    @Test func badInputThrows() {
        #expect(throws: (any Error).self) { try H264.formatDescription(sps: Data([0x67]), pps: Data([0x68])) }
        #expect(H264.parameterSets(in: Data([0, 0, 0, 1, 0x67, 1])) == nil)
    }
}

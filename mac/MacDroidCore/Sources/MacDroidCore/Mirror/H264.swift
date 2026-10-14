import CoreMedia
import Foundation

public enum H264Error: Error, Equatable { case badParameterSets(OSStatus), noSlices, coreMedia(OSStatus) }

/// Annex-B H.264 from the phone → CoreMedia sample buffers for `AVSampleBufferDisplayLayer` (spec §6.1).
public enum H264 {
    public static func nalUnits(in data: Data) -> [Data] {
        let bytes = [UInt8](data)
        var starts: [(index: Int, length: Int)] = []
        var i = 0
        while i + 2 < bytes.count {
            if bytes[i] == 0, bytes[i + 1] == 0 {
                if bytes[i + 2] == 1 {
                    starts.append((i, 3)); i += 3; continue
                }
                if i + 3 < bytes.count, bytes[i + 2] == 0, bytes[i + 3] == 1 {
                    starts.append((i, 4)); i += 4; continue
                }
            }
            i += 1
        }
        var units: [Data] = []
        for (n, start) in starts.enumerated() {
            let from = start.index + start.length
            let to = n + 1 < starts.count ? starts[n + 1].index : bytes.count
            if from < to { units.append(Data(bytes[from..<to])) }
        }
        return units
    }

    public static func type(of nal: Data) -> UInt8 { (nal.first ?? 0) & 0x1F }

    public static func isKeyFrame(_ accessUnit: Data) -> Bool { nalUnits(in: accessUnit).contains { type(of: $0) == 5 } }

    public static func parameterSets(in config: Data) -> (sps: Data, pps: Data)? {
        let units = nalUnits(in: config)
        guard let sps = units.first(where: { type(of: $0) == 7 }), let pps = units.first(where: { type(of: $0) == 8 }) else { return nil }
        return (sps, pps)
    }

    public static func formatDescription(sps: Data, pps: Data) throws -> CMVideoFormatDescription {
        var format: CMFormatDescription?
        let status = sps.withUnsafeBytes { spsBytes in
            pps.withUnsafeBytes { ppsBytes in
                let pointers = [spsBytes.bindMemory(to: UInt8.self).baseAddress!, ppsBytes.bindMemory(to: UInt8.self).baseAddress!]
                let sizes = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2, parameterSetPointers: pointers,
                    parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &format)
            }
        }
        guard status == noErr, let format else { throw H264Error.badParameterSets(status) }
        return format
    }

    public static func dimensions(of format: CMVideoFormatDescription) -> (width: Int, height: Int) {
        let d = CMVideoFormatDescriptionGetDimensions(format)
        return (Int(d.width), Int(d.height))
    }

    public static func sampleBuffer(accessUnit: Data, format: CMVideoFormatDescription) throws -> CMSampleBuffer {
        var avcc = Data()
        for nal in nalUnits(in: accessUnit) where [1, 5].contains(type(of: nal)) {
            var length = UInt32(nal.count).bigEndian
            withUnsafeBytes(of: &length) { avcc.append(contentsOf: $0) }
            avcc.append(nal)
        }
        guard !avcc.isEmpty else { throw H264Error.noSlices }
        var block: CMBlockBuffer?
        var status = CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: avcc.count,
                                                        blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
                                                        dataLength: avcc.count, flags: 0, blockBufferOut: &block)
        guard status == noErr, let block else { throw H264Error.coreMedia(status) }
        status = avcc.withUnsafeBytes { CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: avcc.count) }
        guard status == noErr else { throw H264Error.coreMedia(status) }
        var sample: CMSampleBuffer?
        var size = avcc.count
        status = CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format, sampleCount: 1,
                                           sampleTimingEntryCount: 0, sampleTimingArray: nil, sampleSizeEntryCount: 1, sampleSizeArray: &size,
                                           sampleBufferOut: &sample)
        guard status == noErr, let sample else { throw H264Error.coreMedia(status) }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true), CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sample
    }
}

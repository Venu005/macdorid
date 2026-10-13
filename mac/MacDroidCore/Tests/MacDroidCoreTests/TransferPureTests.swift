import Foundation
import Testing
@testable import MacDroidCore

private struct NamingVectors: Decodable {
    struct Sanitize: Decodable { let `in`: String; let out: String }
    struct Unique: Decodable { let name: String; let taken: [String]; let out: String }
    let sanitize: [Sanitize]
    let unique: [Unique]
}

private struct TextVectors: Decodable {
    struct Bytes: Decodable { let `in`: Int64; let out: String }
    struct Line: Decodable { let done: Int64; let total: Int64; let rate: Double?; let out: String }
    let bytes: [Bytes]
    let lines: [Line]
}

private func vectors<T: Decodable>(_ name: String, as: T.Type) throws -> T {
    try JSONDecoder().decode(T.self, from: Data(try Fixtures.text(name).utf8))
}

@Suite struct FileNamingTests {
    @Test func sharedSanitizeVectors() throws {
        for vector in try vectors("file-naming.json", as: NamingVectors.self).sanitize {
            #expect(FileNaming.sanitize(vector.in) == vector.out, "sanitize(\(vector.in.debugDescription))")
        }
    }

    @Test func sharedUniqueVectors() throws {
        for vector in try vectors("file-naming.json", as: NamingVectors.self).unique {
            #expect(FileNaming.unique(vector.name) { vector.taken.contains($0) } == vector.out)
        }
    }

    @Test func longNamesKeepTheirExtensionWithin200Bytes() {
        let name = FileNaming.sanitize(String(repeating: "a", count: 250) + ".txt")
        #expect(name == String(repeating: "a", count: 196) + ".txt")
        let multibyte = FileNaming.sanitize(String(repeating: "é", count: 150) + ".jpg") // é is 2 bytes
        #expect(multibyte == String(repeating: "é", count: 98) + ".jpg")
        #expect(multibyte.utf8.count == 200)
    }

    @Test func overlongExtensionIsTreatedAsPartOfTheName() {
        let name = FileNaming.sanitize("x." + String(repeating: "e", count: 300))
        #expect(name.utf8.count == 200)
        #expect(name.hasPrefix("x.eee"))
    }

    @Test func uniqueStaysWithin200Bytes() {
        let long = String(repeating: "b", count: 196) + ".txt"
        let next = FileNaming.unique(long) { $0 == long }
        #expect(next == String(repeating: "b", count: 192) + " (1).txt")
        #expect(next.utf8.count == 200)
    }
}

@Suite struct ByteRangesTests {
    @Test func parsesOpenEndedRange() {
        #expect(ByteRanges.parseRangeStart("bytes=0-") == 0)
        #expect(ByteRanges.parseRangeStart("bytes=1048576-") == 1_048_576)
        #expect(ByteRanges.parseRangeStart("bytes=5-10") == nil) // only the open-ended form is used
        #expect(ByteRanges.parseRangeStart("bytes=-5") == nil)
        #expect(ByteRanges.parseRangeStart("items=5-") == nil)
    }

    @Test func parsesContentRange() throws {
        let range = try #require(ByteRanges.parseContentRange("bytes 100-199/200"))
        #expect(range.start == 100 && range.end == 199 && range.total == 200)
        #expect(ByteRanges.parseContentRange("bytes 100-99/200") == nil)
        #expect(ByteRanges.parseContentRange("bytes 100-200/200") == nil)
        #expect(ByteRanges.parseContentRange("bytes */200") == nil)
        #expect(ByteRanges.parseContentRange("bytes -1-5/10") == nil)
    }
}

@Suite struct TransferTextTests {
    @Test func sharedVectors() throws {
        let text = try vectors("transfer-text.json", as: TextVectors.self)
        for vector in text.bytes { #expect(TransferText.bytes(vector.in) == vector.out) }
        for vector in text.lines {
            #expect(TransferText.line(done: vector.done, total: vector.total, bytesPerSecond: vector.rate) == vector.out)
        }
    }
}

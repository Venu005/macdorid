import Foundation
import HTTPTypes
import HummingbirdTesting
import NIOCore
import Testing
@testable import MacDroidCore

@Suite struct FileRouteTests {
    let offset = HTTPField.Name("Upload-Offset")!

    @Test func uploadResumesWithHeadAndContentRange() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        let f = try TransferFixture()
        let manager = f.manager()
        let data = testBytes(300_000)
        await manager.handle(makeOffer("t1", name: "up.bin", size: Int64(data.count)))
        try await eventually { await manager.snapshot.active.count == 1 }
        try await ServerHarness.run(pairedPhone: phone, transfers: manager) { harness in
            let client = try harness.client(phone)
            let first = try await client.put("/v1/files/t1", body: ByteBuffer(bytes: data.prefix(100_000)))
            #expect(first.status == .ok)
            #expect(first.headers[offset] == "100000")
            #expect(try await client.head("/v1/files/t1").headers[offset] == "100000")
            let wrong = try await client.put("/v1/files/t1", headers: [.contentRange: "bytes 50-299999/300000"], body: ByteBuffer(bytes: data.dropFirst(50)))
            #expect(wrong.status == .conflict)
            #expect(wrong.headers[offset] == "100000")
            let rest = try await client.put("/v1/files/t1", headers: [.contentRange: "bytes 100000-299999/300000"], body: ByteBuffer(bytes: data.dropFirst(100_000)))
            #expect(rest.headers[offset] == "300000")
            try await client.shutdown()
        }
        await manager.handle(.transferFinish(TransferFinish(transferId: "t1", sha256: Checksum.sha256Hex(data))))
        #expect(try Data(contentsOf: f.file("up.bin")) == data)
    }

    @Test func downloadServesRangesAndSendsFinish() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let source = f.state.deletingLastPathComponent().appending(path: "down.bin")
        let data = testBytes(250_000)
        try data.write(to: source)
        let id = try #require(await manager.send(files: [source]).first)
        try await ServerHarness.run(pairedPhone: phone, transfers: manager) { harness in
            let client = try harness.client(phone)
            let whole = try await client.get("/v1/files/\(id)")
            #expect(whole.status == .ok)
            #expect(Data(try #require(whole.body).readableBytesView) == data)
            let tail = try await client.get("/v1/files/\(id)", headers: [.range: "bytes=200000-"])
            #expect(tail.status == .partialContent)
            #expect(tail.headers[.contentRange] == "bytes 200000-249999/250000")
            #expect(Data(try #require(tail.body).readableBytesView) == data.dropFirst(200_000))
            try await client.shutdown()
        }
        let finishes = await f.phone.sent.compactMap(\.finishBody)
        #expect(finishes.count == 2 && finishes.allSatisfy { $0.sha256 == Checksum.sha256Hex(data) })
    }

    // Review Focus #5
    @Test func getAtEndReturnsEmpty206AndFinish() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let dir = f.state.deletingLastPathComponent()
        let small = dir.appending(path: "small.txt")
        let empty = dir.appending(path: "empty.txt")
        try Data("hello".utf8).write(to: small)
        try Data().write(to: empty)
        let ids = await manager.send(files: [small, empty])
        try await ServerHarness.run(pairedPhone: phone, transfers: manager) { harness in
            let client = try harness.client(phone)
            // The phone already has all 5 bytes: an empty 206, then finish.
            let atEnd = try await client.get("/v1/files/\(ids[0])", headers: [.range: "bytes=5-"])
            #expect(atEnd.status == .partialContent)
            #expect((atEnd.body?.readableBytes ?? 0) == 0)
            // A zero-byte file: a plain empty 200, then finish.
            let zero = try await client.get("/v1/files/\(ids[1])")
            #expect(zero.status == .ok)
            #expect((zero.body?.readableBytes ?? 0) == 0)
            try await client.shutdown()
        }
        let finishes = await f.phone.sent.compactMap(\.finishBody)
        #expect(finishes.first { $0.transferId == ids[0] }?.sha256 == Checksum.sha256Hex(Data("hello".utf8)))
        #expect(finishes.first { $0.transferId == ids[1] }?.sha256 == Checksum.sha256Hex(Data()))
    }

    @Test func wrongDirectionAndUnknownIdsAreNotFound() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        await manager.handle(makeOffer("incoming", size: 3))
        try await eventually { await manager.snapshot.active.count == 1 }
        let source = f.state.deletingLastPathComponent().appending(path: "o.bin")
        try testBytes(3).write(to: source)
        let outgoing = try #require(await manager.send(files: [source]).first)
        try await ServerHarness.run(pairedPhone: phone, transfers: manager) { harness in
            let client = try harness.client(phone)
            #expect(try await client.get("/v1/files/incoming").status == .notFound)
            #expect(try await client.head("/v1/files/\(outgoing)").status == .notFound)
            #expect(try await client.put("/v1/files/\(outgoing)", body: ByteBuffer(string: "abc")).status == .notFound)
            #expect(try await client.get("/v1/files/unknown").status == .notFound)
            try await client.shutdown()
        }
    }

    @Test func malformedRangeHeadersAreBadRequests() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", size: 10))
        try await eventually { await manager.snapshot.active.count == 1 }
        try await ServerHarness.run(pairedPhone: phone, transfers: manager) { harness in
            let client = try harness.client(phone)
            #expect(try await client.put("/v1/files/t1", headers: [.contentRange: "nonsense"], body: ByteBuffer(string: "x")).status == .badRequest)
            #expect(try await client.get("/v1/files/t1", headers: [.range: "bytes=1-2"]).status == .badRequest)
            try await client.shutdown()
        }
    }
}

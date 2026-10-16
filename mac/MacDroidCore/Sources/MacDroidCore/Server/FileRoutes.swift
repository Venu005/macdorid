import Foundation
import HTTPTypes
import Hummingbird
import NIOCore

extension MacDroidServer {
    static let uploadOffsetField = HTTPField.Name("Upload-Offset")!

    /// `/v1/files/:id` (spec §2.2). Registered after the global TrustedPeerMiddleware, so only the paired phone gets here.
    static func addFileRoutes(to router: Router<MacDroidRequestContext>, transfers: TransferManager) {
        router.head("/v1/files/:id") { _, context -> Response in
            let id = try context.parameters.require("id")
            guard let offset = await transfers.uploadOffset(id: id) else { throw HTTPError(.notFound) }
            return Response(status: .ok, headers: [MacDroidServer.uploadOffsetField: String(offset)])
        }
        router.put("/v1/files/:id") { request, context -> Response in
            try await receiveUpload(request, context: context, transfers: transfers)
        }
        router.get("/v1/files/:id") { request, context -> Response in
            try await serveDownload(request, context: context, transfers: transfers)
        }
    }

    private static func receiveUpload(_ request: Request, context: MacDroidRequestContext, transfers: TransferManager) async throws -> Response {
        let id = try context.parameters.require("id")
        var start: Int64 = 0
        var total: Int64?
        if let header = request.headers[.contentRange] {
            guard let range = ByteRanges.parseContentRange(header) else { throw HTTPError(.badRequest) }
            start = range.start
            total = range.total
        }
        let upload: TransferManager.Upload
        do {
            upload = try await transfers.beginUpload(id: id, start: start, total: total)
        } catch TransferManager.RouteError.offsetMismatch(let current) {
            return Response(status: .conflict, headers: [uploadOffsetField: String(current)])
        } catch TransferManager.RouteError.rangeNotSatisfiable {
            throw HTTPError(.rangeNotSatisfiable)
        } catch {
            throw HTTPError(.notFound)
        }
        do {
            for try await buffer in request.body {
                try await upload.writer.append(Data(buffer.readableBytesView), session: upload.session)
                guard await transfers.progress(id: id, bytesDone: await upload.writer.size) else { throw HTTPError(.gone) }
            }
        } catch let error as PartFileError {
            await upload.writer.flush()
            switch error {
            case .noSpace:
                await transfers.uploadFailed(id: id, .noSpace)
                throw HTTPError(.init(code: 507))
            case .io:
                await transfers.uploadFailed(id: id, .io)
                throw HTTPError(.internalServerError)
            case .superseded:
                throw HTTPError(.conflict)
            case .tooLarge:
                throw HTTPError(.badRequest)
            }
        } catch {
            await upload.writer.flush() // the phone went away: keep what arrived for the resume
            throw error
        }
        await upload.writer.flush()
        return Response(status: .ok, headers: [uploadOffsetField: String(await upload.writer.size)])
    }

    private static func serveDownload(_ request: Request, context: MacDroidRequestContext, transfers: TransferManager) async throws -> Response {
        let id = try context.parameters.require("id")
        var start: Int64 = 0
        if let header = request.headers[.range] {
            guard let value = ByteRanges.parseRangeStart(header) else { throw HTTPError(.badRequest) }
            start = value
        }
        let download: TransferManager.Download
        do {
            download = try await transfers.beginDownload(id: id, from: start)
        } catch TransferManager.RouteError.rangeNotSatisfiable {
            throw HTTPError(.rangeNotSatisfiable)
        } catch TransferManager.RouteError.sourceUnavailable {
            throw HTTPError(.gone)
        } catch {
            throw HTTPError(.notFound)
        }
        let file = download.file
        let reader: SourceReader
        do {
            reader = try SourceReader(url: file.url, offset: start)
        } catch {
            file.stop()
            await transfers.sourceFailed(id: id)
            throw HTTPError(.gone)
        }
        guard reader.size == download.size else {
            reader.close()
            file.stop()
            await transfers.sourceFailed(id: id)
            throw HTTPError(.gone)
        }
        // If the phone goes away before the body starts, the closure below never runs: the lease still lets go (Plan 11).
        let lease = AccessLease {
            reader.close()
            file.stop()
        }
        var headers: HTTPFields = [:]
        if start > 0, start < download.size {
            headers[.contentRange] = "bytes \(start)-\(download.size - 1)/\(download.size)"
        }
        let body = ResponseBody(contentLength: Int(download.size - start)) { writer in
            defer { lease.end() }
            while let chunk = try reader.next() {
                try await writer.write(ByteBuffer(bytes: chunk))
                guard await transfers.progress(id: id, bytesDone: reader.position) else { throw CancellationError() }
            }
            try await writer.finish(nil)
            await transfers.downloadCompleted(id: id, sha256: reader.digest())
        }
        return Response(status: start > 0 ? .partialContent : .ok, headers: headers, body: body)
    }
}

/// Runs `release` exactly once: when `end()` is called, or when the lease is dropped without it.
final class AccessLease: @unchecked Sendable {
    private let lock = NSLock()
    private var release: (() -> Void)?

    init(_ release: @escaping () -> Void) { self.release = release }

    func end() {
        let pending = lock.withLock { () -> (() -> Void)? in
            defer { release = nil }
            return release
        }
        pending?()
    }

    deinit { end() }
}

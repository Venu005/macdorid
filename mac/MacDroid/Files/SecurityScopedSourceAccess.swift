import Foundation
import MacDroidCore

/// Outgoing files: security-scoped, read-only bookmarks, so a resume still works after MacDroid relaunches (spec §3.1).
struct SecurityScopedSourceAccess: SourceAccess {
    func bookmark(for url: URL) throws -> Data {
        try url.bookmarkData(options: [.withSecurityScope, .securityScopeAllowOnlyReadAccess])
    }

    func resolve(_ bookmark: Data) throws -> AccessedFile {
        var stale = false
        let url = try URL(resolvingBookmarkData: bookmark, options: .withSecurityScope, bookmarkDataIsStale: &stale)
        let started = url.startAccessingSecurityScopedResource()
        return AccessedFile(url: url, stop: { if started { url.stopAccessingSecurityScopedResource() } })
    }
}

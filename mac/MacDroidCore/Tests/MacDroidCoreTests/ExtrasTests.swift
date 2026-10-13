import Foundation
import Testing
@testable import MacDroidCore

@Suite struct LinkRulesTests {
    @Test func findsTheFirstHttpLink() {
        #expect(LinkRules.firstLink(in: "ftp://x and then http://y.com/p and https://z.com") == "http://y.com/p")
        #expect(LinkRules.firstLink(in: "no link here") == nil)
        #expect(LinkRules.firstLink(in: "HTTPS://Example.com/A") == "HTTPS://Example.com/A")
    }

    // Review Focus #1
    @Test func trimsTrailingPunctuation() {
        #expect(LinkRules.firstLink(in: "(see https://x.com/a).") == "https://x.com/a")
        #expect(LinkRules.firstLink(in: "go to https://x.com/a?b=c!") == "https://x.com/a?b=c")
        #expect(LinkRules.firstLink(in: "\"https://x.com/q\"") == "https://x.com/q")
    }

    // Review Focus #2
    @Test func onlyHttpAndHttpsWithAHostAreAllowed() {
        #expect(LinkRules.allowedURL("https://ok.com/x") != nil)
        #expect(LinkRules.allowedURL("javascript:alert(1)") == nil)
        #expect(LinkRules.allowedURL("file:///etc/passwd") == nil)
        #expect(LinkRules.allowedURL("http://") == nil)
        #expect(LinkRules.allowedURL("https://x.com/" + String(repeating: "a", count: 8_200)) == nil)
    }

    @Test func oversizedLinksAreNotFound() {
        #expect(LinkRules.firstLink(in: "https://x.com/" + String(repeating: "a", count: 8_200)) == nil)
    }
}

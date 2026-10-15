import Testing
@testable import MacDroidCore

@Suite struct DialInputTests {
    @Test func typingKeepsOnlyDialCharacters() {
        var input = DialInput()
        input.type("1a-2 (3)")
        #expect(input.number == "123")
        input.type("*#")
        #expect(input.number == "123*#")
    }

    @Test func plusOnlyAtTheStart() {
        var input = DialInput()
        input.type("+")
        input.type("44+")
        #expect(input.number == "+44")
        input.type("+")
        #expect(input.number == "+44")
    }

    @Test func atMost32Characters() {
        var input = DialInput()
        input.type(String(repeating: "9", count: 40))
        #expect(input.number.count == DialInput.maxLength)
    }

    @Test func pasteReplacesWithTheCleanedNumber() {
        var input = DialInput("12")
        input.paste("Call me on +1 (555) 123-4567!")
        #expect(input.number == "+15551234567")
        input.paste("no digits here")
        #expect(input.number == "+15551234567") // nothing usable: keep what was there
    }

    @Test func deleteAndClear() {
        var input = DialInput("+12")
        input.deleteLast()
        #expect(input.number == "+1")
        input.clear()
        #expect(input.number.isEmpty)
        input.deleteLast()
        #expect(input.number.isEmpty)
    }

    @Test func canCallOnlyWithAUsableNumber() {
        #expect(!DialInput().canCall)
        #expect(!DialInput("+").canCall)
        #expect(DialInput("*#06#").canCall)
        #expect(DialInput("5551234").canCall)
    }
}

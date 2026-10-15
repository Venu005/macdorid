import AppKit
import MacDroidCore
import SwiftUI

/// The dialer window's content: a phone-style keypad (hold 0 for +, hold ⌫ to clear) and keyboard typing.
struct DialerView: View {
    let model: AppModel
    let close: () -> Void
    @State private var input = DialInput()
    @State private var problem: String?
    @State private var calling = false
    @FocusState private var focused: Bool

    private let rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["*", "0", "#"]]

    var body: some View {
        VStack(spacing: 18) {
            HStack(spacing: 8) {
                Text(input.number.isEmpty ? "Enter a number" : input.number)
                    .font(.system(size: 26, weight: .medium, design: .rounded).monospacedDigit())
                    .foregroundStyle(input.number.isEmpty ? .tertiary : .primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .frame(maxWidth: .infinity)
                Image(systemName: "delete.left")
                    .font(.title3)
                    .foregroundStyle(input.number.isEmpty ? .tertiary : .secondary)
                    .contentShape(Rectangle())
                    .gesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in edit { $0.clear() } }
                        .exclusively(before: TapGesture().onEnded { edit { $0.deleteLast() } }))
                    .help("Delete (hold to clear)")
            }
            .padding(.horizontal, 6)
            Text(problem ?? " ").font(.callout).foregroundStyle(.secondary).lineLimit(2).multilineTextAlignment(.center)
            VStack(spacing: 12) {
                ForEach(rows, id: \.self) { row in
                    HStack(spacing: 22) {
                        ForEach(row, id: \.self) { key in keyCap(key) }
                    }
                }
            }
            Button(action: call) {
                Image(systemName: "phone.fill")
                    .font(.title2)
                    .foregroundStyle(.white)
                    .frame(width: 64, height: 64)
                    .background(Circle().fill(input.canCall && !calling ? Color.green : Color.green.opacity(0.35)))
            }
            .buttonStyle(.plain)
            .disabled(!input.canCall || calling)
            .help("Call on your phone (Return)")
        }
        .padding(24)
        .frame(width: 300, height: 470)
        .focusable()
        .focusEffectDisabled()
        .focused($focused)
        .onAppear { focused = true }
        .onKeyPress(phases: .down) { press in key(press) }
    }

    @ViewBuilder
    private func keyCap(_ key: String) -> some View {
        let face = VStack(spacing: 0) {
            Text(key).font(.system(size: 26, weight: .regular, design: .rounded))
            if key == "0" { Text("+").font(.caption2).foregroundStyle(.secondary) }
        }
        .frame(width: 64, height: 64)
        .background(Circle().fill(Color.secondary.opacity(0.15)))
        .contentShape(Circle())
        if key == "0" {
            // Hold 0 for +, as on a phone.
            face.gesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in edit { $0.type("+") } }
                .exclusively(before: TapGesture().onEnded { edit { $0.type("0") } }))
        } else {
            face.onTapGesture { edit { $0.type(key) } }
        }
    }

    private func key(_ press: KeyPress) -> KeyPress.Result {
        if press.modifiers.contains(.command) {
            guard press.characters == "v" else { return .ignored }
            edit { $0.paste(NSPasteboard.general.string(forType: .string) ?? "") }
            return .handled
        }
        switch press.key {
        case .return: call(); return .handled
        case .escape: close(); return .handled
        case .delete, .deleteForward: edit { $0.deleteLast() }; return .handled
        default:
            let before = input.number
            edit { $0.type(press.characters) }
            return input.number == before ? .ignored : .handled
        }
    }

    private func edit(_ change: (inout DialInput) -> Void) {
        change(&input)
        problem = nil
    }

    private func call() {
        guard input.canCall, !calling else { return }
        calling = true
        Task {
            problem = await model.dial(input.number)
            calling = false
            if problem == nil {
                input.clear()
                close()
            }
        }
    }
}

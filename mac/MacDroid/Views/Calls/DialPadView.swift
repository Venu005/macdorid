import MacDroidCore
import SwiftUI

/// The Phone page's number pad (spec §3.3).
struct DialPadView: View {
    let model: AppModel
    @State private var number = ""
    @State private var problem: String?
    private let keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                TextField("Phone number", text: $number).textFieldStyle(.roundedBorder).font(.title3.monospacedDigit())
                Button { number = String(number.dropLast()) } label: { Image(systemName: "delete.left") }
                    .disabled(number.isEmpty).help("Delete")
                Button { number = PhoneNumbers.clean(NSPasteboard.general.string(forType: .string) ?? "") ?? number } label: {
                    Image(systemName: "doc.on.clipboard")
                }
                .help("Paste a number")
            }
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(56)), count: 3), spacing: 6) {
                ForEach(keys, id: \.self) { key in
                    Button(key) { number += key }.frame(width: 56)
                }
            }
            HStack {
                Button("+") { if number.isEmpty { number = "+" } }.disabled(!number.isEmpty).help("International prefix")
                Spacer()
                Button {
                    Task {
                        problem = await model.dial(number)
                        if problem == nil { number = "" }
                    }
                } label: {
                    Label("Call", systemImage: "phone.fill")
                }
                .buttonStyle(.borderedProminent)
                .tint(.green)
                .disabled(number.isEmpty)
            }
            if let problem { Text(problem).font(.callout).foregroundStyle(.secondary) }
        }
    }
}

import MacDroidCore
import SwiftUI
import UniformTypeIdentifiers

/// The Files page: a drop zone to send, transfers in progress, and recent history (spec §3.2).
struct FilesView: View {
    let model: AppModel
    @State private var targeted = false

    var body: some View {
        let snapshot = model.transfers
        VStack(spacing: 0) {
            if model.files.folderProblem {
                BannerView(kind: .warning, message: "MacDroid can't reach the folder you chose for received files. Choose it again in Settings.")
            }
            if let error = model.transientError { BannerView(kind: .error, message: error) }
            List {
                Section { dropZone }
                if !snapshot.active.isEmpty {
                    Section("In progress") {
                        ForEach(snapshot.active) { TransferRow(record: $0, rate: snapshot.rates[$0.id], model: model) }
                    }
                }
                Section("Recent") {
                    if snapshot.history.isEmpty {
                        Text("Files you send or receive appear here.").foregroundStyle(.secondary)
                    } else {
                        ForEach(snapshot.history) { TransferRow(record: $0, rate: nil, model: model) }
                    }
                }
            }
            .listStyle(.inset)
        }
        .navigationTitle("Files")
    }

    private var dropZone: some View {
        let enabled = model.canSendFiles
        return VStack(spacing: 8) {
            Image(systemName: "arrow.down.doc")
                .font(.system(size: 28))
                .foregroundStyle(enabled ? Color.accentColor : Color.secondary)
            Text(enabled ? "Drop files here to send to \(model.phoneName)" : "Phone not connected").font(.headline)
            Button("Send Files…") { model.pickFilesToSend() }.disabled(!enabled)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
        .background(RoundedRectangle(cornerRadius: 12).fill(targeted ? Color.accentColor.opacity(0.15) : Color.secondary.opacity(0.06)))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .strokeBorder(targeted ? Color.accentColor : Color.secondary.opacity(0.3), style: StrokeStyle(lineWidth: 1.5, dash: [6]))
        )
        .onDrop(of: [.fileURL], isTargeted: $targeted) { _ in
            guard model.canSendFiles else { return false }
            model.sendFiles(DroppedFiles.urls()) // read at drop time, when the sandbox grants access
            return true
        }
    }
}

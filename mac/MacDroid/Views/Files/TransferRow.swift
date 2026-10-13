import MacDroidCore
import SwiftUI

/// One transfer: direction, name, progress or status, and its actions (cancel, Show in Finder/Open, Retry).
struct TransferRow: View {
    let record: TransferRecord
    let rate: Double?
    let model: AppModel
    var compact = false

    var body: some View {
        let row = TransferPresentation.row(record, rate: rate, savedFileExists: model.receivedFileExists(record))
        HStack(spacing: 10) {
            Image(systemName: record.direction == .toPhone ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                .font(.system(size: compact ? 16 : 20))
                .foregroundStyle(row.isError ? Color.red : Color.accentColor)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(row.title).lineLimit(1).truncationMode(.middle)
                    if !compact { Text(row.directionLabel).foregroundStyle(.secondary) }
                }
                if let progress = row.progress { ProgressView(value: progress).controlSize(.small) }
                Text(row.detail)
                    .font(.caption)
                    .foregroundStyle(row.isError ? Color.red : Color.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if row.canOpen {
                Button(compact ? "Show" : "Show in Finder") { model.revealReceived(record) }
                if !compact { Button("Open") { model.openReceived(record) } }
            }
            if row.canRetry { Button("Retry") { model.retryTransfer(record.id) } }
            if row.canCancel {
                Button { model.cancelTransfer(record.id) } label: { Image(systemName: "xmark.circle.fill") }
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
                    .help("Cancel")
            }
        }
        .controlSize(.small)
        .padding(.vertical, compact ? 2 : 4)
    }
}

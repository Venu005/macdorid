import MacDroidCore
import SwiftUI

/// A square thumbnail, or a grey placeholder until it arrives.
struct PhotoThumbnail: View {
    let model: AppModel
    let id: String?

    var body: some View {
        Rectangle()
            .fill(.quaternary)
            .overlay {
                if let id, let image = model.thumbnailImage(id) {
                    Image(nsImage: image).resizable().scaledToFill()
                }
            }
            .clipped()
            .onAppear { if let id { model.needThumbnail(id) } }
    }
}

/// One grid item: click / ⌘-click / ⇧-click select, double-click previews, drag copies the original out (spec §5, §9).
struct PhotoCell: View {
    let model: AppModel
    let item: PhotoItem

    var body: some View {
        let selected = model.photoSelection.contains(item.id)
        PhotoThumbnail(model: model, id: item.id)
            .aspectRatio(1, contentMode: .fit)
            .overlay(alignment: .bottomLeading) {
                if item.kind == .video {
                    Label(item.durationMs.map(PhotosPresentation.duration(ms:)) ?? "", systemImage: "play.fill")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.white)
                        .shadow(color: .black.opacity(0.6), radius: 2)
                        .padding(4)
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, Color.accentColor)
                        .padding(4)
                }
            }
            .overlay {
                if model.photoBusy.contains(item.id) { ProgressView().controlSize(.small) }
            }
            .overlay {
                RoundedRectangle(cornerRadius: 4).strokeBorder(Color.accentColor, lineWidth: selected ? 3 : 0)
            }
            .clipShape(RoundedRectangle(cornerRadius: 4))
            .contentShape(Rectangle())
            .onTapGesture(count: 2) { model.previewPhoto(item) }
            .onTapGesture { model.clickPhoto(item.id) }
            .onDrag { model.dragProvider(for: item) }
            .contextMenu {
                Button("Preview") { model.previewPhoto(item) }
                Button("Save to Mac") {
                    model.photoSelection = [item.id]
                    model.saveSelectedPhotos()
                }
            }
            .help(item.name)
            .accessibilityLabel(item.name)
            .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

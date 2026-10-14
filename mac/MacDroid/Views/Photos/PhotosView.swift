import MacDroidCore
import QuickLook
import SwiftUI

/// The phone's photos (spec §5, layout B): album column, day-grouped grid, floating selection bar.
struct PhotosView: View {
    @Bindable var model: AppModel

    var body: some View {
        let state = model.photos
        Group {
            if !state.connected && state.items.isEmpty {
                ContentUnavailableView("Phone not connected", systemImage: "iphone.slash", description: Text("Your phone's photos appear here when it connects."))
            } else if state.connected && !state.supported {
                ContentUnavailableView("Photos", systemImage: "photo.on.rectangle", description: Text("Update MacDroid on your phone to use this."))
            } else if state.access == PhotoAccess.none {
                ContentUnavailableView("Photos are off", systemImage: "photo", description: Text(PhotosPresentation.banner(access: PhotoAccess.none, visibleCount: 0) ?? ""))
            } else {
                // Disconnected with photos loaded: they stay visible, dimmed, and nothing can be done with them (spec §6).
                HStack(spacing: 0) {
                    AlbumList(model: model).frame(width: 180)
                    Divider()
                    PhotoGrid(model: model)
                }
                .disabled(!state.connected)
                .opacity(state.connected ? 1 : 0.55)
            }
        }
        .toolbar {
            ToolbarItemGroup {
                Picker("Show", selection: Binding(get: { model.photos.kind }, set: { model.setPhotoKind($0) })) {
                    Text("All").tag(PhotoKindFilter.all)
                    Text("Photos").tag(PhotoKindFilter.photos)
                    Text("Videos").tag(PhotoKindFilter.videos)
                }
                .pickerStyle(.segmented)
                .help("Show photos, videos or both")
                .disabled(!state.supported)
                Button { model.saveSelectedPhotos() } label: { Label("Save to Mac", systemImage: "square.and.arrow.down") }
                    .help("Save the selected items to your received-files folder")
                    .disabled(model.photoSelection.isEmpty || !state.supported)
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if !state.connected && !state.items.isEmpty { BannerView(kind: .warning, message: "Phone not connected") }
        }
        .quickLookPreview($model.photoPreview)
    }
}

private struct AlbumList: View {
    let model: AppModel

    var body: some View {
        List(selection: Binding<String?>(get: { model.photos.albumId }, set: { if let id = $0 { model.selectPhotoAlbum(id) } })) {
            ForEach(model.photos.albums) { album in
                HStack(spacing: 8) {
                    PhotoThumbnail(model: model, id: album.coverId)
                        .frame(width: 28, height: 28)
                        .clipShape(RoundedRectangle(cornerRadius: 5))
                    VStack(alignment: .leading, spacing: 1) {
                        Text(album.name).lineLimit(1)
                        Text(album.count.formatted()).font(.caption).foregroundStyle(.secondary)
                    }
                }
                .tag(album.id)
            }
        }
        .listStyle(.sidebar)
    }
}

private struct PhotoGrid: View {
    let model: AppModel

    var body: some View {
        let state = model.photos
        let days = PhotosPresentation.days(state.items, now: Date())
        // The next page loads once a cell in about the last two rows appears (spec §5.2).
        let nearEnd = Set(state.items.suffix(20).map(\.id))
        ZStack(alignment: .bottom) {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if let banner = PhotosPresentation.banner(access: state.access, visibleCount: state.albums.first?.count ?? state.items.count) {
                        BannerView(kind: .warning, message: banner)
                    }
                    if let error = model.photoError { BannerView(kind: .error, message: error) }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 3)], alignment: .leading, spacing: 3, pinnedViews: [.sectionHeaders]) {
                        ForEach(days) { day in
                            Section {
                                ForEach(day.items) { item in
                                    PhotoCell(model: model, item: item)
                                        .onAppear { if state.hasMore && nearEnd.contains(item.id) { model.loadMorePhotos() } }
                                }
                            } header: {
                                Text(day.title)
                                    .font(.headline)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.vertical, 6)
                                    .background(.background)
                            }
                        }
                        if state.hasMore {
                            ProgressView().controlSize(.small).frame(maxWidth: .infinity, minHeight: 96).onAppear { model.loadMorePhotos() }
                        }
                    }
                }
                .padding(12)
                .padding(.bottom, model.photoSelection.isEmpty ? 0 : 56)
            }
            .overlay {
                if state.items.isEmpty {
                    if state.loading {
                        ProgressView()
                    } else {
                        ContentUnavailableView("No photos", systemImage: "photo", description: Text("Photos and videos on your phone appear here."))
                    }
                }
            }
            if !model.photoSelection.isEmpty {
                SelectionBar(model: model).padding(12)
            }
        }
        .focusable()
        .focusEffectDisabled()
        .onKeyPress(.space) { model.previewSelectedPhoto() ? .handled : .ignored }
        .onKeyPress(.escape) {
            model.clearPhotoSelection()
            return .handled
        }
        .onKeyPress(characters: ["a"], phases: .down) { press in
            guard press.modifiers == .command else { return .ignored }
            model.selectAllPhotos()
            return .handled
        }
    }
}

private struct SelectionBar: View {
    let model: AppModel

    var body: some View {
        let selected = model.photos.items.filter { model.photoSelection.contains($0.id) }
        HStack(spacing: 10) {
            Text(PhotosPresentation.selection(count: selected.count, bytes: selected.reduce(0) { $0 + $1.size }))
                .font(.callout.weight(.semibold))
            Spacer()
            Button("Preview") { if let first = selected.first { model.previewPhoto(first) } }
                .disabled(selected.count != 1)
            Button("Save to Mac") { model.saveSelectedPhotos() }
                .buttonStyle(.borderedProminent)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
        .shadow(color: .black.opacity(0.25), radius: 10, y: 4)
    }
}

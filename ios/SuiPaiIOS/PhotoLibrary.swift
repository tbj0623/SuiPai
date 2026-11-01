import Foundation
import Photos
import UIKit
import Combine

/// 按天分组的一段照片，供网格的 section header 使用。
struct DayGroup: Identifiable {
    let day: Date
    let assets: [PHAsset]
    var id: Date { day }
}

/// PHPhotoLibrary / PHImageManager 的封装。
/// 分页策略：PHFetchResult 本身是惰性的，但 SwiftUI 需要确定数量的元素，
/// 所以先物化成数组，再用 displayCount 控制「已展示」的上限，底部加载更多。
final class PhotoLibraryService: ObservableObject {
    static let shared = PhotoLibraryService()

    @Published private(set) var assets: [PHAsset] = []
    @Published private(set) var isLoading = false
    @Published private(set) var errorMessage: String?
    @Published var displayCount: Int = 0

    let pageSize = 400
    private var fetchResult: PHFetchResult<PHAsset>?
    private let thumbCache = NSCache<NSString, UIImage>()

    private init() {
        thumbCache.countLimit = 600
    }

    var totalCount: Int { assets.count }
    var hasMore: Bool { displayCount < assets.count }

    // MARK: - 列表

    func reload() {
        isLoading = true
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            let opts = PHFetchOptions()
            opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
            opts.predicate = NSPredicate(format: "mediaType == %d", PHAssetMediaType.image.rawValue)
            let result = PHAsset.fetchAssets(with: opts)
            var list: [PHAsset] = []
            list.reserveCapacity(result.count)
            result.enumerateObjects { asset, _, _ in list.append(asset) }
            DispatchQueue.main.async {
                self.fetchResult = result
                self.assets = list
                self.displayCount = min(self.pageSize, list.count)
                self.isLoading = false
                self.errorMessage = nil
            }
        }
    }

    func loadMore() {
        guard hasMore else { return }
        displayCount = min(displayCount + pageSize, assets.count)
    }

    func index(of asset: PHAsset) -> Int? {
        assets.firstIndex { $0.localIdentifier == asset.localIdentifier }
    }

    /// 只对「已展示」的区间分组，避免为几万张照片预先算日期。
    func dayGroups(limit: Int) -> [DayGroup] {
        let n = min(limit, assets.count)
        guard n > 0 else { return [] }
        let cal = Calendar.current
        var groups: [DayGroup] = []
        var currentDay: Date?
        var bucket: [PHAsset] = []
        for i in 0..<n {
            let asset = assets[i]
            let day = cal.startOfDay(for: asset.creationDate ?? Date(timeIntervalSince1970: 0))
            if day != currentDay {
                if let d = currentDay, !bucket.isEmpty {
                    groups.append(DayGroup(day: d, assets: bucket))
                }
                currentDay = day
                bucket = []
            }
            bucket.append(asset)
        }
        if let d = currentDay, !bucket.isEmpty {
            groups.append(DayGroup(day: d, assets: bucket))
        }
        return groups
    }

    // MARK: - 缩略图

    func thumbnail(for asset: PHAsset, size: CGSize, completion: @escaping (UIImage?) -> Void) {
        let key = "\(asset.localIdentifier)-\(Int(size.width))x\(Int(size.height))" as NSString
        if let cached = thumbCache.object(forKey: key) {
            completion(cached)
            return
        }
        let opts = PHImageRequestOptions()
        opts.isNetworkAccessAllowed = true
        opts.deliveryMode = .opportunistic
        opts.resizeMode = .fast
        let scale = UIScreen.main.scale
        let target = CGSize(width: size.width * scale, height: size.height * scale)
        PHImageManager.default().requestImage(for: asset,
                                              targetSize: target,
                                              contentMode: .aspectFill,
                                              options: opts) { [weak self] image, _ in
            if let image = image {
                self?.thumbCache.setObject(image, forKey: key)
            }
            // 降级图也交给 UI 先显示，避免 iCloud 图一直空着
            DispatchQueue.main.async { completion(image) }
        }
    }

    func filename(for asset: PHAsset) -> String {
        if let res = PHAssetResource.assetResources(for: asset).first {
            return res.originalFilename
        }
        return "IMG.jpg"
    }

    // MARK: - 保存到「SuiPai」相簿

    func saveImageData(_ data: Data, filename: String, albumName: String) async -> Bool {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        guard status == .authorized || status == .limited else { return false }
        let albumId = await ensureAlbum(named: albumName)
        do {
            try await PHPhotoLibrary.shared().performChanges {
                let request = PHAssetCreationRequest.forAsset()
                let opts = PHAssetResourceCreationOptions()
                opts.originalFilename = filename
                request.addResource(with: .photo, data: data, options: opts)
                if let albumId = albumId,
                   let album = PhotoLibraryService.fetchAlbum(id: albumId),
                   let placeholder = request.placeholderForCreatedAsset,
                   let change = PHAssetCollectionChangeRequest(for: album) {
                    change.addAssets([placeholder] as NSArray)
                }
            }
            return true
        } catch {
            return false
        }
    }

    /// 相簿不存在就建一个，返回 localIdentifier 供后续引用。
    private func ensureAlbum(named name: String) async -> String? {
        if let existing = PhotoLibraryService.fetchAlbum(named: name) {
            return existing.localIdentifier
        }
        do {
            var createdId: String?
            try await PHPhotoLibrary.shared().performChanges {
                let request = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: name)
                createdId = request.placeholderForCreatedAssetCollection.localIdentifier
            }
            return createdId
        } catch {
            return nil
        }
    }

    private static func fetchAlbum(named name: String) -> PHAssetCollection? {
        let opts = PHFetchOptions()
        opts.predicate = NSPredicate(format: "title = %@", name)
        return PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: opts).firstObject
    }

    private static func fetchAlbum(id: String) -> PHAssetCollection? {
        PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [id], options: nil).firstObject
    }
}
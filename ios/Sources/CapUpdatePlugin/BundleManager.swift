import CommonCrypto
import Foundation
import SSZipArchive

/// Manages downloading, extracting, and tracking OTA bundles on the filesystem.
public class BundleManager {

    public static let shared = BundleManager()

    private static let bundlesDirName = "cap_update_bundles"

    public init() {}

    /// Root directory where all bundles are stored.
    public func getBundlesDir() -> URL {
        let filesDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let dir = filesDir.appendingPathComponent(Self.bundlesDirName, isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    /// Download a ZIP from the given URL, extract it, and return the resolved bundle ID.
    public func downloadAndExtract(urlString: String, bundleId: String?, checksum: String?) throws -> String {
        guard let url = URL(string: urlString) else {
            throw BundleError.invalidUrl
        }

        // Synchronous download (called from background queue)
        let (data, response, error) = URLSession.shared.synchronousDataTask(with: url)

        if let error = error {
            throw BundleError.downloadFailed(error.localizedDescription)
        }

        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            throw BundleError.downloadFailed("HTTP \(code)")
        }

        guard let data = data else {
            throw BundleError.downloadFailed("No data received")
        }

        // Resolve bundle ID
        var resolvedId = bundleId ?? ""
        if resolvedId.isEmpty {
            // Try response header, then derive from URL
            if let headerBundleId = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "X-Bundle-Id"),
               !headerBundleId.isEmpty {
                resolvedId = headerBundleId
            } else {
                resolvedId = deriveBundleId(from: urlString)
            }
        }

        // Write to temp file
        let tempDir = FileManager.default.temporaryDirectory
        let tempZip = tempDir.appendingPathComponent("cap_update_\(resolvedId).zip")
        try data.write(to: tempZip)

        // Verify checksum
        if let checksum = checksum, !checksum.isEmpty {
            let computed = try computeSha256(file: tempZip)
            if computed.lowercased() != checksum.lowercased() {
                try? FileManager.default.removeItem(at: tempZip)
                throw BundleError.checksumMismatch(expected: checksum, actual: computed)
            }
        }

        // Prepare target directory
        let targetDir = getBundlesDir().appendingPathComponent(resolvedId, isDirectory: true)
        if FileManager.default.fileExists(atPath: targetDir.path) {
            try FileManager.default.removeItem(at: targetDir)
        }
        try FileManager.default.createDirectory(at: targetDir, withIntermediateDirectories: true)

        // Extract using SSZipArchive
        let success = SSZipArchive.unzipFile(atPath: tempZip.path, toDestination: targetDir.path)
        try? FileManager.default.removeItem(at: tempZip)

        if !success {
            try? FileManager.default.removeItem(at: targetDir)
            throw BundleError.extractionFailed
        }

        return resolvedId
    }

    /// Get the absolute path to a bundle's directory, or nil if it doesn't exist.
    public func getBundlePath(bundleId: String) -> String? {
        let dir = getBundlesDir().appendingPathComponent(bundleId, isDirectory: true)
        return FileManager.default.fileExists(atPath: dir.path) ? dir.path : nil
    }

    /// List all downloaded bundle IDs.
    public func getBundleList() -> [String] {
        let dir = getBundlesDir()
        guard let contents = try? FileManager.default.contentsOfDirectory(
            at: dir,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }

        return contents.compactMap { url in
            var isDir: ObjCBool = false
            if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue {
                return url.lastPathComponent
            }
            return nil
        }
    }

    /// Delete a bundle directory.
    public func deleteBundle(bundleId: String) {
        let dir = getBundlesDir().appendingPathComponent(bundleId, isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
    }

    /// Recursively search for the directory containing index.html.
    public func findWebRoot(dir: URL) -> URL? {
        let indexUrl = dir.appendingPathComponent("index.html")
        if FileManager.default.fileExists(atPath: indexUrl.path) {
            return dir
        }

        guard let contents = try? FileManager.default.contentsOfDirectory(
            at: dir,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else {
            return nil
        }

        for url in contents {
            var isDir: ObjCBool = false
            if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue {
                if let found = findWebRoot(dir: url) {
                    return found
                }
            }
        }

        return nil
    }

    // MARK: - Private Helpers

    private func deriveBundleId(from urlString: String) -> String {
        var filename = (urlString as NSString).lastPathComponent

        // Strip query string
        if let queryIndex = filename.firstIndex(of: "?") {
            filename = String(filename[..<queryIndex])
        }

        // Strip .zip extension
        if filename.lowercased().hasSuffix(".zip") {
            filename = String(filename.dropLast(4))
        }

        // Sanitize
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._-"))
        return filename.unicodeScalars.map { allowed.contains($0) ? String($0) : "_" }.joined()
    }

    private func computeSha256(file: URL) throws -> String {
        let data = try Data(contentsOf: file)
        var hash = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes { buffer in
            _ = CC_SHA256(buffer.baseAddress, CC_LONG(data.count), &hash)
        }
        return hash.map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - Error Types

enum BundleError: LocalizedError {
    case invalidUrl
    case downloadFailed(String)
    case checksumMismatch(expected: String, actual: String)
    case extractionFailed

    var errorDescription: String? {
        switch self {
        case .invalidUrl:
            return "Invalid download URL"
        case .downloadFailed(let reason):
            return "Download failed: \(reason)"
        case .checksumMismatch(let expected, let actual):
            return "Checksum mismatch. Expected: \(expected), got: \(actual)"
        case .extractionFailed:
            return "Failed to extract ZIP archive"
        }
    }
}

// MARK: - URLSession Synchronous Extension

extension URLSession {
    func synchronousDataTask(with url: URL) -> (Data?, URLResponse?, Error?) {
        var data: Data?
        var response: URLResponse?
        var error: Error?

        let semaphore = DispatchSemaphore(value: 0)
        let task = self.dataTask(with: url) { d, r, e in
            data = d
            response = r
            error = e
            semaphore.signal()
        }
        task.resume()
        semaphore.wait()

        return (data, response, error)
    }
}

import Capacitor
import Foundation
import UIKit

@objc(CapUpdatePlugin)
public class CapUpdatePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "CapUpdatePlugin"
    public let jsName = "CapUpdate"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "downloadBundle", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setBundle", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getBundle", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getBundles", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "deleteBundle", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reset", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reload", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkForUpdate", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "sync", returnType: CAPPluginReturnPromise),
    ]

    private let defaults = UserDefaults.standard
    private let bundleManager = BundleManager()

    private static let keyActiveBundle = "cap_update_active_bundle_id"
    private static let keyActivePath = "cap_update_active_bundle_path"

    // MARK: - Lifecycle

    override public func load() {
        // Restore persisted bundle BEFORE the WebView loads its initial URL.
        guard let persistedPath = defaults.string(forKey: Self.keyActivePath),
              let persistedBundle = defaults.string(forKey: Self.keyActiveBundle) else {
            return
        }

        let dir = URL(fileURLWithPath: persistedPath)
        if FileManager.default.fileExists(atPath: dir.path) {
            bridge?.setServerBasePath(persistedPath)
        } else {
            // Bundle directory was deleted — clear stale prefs
            clearActiveBundle()
        }
    }

    // MARK: - Bundle Management

    @objc func downloadBundle(_ call: CAPPluginCall) {
        guard let url = call.getString("url"), !url.isEmpty else {
            call.reject("url is required")
            return
        }
        let bundleId = call.getString("bundleId")
        let checksum = call.getString("checksum")

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let resolvedId = try self.bundleManager.downloadAndExtract(
                    urlString: url, bundleId: bundleId, checksum: checksum
                )
                call.resolve([
                    "bundleId": resolvedId,
                    "status": "downloaded"
                ])
            } catch {
                call.reject("Download failed: \(error.localizedDescription)")
            }
        }
    }

    @objc func setBundle(_ call: CAPPluginCall) {
        guard let bundleId = call.getString("bundleId"), !bundleId.isEmpty else {
            call.reject("bundleId is required")
            return
        }
        let immediate = call.getBool("immediate") ?? false

        guard let bundlePath = bundleManager.getBundlePath(bundleId: bundleId) else {
            call.reject("Bundle not found: \(bundleId)")
            return
        }

        // Find web root (directory containing index.html)
        let rootUrl = URL(fileURLWithPath: bundlePath)
        let webRoot = bundleManager.findWebRoot(dir: rootUrl) ?? rootUrl
        let resolvedPath = webRoot.path

        // Persist
        defaults.set(bundleId, forKey: Self.keyActiveBundle)
        defaults.set(resolvedPath, forKey: Self.keyActivePath)

        // Point Capacitor's built-in server to the new directory
        bridge?.setServerBasePath(resolvedPath)

        if immediate {
            DispatchQueue.main.async {
                self.bridge?.webView?.reload()
            }
        }

        call.resolve()
    }

    @objc func getBundle(_ call: CAPPluginCall) {
        if let activeId = defaults.string(forKey: Self.keyActiveBundle) {
            call.resolve([
                "bundleId": activeId,
                "status": "active"
            ])
        } else {
            call.resolve([
                "bundleId": "built-in",
                "status": "built-in"
            ])
        }
    }

    @objc func getBundles(_ call: CAPPluginCall) {
        let bundles = bundleManager.getBundleList()
        let activeId = defaults.string(forKey: Self.keyActiveBundle)

        let items: [[String: Any]] = bundles.map { id in
            return [
                "bundleId": id,
                "status": id == activeId ? "active" : "downloaded"
            ]
        }

        call.resolve(["bundles": items])
    }

    @objc func deleteBundle(_ call: CAPPluginCall) {
        guard let bundleId = call.getString("bundleId"), !bundleId.isEmpty else {
            call.reject("bundleId is required")
            return
        }

        let activeId = defaults.string(forKey: Self.keyActiveBundle)
        if bundleId == activeId {
            call.reject("Cannot delete the currently active bundle. Call reset() first.")
            return
        }

        bundleManager.deleteBundle(bundleId: bundleId)
        call.resolve()
    }

    // MARK: - Lifecycle Control

    @objc func reset(_ call: CAPPluginCall) {
        let immediate = call.getBool("immediate") ?? false

        clearActiveBundle()

        // Reset Capacitor's server to default bundled assets
        bridge?.setServerAssetPath("public")

        if immediate {
            DispatchQueue.main.async {
                self.bridge?.webView?.reload()
            }
        }

        call.resolve()
    }

    @objc func reload(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.bridge?.webView?.reload()
        }
        call.resolve()
    }

    // MARK: - Automated Updates

    @objc func checkForUpdate(_ call: CAPPluginCall) {
        performUpdateCheck(call: call) { result in
            call.resolve(result)
        }
    }

    @objc func sync(_ call: CAPPluginCall) {
        performUpdateCheck(call: call) { data in
            let isUpdateAvailable = data["isUpdateAvailable"] as? Bool ?? false
            guard isUpdateAvailable, let downloadUrl = data["downloadUrl"] as? String else {
                call.resolve(["updated": false])
                return
            }

            // Extract bundle ID from server response
            let latestBundle = data["latestBundle"] as? [String: Any]
            let bundleId = latestBundle?["id"].flatMap { "\($0)" }

            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    let resolvedId = try self.bundleManager.downloadAndExtract(
                        urlString: downloadUrl, bundleId: bundleId, checksum: nil
                    )

                    guard let bundlePath = self.bundleManager.getBundlePath(bundleId: resolvedId) else {
                        call.reject("Bundle downloaded but path not found")
                        return
                    }

                    let rootUrl = URL(fileURLWithPath: bundlePath)
                    let webRoot = self.bundleManager.findWebRoot(dir: rootUrl) ?? rootUrl
                    let resolvedPath = webRoot.path

                    self.defaults.set(resolvedId, forKey: Self.keyActiveBundle)
                    self.defaults.set(resolvedPath, forKey: Self.keyActivePath)

                    DispatchQueue.main.async {
                        self.bridge?.setServerBasePath(resolvedPath)
                        self.bridge?.webView?.reload()
                    }

                    var result: [String: Any] = ["updated": true]
                    if let bundle = latestBundle {
                        result["latestBundle"] = bundle
                    }
                    call.resolve(result)

                } catch {
                    call.reject("Sync failed: \(error.localizedDescription)")
                }
            }
        }
    }

    // MARK: - Private Helpers

    private func clearActiveBundle() {
        defaults.removeObject(forKey: Self.keyActiveBundle)
        defaults.removeObject(forKey: Self.keyActivePath)
    }

    private func performUpdateCheck(call: CAPPluginCall, completion: @escaping ([String: Any]) -> Void) {
        guard let urlString = call.getString("url"), !urlString.isEmpty else {
            call.reject("url is required")
            return
        }
        let channel = call.getString("channel") ?? "production"

        guard let url = URL(string: urlString) else {
            call.reject("Invalid URL")
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 30

        // Device metadata headers
        let deviceId = UIDevice.current.identifierForVendor?.uuidString ?? "unknown"
        request.addValue(deviceId, forHTTPHeaderField: "X-Device-Identifier")
        request.addValue("ios", forHTTPHeaderField: "X-Platform")
        request.addValue(defaults.string(forKey: Self.keyActiveBundle) ?? "", forHTTPHeaderField: "X-Bundle-Id")
        request.addValue(channel, forHTTPHeaderField: "X-Channel")

        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            if let error = error {
                call.reject("Update check failed: \(error.localizedDescription)")
                return
            }

            guard let data = data else {
                call.reject("No data received from update server")
                return
            }

            do {
                guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    call.reject("Invalid JSON response")
                    return
                }

                var result: [String: Any] = [:]
                result["isUpdateAvailable"] = json["is_update_available"] ?? false
                result["latestBundle"] = json["latest_bundle"]
                result["currentBundle"] = json["current_bundle"]
                result["downloadUrl"] = json["download_url"]

                completion(result)
            } catch {
                call.reject("JSON parsing error: \(error.localizedDescription)")
            }
        }
        task.resume()
    }
}

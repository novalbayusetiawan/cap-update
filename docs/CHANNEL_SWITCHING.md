# Channel-Switching & Update Architecture

This document describes how `cap-update` manages multiple deployment channels (e.g., `production`, `staging`) and handles channel-less bundles.

---

## 1. The Channel-Switching Problem

Previously, the plugin only tracked a single active bundle ID globally on the device. When switching channels:
1. Device syncs `production` → applies production bundle (e.g., ID `5`).
2. Device syncs `staging` → applies staging bundle (e.g., ID `8`).
3. Device syncs `production` again → the plugin sent the active bundle ID (`8`) as `X-Bundle-Id`.
4. The update server compared the production channel's latest bundle (`5`) against the device's reported active bundle (`8`). Since `5` is not greater than `8`, the server reported "no update available", leaving the device stuck on the staging channel bundle.

---

## 2. The Solution: Per-Channel Bundle Tracking

To resolve this, the plugin tracks which bundle was last activated **per channel** using local native preferences:
- **iOS**: A dictionary stored in `UserDefaults` (`cap_update_channel_bundles`) mapping `channel_name -> bundle_id`.
- **Android**: Custom key-value pairs in `SharedPreferences` (`channel_bundle_{channel} -> bundle_id`).

### The Sync Cycle
When calling `sync({ channel: "production" })` or `checkForUpdate({ channel: "production" })`:
1. The plugin retrieves the last known bundle ID for `production` (e.g. `"5"`) and sends it in the `X-Channel-Bundle-Id` header.
2. The plugin also sends the globally active bundle ID in `X-Bundle-Id` for backward compatibility.
3. The server compares the production channel's latest bundle specifically against `X-Channel-Bundle-Id` (using inequality `!=` to support rollbacks).
4. If the server detects a mismatch, it returns `is_update_available: true`.

---

## 3. Local Caching & Fast Activation

If a channel switch is required (i.e. the globally active bundle is different from the target channel's latest bundle) but the target bundle is **already downloaded on disk**:
* Instead of initiating a download, `sync()` automatically activates the locally cached bundle directory via `setBundle` logic and reloads the WebView immediately.
* This makes channel switching instant and works offline.

---

## 4. Channel-Less / Manual Bundle Compatibility

The channel-specific tracking only overlays on top of the automated sync check. It does **not** restrict manual bundle management:
* **Manual Activation**: Calling `setBundle({ bundleId })` directly sets the global active bundle without modifying any channel-specific mappings.
* **Fallback Behavior**: If a user runs a manual bundle and then calls `sync({ channel })` for the first time, `X-Channel-Bundle-Id` will be empty. The server falls back to comparing against `X-Bundle-Id` (the manual bundle) and will return the channel's latest bundle for download/activation.

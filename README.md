# ⚡ Cap Update

A Capacitor native plugin for **over-the-air (OTA) live updates**. Download, manage, and apply web bundle updates at runtime — without rebuilding or resubmitting your app.

Uses Capacitor's built-in `Bridge.setServerBasePath()` API to swap web assets. **No local HTTP server**, no port conflicts, no cleartext traffic — just native file serving through Capacitor's own engine.

[![npm](https://img.shields.io/npm/v/cap-update)](https://www.npmjs.com/package/cap-update)
[![Capacitor](https://img.shields.io/badge/capacitor-v7+-blue)](https://capacitorjs.com)

---

## Why Cap Update?

| Feature | Cap Update | Local HTTP Server Approach |
|---|---|---|
| **Dependencies** | None (uses Capacitor Bridge) | NanoHTTPD / GCDWebServer |
| **Port conflicts** | Impossible | Risk of port in use |
| **HTTPS** | Default `https://localhost` | Needs cleartext `http://` |
| **Plugin compat** | All Capacitor plugins work | May break (different origin) |
| **App Store** | No extra review flags | Embedded server may flag |
| **Complexity** | Minimal | Server lifecycle management |

---

## Install

```bash
npm install cap-update
npx cap sync
```

### iOS

No additional configuration required. The plugin uses SSZipArchive for ZIP extraction (included automatically).

### Android

No additional configuration required. Uses Java's built-in `java.util.zip` — zero extra dependencies.

---

## Quick Start

```typescript
import { CapUpdate } from 'cap-update';

// Full automated update cycle
const result = await CapUpdate.sync({
  url: 'https://your-server.com/api/updates/check',
  channel: 'production',
});

if (result.updated) {
  console.log('App updated to latest bundle!');
}
```

---

## API Reference

### `downloadBundle(options)`

Download a ZIP bundle from a URL and extract it locally.

```typescript
await CapUpdate.downloadBundle({
  url: 'https://your-server.com/bundles/v1.2.0.zip',
  bundleId: 'v1.2.0',
  checksum: 'sha256-abc123...',  // optional integrity check
});
```

| Param | Type | Description |
|---|---|---|
| `url` | `string` | **(required)** URL of the ZIP bundle |
| `bundleId` | `string` | Identifier for this bundle. Auto-generated from URL if omitted |
| `checksum` | `string` | SHA-256 checksum for integrity verification |

**Returns:** `Promise<BundleInfo>`

---

### `setBundle(options)`

Set a downloaded bundle as the active bundle. By default, it takes effect on the next app restart. Set `immediate: true` to reload the WebView immediately.

```typescript
// Apply on next restart
await CapUpdate.setBundle({ bundleId: 'v1.2.0' });

// Apply immediately (reloads WebView)
await CapUpdate.setBundle({ bundleId: 'v1.2.0', immediate: true });
```

| Param | Type | Default | Description |
|---|---|---|---|
| `bundleId` | `string` | — | **(required)** ID of the downloaded bundle |
| `immediate` | `boolean` | `false` | Reload WebView immediately |

---

### `getBundle()`

Get info about the currently active bundle.

```typescript
const info = await CapUpdate.getBundle();
// { bundleId: 'v1.2.0', path: '/data/.../bundles/v1.2.0', status: 'active' }
// { bundleId: 'built-in', status: 'active' }  // when using default
```

**Returns:** `Promise<BundleInfo>`

---

### `getBundles()`

List all downloaded bundles on the device.

```typescript
const { bundles } = await CapUpdate.getBundles();
// [{ bundleId: 'v1.1.0', status: 'downloaded' }, { bundleId: 'v1.2.0', status: 'active' }]
```

**Returns:** `Promise<{ bundles: BundleInfo[] }>`

---

### `deleteBundle(options)`

Delete a specific downloaded bundle. Cannot delete the currently active bundle.

```typescript
await CapUpdate.deleteBundle({ bundleId: 'v1.1.0' });
```

---

### `reset(options?)`

Reset to the built-in bundle (the one shipped with the app binary).

```typescript
// Reset and reload immediately
await CapUpdate.reset({ immediate: true });

// Reset on next restart
await CapUpdate.reset();
```

---

### `reload()`

Reload the WebView. Useful after calling `setBundle()` without `immediate: true`.

```typescript
await CapUpdate.reload();
```

---

### `sync(options)`

Automated update cycle: check for updates → download → apply → reload.

```typescript
const result = await CapUpdate.sync({
  url: 'https://your-server.com/api/updates/check',
  channel: 'production',
});

console.log(result.updated);       // boolean
console.log(result.latestBundle);   // bundle metadata from server
```

| Param | Type | Default | Description |
|---|---|---|---|
| `url` | `string` | — | **(required)** Update check endpoint URL |
| `channel` | `string` | `'production'` | Deployment channel |

**Returns:** `Promise<SyncResult>`

---

### `checkForUpdate(options)`

Check if an update is available without downloading it.

```typescript
const result = await CapUpdate.checkForUpdate({
  url: 'https://your-server.com/api/updates/check',
  channel: 'staging',
});

if (result.isUpdateAvailable) {
  console.log('New version:', result.latestBundle);
  console.log('Download from:', result.downloadUrl);
}
```

**Returns:** `Promise<CheckUpdateResult>`

---

## Update Server Protocol

The `sync()` and `checkForUpdate()` methods send a `GET` request to your endpoint with these headers:

| Header | Description |
|---|---|
| `X-Device-Identifier` | Unique device ID |
| `X-Platform` | `android` or `ios` |
| `X-Bundle-Id` | Currently active bundle ID |
| `X-Channel` | Deployment channel |

### Expected JSON Response

```json
{
  "is_update_available": true,
  "latest_bundle": {
    "id": "v1.2.0",
    "version": "1.2.0"
  },
  "current_bundle": {
    "id": "v1.1.0"
  },
  "download_url": "https://your-server.com/bundles/v1.2.0.zip"
}
```

---

## Types

```typescript
interface BundleInfo {
  bundleId: string;
  status: 'active' | 'downloaded' | 'built-in';
}

interface DownloadBundleOptions {
  url: string;
  bundleId?: string;
  checksum?: string;
}

interface SetBundleOptions {
  bundleId: string;
  immediate?: boolean;
}

interface SyncOptions {
  url: string;
  channel?: string;
}

interface SyncResult {
  updated: boolean;
  latestBundle?: any;
}

interface CheckUpdateResult {
  isUpdateAvailable: boolean;
  latestBundle?: any;
  currentBundle?: any;
  downloadUrl?: string;
}
```

---

## How It Works

```
┌─────────────────────────────────────────────────┐
│  Your App (WebView)                             │
│  Served via Capacitor's built-in local server   │
│  URL: https://localhost (unchanged)             │
└──────────────────┬──────────────────────────────┘
                   │
          bridge.setServerBasePath(path)
                   │
     ┌─────────────┴─────────────┐
     │  Default: assets/public   │  ← Built-in bundle (APK/IPA)
     │  Custom:  files/bundles/  │  ← Downloaded OTA bundles
     └───────────────────────────┘
```

1. **Download** — ZIP bundle is downloaded and extracted to `{app_files}/cap_update_bundles/{bundleId}/`
2. **Set** — `bridge.setServerBasePath(path)` tells Capacitor's server to serve from the new directory
3. **Persist** — The path is saved so it survives app restarts
4. **Reload** — WebView reloads, now serving the updated assets

No secondary HTTP server. No port management. Just Capacitor doing what it already does — serving files — from a different folder.

---

## Compatibility

| Platform | Supported |
|---|---|
| Android | ✅ Capacitor 7+ |
| iOS | ✅ Capacitor 7+ |
| Web | ⚠️ No-op (logs warnings) |

---

## License

MIT
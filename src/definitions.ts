/**
 * Information about a bundle.
 */
export interface BundleInfo {
  /**
   * The unique identifier for this bundle.
   * Returns `'built-in'` when using the default app bundle.
   */
  bundleId: string;
  /**
   * Current status of this bundle.
   */
  status: 'active' | 'downloaded' | 'built-in';
}

/**
 * Options for downloading a bundle.
 */
export interface DownloadBundleOptions {
  /**
   * The URL of the ZIP bundle to download.
   */
  url: string;
  /**
   * A unique identifier for this bundle.
   * If omitted, it will be derived from the URL filename.
   */
  bundleId?: string;
  /**
   * SHA-256 checksum for integrity verification.
   * Download will be rejected if the checksum does not match.
   */
  checksum?: string;
}

/**
 * Options for setting the active bundle.
 */
export interface SetBundleOptions {
  /**
   * The ID of a previously downloaded bundle to activate.
   */
  bundleId: string;
  /**
   * If `true`, the WebView will reload immediately after setting the bundle.
   * If `false` (default), the bundle will be applied on the next app restart.
   * @default false
   */
  immediate?: boolean;
}

/**
 * Options for update checking and sync.
 */
export interface SyncOptions {
  /**
   * The URL of the update check endpoint.
   */
  url: string;
  /**
   * The deployment channel to check (e.g. `'production'`, `'staging'`).
   * @default 'production'
   */
  channel?: string;
}

/**
 * Result returned from `sync()`.
 */
export interface SyncResult {
  /**
   * Whether a new bundle was downloaded and applied.
   */
  updated: boolean;
  /**
   * Metadata of the applied bundle, if an update occurred.
   */
  latestBundle?: Record<string, unknown>;
}

/**
 * Result returned from `checkForUpdate()`.
 */
export interface CheckUpdateResult {
  /**
   * Whether a newer bundle is available on the server.
   */
  isUpdateAvailable: boolean;
  /**
   * Metadata of the latest available bundle.
   */
  latestBundle?: Record<string, unknown>;
  /**
   * Metadata of the currently active bundle.
   */
  currentBundle?: Record<string, unknown>;
  /**
   * Direct download URL for the latest bundle ZIP.
   */
  downloadUrl?: string;
}

/**
 * Options for resetting to the built-in bundle.
 */
export interface ResetOptions {
  /**
   * If `true`, the WebView will reload immediately after reset.
   * @default false
   */
  immediate?: boolean;
}

export interface CapUpdatePlugin {
  /**
   * Download a ZIP bundle from a URL and extract it locally.
   *
   * @param options - Download configuration.
   * @returns Info about the downloaded bundle.
   */
  downloadBundle(options: DownloadBundleOptions): Promise<BundleInfo>;

  /**
   * Set a downloaded bundle as the active bundle.
   * Uses `Bridge.setServerBasePath()` internally — no local HTTP server.
   *
   * @param options - Which bundle to activate and whether to reload immediately.
   */
  setBundle(options: SetBundleOptions): Promise<void>;

  /**
   * Get info about the currently active bundle.
   *
   * @returns The active bundle info. Returns `bundleId: 'built-in'` when using the default.
   */
  getBundle(): Promise<BundleInfo>;

  /**
   * List all downloaded bundles on the device.
   *
   * @returns An object containing an array of bundle info.
   */
  getBundles(): Promise<{ bundles: BundleInfo[] }>;

  /**
   * Delete a specific downloaded bundle.
   * Cannot delete the currently active bundle.
   *
   * @param options - Which bundle to delete.
   */
  deleteBundle(options: { bundleId: string }): Promise<void>;

  /**
   * Reset to the built-in bundle shipped with the app binary.
   *
   * @param options - Whether to reload immediately.
   */
  reset(options?: ResetOptions): Promise<void>;

  /**
   * Reload the WebView. Useful after `setBundle()` without `immediate: true`.
   */
  reload(): Promise<void>;

  /**
   * Check if an update is available on the server without downloading.
   *
   * @param options - Server URL and channel.
   * @returns Update availability and bundle metadata.
   */
  checkForUpdate(options: SyncOptions): Promise<CheckUpdateResult>;

  /**
   * Full automated update cycle: check → download → apply → reload.
   *
   * @param options - Server URL and channel.
   * @returns Whether an update was applied.
   */
  sync(options: SyncOptions): Promise<SyncResult>;
}

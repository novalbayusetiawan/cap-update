import { WebPlugin } from '@capacitor/core';

import type {
  BundleInfo,
  CapUpdatePlugin,
  CheckUpdateResult,
  DownloadBundleOptions,
  ResetOptions,
  SetBundleOptions,
  SyncOptions,
  SyncResult,
} from './definitions';

export class CapUpdateWeb extends WebPlugin implements CapUpdatePlugin {
  private getActiveBundleId(): string {
    return localStorage.getItem('cap_update_active_bundle') || 'built-in';
  }

  private getDownloadedBundles(): string[] {
    try {
      return JSON.parse(localStorage.getItem('cap_update_bundles') || '[]');
    } catch {
      return [];
    }
  }

  private saveDownloadedBundles(bundles: string[]) {
    localStorage.setItem('cap_update_bundles', JSON.stringify(bundles));
  }

  private getChannelBundles(): Record<string, string> {
    try {
      return JSON.parse(localStorage.getItem('cap_update_channel_bundles') || '{}');
    } catch {
      return {};
    }
  }

  private saveChannelBundles(mapping: Record<string, string>) {
    localStorage.setItem('cap_update_channel_bundles', JSON.stringify(mapping));
  }

  private deriveBundleId(url: string): string {
    let filename = url.substring(url.lastIndexOf('/') + 1);
    const queryIndex = filename.indexOf('?');
    if (queryIndex > 0) {
      filename = filename.substring(0, queryIndex);
    }
    if (filename.toLowerCase().endsWith('.zip')) {
      filename = filename.substring(0, filename.length - 4);
    }
    return filename.replace(/[^a-zA-Z0-9._-]/g, '_');
  }

  async downloadBundle(options: DownloadBundleOptions): Promise<BundleInfo> {
    const bundleId = options.bundleId || this.deriveBundleId(options.url);
    const bundles = this.getDownloadedBundles();
    if (!bundles.includes(bundleId)) {
      bundles.push(bundleId);
      this.saveDownloadedBundles(bundles);
    }
    return { bundleId, status: 'downloaded' };
  }

  async setBundle(options: SetBundleOptions): Promise<void> {
    const bundles = this.getDownloadedBundles();
    if (options.bundleId !== 'built-in' && !bundles.includes(options.bundleId)) {
      throw new Error(`Bundle not found: ${options.bundleId}`);
    }
    localStorage.setItem('cap_update_active_bundle', options.bundleId);
    if (options.immediate) {
      this.reload();
    }
  }

  async getBundle(): Promise<BundleInfo> {
    const bundleId = this.getActiveBundleId();
    return {
      bundleId,
      status: bundleId === 'built-in' ? 'built-in' : 'active',
    };
  }

  async getBundles(): Promise<{ bundles: BundleInfo[] }> {
    const activeId = this.getActiveBundleId();
    const bundles = this.getDownloadedBundles();
    const list: BundleInfo[] = bundles.map((id) => ({
      bundleId: id,
      status: id === activeId ? 'active' : 'downloaded',
    }));
    return { bundles: list };
  }

  async deleteBundle(options: { bundleId: string }): Promise<void> {
    const activeId = this.getActiveBundleId();
    if (options.bundleId === activeId) {
      throw new Error('Cannot delete the currently active bundle. Call reset() first.');
    }
    let bundles = this.getDownloadedBundles();
    bundles = bundles.filter((id) => id !== options.bundleId);
    this.saveDownloadedBundles(bundles);
  }

  async reset(options?: ResetOptions): Promise<void> {
    localStorage.setItem('cap_update_active_bundle', 'built-in');
    if (options?.immediate) {
      this.reload();
    }
  }

  async reload(): Promise<void> {
    window.location.reload();
  }

  async checkForUpdate(options: SyncOptions): Promise<CheckUpdateResult> {
    const channel = options.channel || 'production';
    const activeId = this.getActiveBundleId();
    const channelBundles = this.getChannelBundles();
    const channelBundleId = channelBundles[channel] || '';

    try {
      const response = await fetch(options.url, {
        method: 'GET',
        headers: {
          'X-Device-Identifier': 'web-device',
          'X-Platform': 'web',
          'X-Bundle-Id': activeId,
          'X-Channel-Bundle-Id': channelBundleId,
          'X-Channel': channel,
        },
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const json = await response.json();
      const result: CheckUpdateResult = {
        isUpdateAvailable: json.is_update_available || false,
        latestBundle: json.latest_bundle,
        currentBundle: json.current_bundle,
        downloadUrl: json.download_url,
      };

      // If server reports no update, update mapping
      if (!result.isUpdateAvailable && result.latestBundle?.id) {
        channelBundles[channel] = String(result.latestBundle.id);
        this.saveChannelBundles(channelBundles);
      }

      return result;
    } catch (e: any) {
      console.error('checkForUpdate error:', e);
      return { isUpdateAvailable: false };
    }
  }

  async sync(options: SyncOptions): Promise<SyncResult> {
    const channel = options.channel || 'production';
    const check = await this.checkForUpdate(options);

    const latestBundleId = check.latestBundle?.id ? String(check.latestBundle.id) : null;
    if (!latestBundleId) {
      return { updated: false };
    }

    const activeId = this.getActiveBundleId();
    const needsUpdate = check.isUpdateAvailable;
    const needsChannelSwitch = activeId !== latestBundleId;

    if (!needsUpdate && !needsChannelSwitch) {
      return { updated: false };
    }

    const bundles = this.getDownloadedBundles();
    const channelBundles = this.getChannelBundles();

    // Check if already downloaded
    if (bundles.includes(latestBundleId)) {
      localStorage.setItem('cap_update_active_bundle', latestBundleId);
      channelBundles[channel] = latestBundleId;
      this.saveChannelBundles(channelBundles);
      this.reload();
      return { updated: true, latestBundle: check.latestBundle };
    }

    // Otherwise download (simulate it on web)
    if (!check.downloadUrl) {
      return { updated: false };
    }

    bundles.push(latestBundleId);
    this.saveDownloadedBundles(bundles);
    localStorage.setItem('cap_update_active_bundle', latestBundleId);
    channelBundles[channel] = latestBundleId;
    this.saveChannelBundles(channelBundles);
    this.reload();

    return { updated: true, latestBundle: check.latestBundle };
  }
}

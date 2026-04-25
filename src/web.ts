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
  async downloadBundle(_options: DownloadBundleOptions): Promise<BundleInfo> {
    console.warn('CapUpdate: downloadBundle is not supported on web.');
    return { bundleId: 'built-in', status: 'built-in' };
  }

  async setBundle(_options: SetBundleOptions): Promise<void> {
    console.warn('CapUpdate: setBundle is not supported on web.');
  }

  async getBundle(): Promise<BundleInfo> {
    return { bundleId: 'built-in', status: 'built-in' };
  }

  async getBundles(): Promise<{ bundles: BundleInfo[] }> {
    return { bundles: [] };
  }

  async deleteBundle(_options: { bundleId: string }): Promise<void> {
    console.warn('CapUpdate: deleteBundle is not supported on web.');
  }

  async reset(_options?: ResetOptions): Promise<void> {
    console.warn('CapUpdate: reset is not supported on web.');
  }

  async reload(): Promise<void> {
    window.location.reload();
  }

  async checkForUpdate(_options: SyncOptions): Promise<CheckUpdateResult> {
    console.warn('CapUpdate: checkForUpdate is not supported on web.');
    return { isUpdateAvailable: false };
  }

  async sync(_options: SyncOptions): Promise<SyncResult> {
    console.warn('CapUpdate: sync is not supported on web.');
    return { updated: false };
  }
}

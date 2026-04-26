import './style.css';
import { CapUpdate } from 'cap-update';

// ── State ──
let logs: string[] = [];

function log(msg: string) {
  const ts = new Date().toLocaleTimeString();
  logs.unshift(`[${ts}] ${msg}`);
  if (logs.length > 50) logs.pop();
  renderLogs();
}

function renderLogs() {
  const el = document.getElementById('logs');
  if (el) {
    el.innerHTML = logs.map((l) => `<div class="log-entry">${l}</div>`).join('');
  }
}

// ── Actions ──

async function handleGetBundle() {
  try {
    const info = await CapUpdate.getBundle();
    log(`Current bundle: <strong>${info.bundleId}</strong> (${info.status})`);
  } catch (e: any) {
    log(`❌ getBundle error: ${e.message}`);
  }
}

async function handleGetBundles() {
  try {
    const { bundles } = await CapUpdate.getBundles();
    if (bundles.length === 0) {
      log('No bundles downloaded yet.');
    } else {
      log(`Found <strong>${bundles.length}</strong> bundle(s):`);
      bundles.forEach((b) => log(`  • ${b.bundleId} [${b.status}]`));
    }
  } catch (e: any) {
    log(`❌ getBundles error: ${e.message}`);
  }
}

async function handleDownload() {
  const urlInput = document.getElementById('download-url') as HTMLInputElement;
  const idInput = document.getElementById('bundle-id') as HTMLInputElement;
  const url = urlInput.value.trim();
  const bundleId = idInput.value.trim() || undefined;

  if (!url) {
    log('❌ Please enter a download URL.');
    return;
  }

  log(`⬇️ Downloading from ${url}...`);
  try {
    const info = await CapUpdate.downloadBundle({ url, bundleId });
    log(`✅ Downloaded: <strong>${info.bundleId}</strong>`);
  } catch (e: any) {
    log(`❌ Download failed: ${e.message}`);
  }
}

async function handleSetBundle() {
  const input = document.getElementById('set-bundle-id') as HTMLInputElement;
  const bundleId = input.value.trim();
  if (!bundleId) {
    log('❌ Please enter a bundle ID to set.');
    return;
  }

  log(`🔄 Setting bundle: ${bundleId}...`);
  try {
    await CapUpdate.setBundle({ bundleId, immediate: true });
    log(`✅ Bundle set and reloading.`);
  } catch (e: any) {
    log(`❌ setBundle error: ${e.message}`);
  }
}

async function handleDeleteBundle() {
  const input = document.getElementById('delete-bundle-id') as HTMLInputElement;
  const bundleId = input.value.trim();
  if (!bundleId) {
    log('❌ Please enter a bundle ID to delete.');
    return;
  }

  try {
    await CapUpdate.deleteBundle({ bundleId });
    log(`🗑️ Deleted: ${bundleId}`);
  } catch (e: any) {
    log(`❌ deleteBundle error: ${e.message}`);
  }
}

async function handleReset() {
  log('♻️ Resetting to built-in bundle...');
  try {
    await CapUpdate.reset({ immediate: true });
    log('✅ Reset complete. Reloading...');
  } catch (e: any) {
    log(`❌ reset error: ${e.message}`);
  }
}

async function handleReload() {
  log('🔃 Reloading WebView...');
  try {
    await CapUpdate.reload();
  } catch (e: any) {
    log(`❌ reload error: ${e.message}`);
  }
}

async function handleSync() {
  const urlInput = document.getElementById('sync-url') as HTMLInputElement;
  const channelInput = document.getElementById('sync-channel') as HTMLInputElement;
  const url = urlInput.value.trim();
  const channel = channelInput.value.trim() || 'production';

  if (!url) {
    log('❌ Please enter a sync URL.');
    return;
  }

  log(`🔄 Syncing (channel: ${channel})...`);
  try {
    const result = await CapUpdate.sync({ url, channel });
    if (result.updated) {
      log(`✅ Updated! New bundle applied.`);
    } else {
      log(`ℹ️ No update available.`);
    }
  } catch (e: any) {
    log(`❌ sync error: ${e.message}`);
  }
}

async function handleCheckUpdate() {
  const urlInput = document.getElementById('sync-url') as HTMLInputElement;
  const channelInput = document.getElementById('sync-channel') as HTMLInputElement;
  const url = urlInput.value.trim();
  const channel = channelInput.value.trim() || 'production';

  if (!url) {
    log('❌ Please enter a check URL.');
    return;
  }

  log(`🔍 Checking for updates...`);
  try {
    const result = await CapUpdate.checkForUpdate({ url, channel });
    log(`Update available: <strong>${result.isUpdateAvailable}</strong>`);
    if (result.downloadUrl) log(`Download URL: ${result.downloadUrl}`);
    if (result.latestBundle) log(`Latest: ${JSON.stringify(result.latestBundle)}`);
  } catch (e: any) {
    log(`❌ checkForUpdate error: ${e.message}`);
  }
}

// ── Render ──

document.querySelector<HTMLDivElement>('#app')!.innerHTML = `
  <div class="container">
    <header>
      <div class="header-icon">⚡</div>
      <h1>Cap Update v0.0.1</h1>
      <p class="subtitle">Live OTA Bundle Manager</p>
    </header>

    <!-- Current Bundle -->
    <section class="card">
      <h2>📦 Current Bundle</h2>
      <div class="btn-row">
        <button id="btn-get-bundle" class="btn primary">Get Bundle</button>
        <button id="btn-get-bundles" class="btn">List All</button>
      </div>
    </section>

    <!-- Download Bundle -->
    <section class="card">
      <h2>⬇️ Download Bundle</h2>
      <input id="download-url" type="url" placeholder="https://your-server.com/bundle.zip" />
      <input id="bundle-id" type="text" placeholder="Bundle ID (optional)" />
      <button id="btn-download" class="btn primary">Download & Extract</button>
    </section>

    <!-- Set Bundle -->
    <section class="card">
      <h2>🔄 Set Active Bundle</h2>
      <input id="set-bundle-id" type="text" placeholder="Bundle ID" />
      <button id="btn-set" class="btn primary">Set & Reload</button>
    </section>

    <!-- Delete Bundle -->
    <section class="card">
      <h2>🗑️ Delete Bundle</h2>
      <input id="delete-bundle-id" type="text" placeholder="Bundle ID" />
      <button id="btn-delete" class="btn danger">Delete</button>
    </section>

    <!-- Sync / Check Update -->
    <section class="card">
      <h2>🔄 Sync / Check Update</h2>
      <input id="sync-url" type="url" placeholder="https://your-server.com/api/updates/check" />
      <input id="sync-channel" type="text" placeholder="Channel (default: production)" />
      <div class="btn-row">
        <button id="btn-check" class="btn">Check Update</button>
        <button id="btn-sync" class="btn primary">Full Sync</button>
      </div>
    </section>

    <!-- Actions -->
    <section class="card">
      <h2>⚙️ Actions</h2>
      <div class="btn-row">
        <button id="btn-reset" class="btn danger">Reset to Default</button>
        <button id="btn-reload" class="btn">Reload WebView</button>
      </div>
    </section>

    <!-- Log Output -->
    <section class="card log-card">
      <h2>📋 Log</h2>
      <div id="logs" class="log-container"></div>
    </section>

    <footer>
      <p>cap-update v0.0.1 • Capacitor v7+</p>
    </footer>
  </div>
`;

// ── Event Listeners ──
document.getElementById('btn-get-bundle')!.addEventListener('click', handleGetBundle);
document.getElementById('btn-get-bundles')!.addEventListener('click', handleGetBundles);
document.getElementById('btn-download')!.addEventListener('click', handleDownload);
document.getElementById('btn-set')!.addEventListener('click', handleSetBundle);
document.getElementById('btn-delete')!.addEventListener('click', handleDeleteBundle);
document.getElementById('btn-reset')!.addEventListener('click', handleReset);
document.getElementById('btn-reload')!.addEventListener('click', handleReload);
document.getElementById('btn-sync')!.addEventListener('click', handleSync);
document.getElementById('btn-check')!.addEventListener('click', handleCheckUpdate);

// ── Init ──
log('App loaded. Ready to manage bundles.');
handleGetBundle();

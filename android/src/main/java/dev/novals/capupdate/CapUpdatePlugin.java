package dev.novals.capupdate;

import android.content.Context;
import android.content.SharedPreferences;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.util.List;

@CapacitorPlugin(name = "CapUpdate")
public class CapUpdatePlugin extends Plugin {

    private static final String PREFS_NAME = "cap_update_prefs";
    private static final String KEY_ACTIVE_BUNDLE = "active_bundle_id";
    private static final String KEY_ACTIVE_PATH = "active_bundle_path";

    private BundleManager bundleManager;

    @Override
    public void load() {
        super.load();
        bundleManager = new BundleManager(getContext());

        // Restore persisted bundle on app startup.
        // This runs BEFORE the WebView loads its initial URL,
        // so setServerBasePath takes effect for the first page load.
        String persistedPath = getPrefs().getString(KEY_ACTIVE_PATH, null);
        String persistedBundle = getPrefs().getString(KEY_ACTIVE_BUNDLE, null);

        if (persistedPath != null && persistedBundle != null) {
            File dir = new File(persistedPath);
            if (dir.exists() && dir.isDirectory()) {
                getBridge().setServerBasePath(persistedPath);
            } else {
                // Bundle directory was deleted externally — clear stale prefs
                clearActiveBundle();
            }
        }
    }

    private SharedPreferences getPrefs() {
        return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ──────────────────────────────────────────────
    // Bundle Management
    // ──────────────────────────────────────────────

    @PluginMethod
    public void downloadBundle(PluginCall call) {
        String url = call.getString("url");
        String bundleId = call.getString("bundleId");
        String checksum = call.getString("checksum");

        if (url == null || url.isEmpty()) {
            call.reject("url is required");
            return;
        }

        new Thread(() -> {
            try {
                String resolvedId = bundleManager.downloadAndExtract(url, bundleId, checksum);
                JSObject ret = new JSObject();
                ret.put("bundleId", resolvedId);
                ret.put("status", "downloaded");
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Download failed: " + e.getMessage(), e);
            }
        }).start();
    }

    @PluginMethod
    public void setBundle(PluginCall call) {
        String bundleId = call.getString("bundleId");
        Boolean immediate = call.getBoolean("immediate", false);

        if (bundleId == null || bundleId.isEmpty()) {
            call.reject("bundleId is required");
            return;
        }

        String bundlePath = bundleManager.getBundlePath(bundleId);
        if (bundlePath == null) {
            call.reject("Bundle not found: " + bundleId);
            return;
        }

        // Find the web root (directory containing index.html)
        File rootDir = new File(bundlePath);
        File webRoot = bundleManager.findWebRoot(rootDir);
        if (webRoot == null) {
            webRoot = rootDir;
        }

        String resolvedPath = webRoot.getAbsolutePath();

        // Persist the active bundle
        getPrefs().edit()
                .putString(KEY_ACTIVE_BUNDLE, bundleId)
                .putString(KEY_ACTIVE_PATH, resolvedPath)
                .commit();

        // Point Capacitor's built-in server to the new directory
        getBridge().setServerBasePath(resolvedPath);

        if (Boolean.TRUE.equals(immediate)) {
            getBridge().reload();
        }

        call.resolve();
    }

    @PluginMethod
    public void getBundle(PluginCall call) {
        String activeId = getPrefs().getString(KEY_ACTIVE_BUNDLE, null);

        JSObject ret = new JSObject();
        if (activeId != null) {
            ret.put("bundleId", activeId);
            ret.put("status", "active");
        } else {
            ret.put("bundleId", "built-in");
            ret.put("status", "built-in");
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void getBundles(PluginCall call) {
        List<String> bundles = bundleManager.getBundleList();
        String activeId = getPrefs().getString(KEY_ACTIVE_BUNDLE, null);

        JSArray array = new JSArray();
        for (String id : bundles) {
            JSObject item = new JSObject();
            item.put("bundleId", id);
            item.put("status", id.equals(activeId) ? "active" : "downloaded");
            array.put(item);
        }

        JSObject ret = new JSObject();
        ret.put("bundles", array);
        call.resolve(ret);
    }

    @PluginMethod
    public void deleteBundle(PluginCall call) {
        String bundleId = call.getString("bundleId");
        if (bundleId == null || bundleId.isEmpty()) {
            call.reject("bundleId is required");
            return;
        }

        String activeId = getPrefs().getString(KEY_ACTIVE_BUNDLE, null);
        if (bundleId.equals(activeId)) {
            call.reject("Cannot delete the currently active bundle. Call reset() first.");
            return;
        }

        bundleManager.deleteBundle(bundleId);
        call.resolve();
    }

    // ──────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────

    @PluginMethod
    public void reset(PluginCall call) {
        Boolean immediate = call.getBoolean("immediate", false);

        clearActiveBundle();

        // Reset Capacitor's server to serve from the default APK assets
        getBridge().setServerAssetPath("public");

        if (Boolean.TRUE.equals(immediate)) {
            getBridge().reload();
        }

        call.resolve();
    }

    @PluginMethod
    public void reload(PluginCall call) {
        getBridge().reload();
        call.resolve();
    }

    // ──────────────────────────────────────────────
    // Automated Updates
    // ──────────────────────────────────────────────

    @PluginMethod
    public void checkForUpdate(PluginCall call) {
        performUpdateCheck(call, (result) -> call.resolve(result));
    }

    @PluginMethod
    public void sync(PluginCall call) {
        performUpdateCheck(call, (data) -> {
            Boolean isUpdate = data.getBool("isUpdateAvailable");
            boolean isUpdateAvailable = isUpdate != null && isUpdate;
            String downloadUrl = data.getString("downloadUrl");

            if (!isUpdateAvailable || downloadUrl == null) {
                JSObject ret = new JSObject();
                ret.put("updated", false);
                call.resolve(ret);
                return;
            }

            // Extract bundle ID from server response
            JSObject latestBundle = data.getJSObject("latestBundle");
            String bundleId = null;
            if (latestBundle != null) {
                bundleId = latestBundle.optString("id", null);
            }

            final String finalBundleId = bundleId;

            new Thread(() -> {
                try {
                    String resolvedId = bundleManager.downloadAndExtract(downloadUrl, finalBundleId, null);

                    // Apply the new bundle immediately
                    String bundlePath = bundleManager.getBundlePath(resolvedId);
                    if (bundlePath != null) {
                        File rootDir = new File(bundlePath);
                        File webRoot = bundleManager.findWebRoot(rootDir);
                        if (webRoot == null) {
                            webRoot = rootDir;
                        }

                        String resolvedPath = webRoot.getAbsolutePath();

                        getPrefs().edit()
                                .putString(KEY_ACTIVE_BUNDLE, resolvedId)
                                .putString(KEY_ACTIVE_PATH, resolvedPath)
                                .commit();

                        getActivity().runOnUiThread(() -> {
                            getBridge().setServerBasePath(resolvedPath);
                            getBridge().reload();
                        });

                        JSObject ret = new JSObject();
                        ret.put("updated", true);
                        ret.put("latestBundle", latestBundle);
                        call.resolve(ret);
                    } else {
                        call.reject("Bundle downloaded but path not found");
                    }
                } catch (Exception e) {
                    call.reject("Sync failed: " + e.getMessage(), e);
                }
            }).start();
        });
    }

    // ──────────────────────────────────────────────
    // Internal Helpers
    // ──────────────────────────────────────────────

    private void clearActiveBundle() {
        getPrefs().edit()
                .remove(KEY_ACTIVE_BUNDLE)
                .remove(KEY_ACTIVE_PATH)
                .apply();
    }

    private interface UpdateCheckCallback {
        void onResult(JSObject data);
    }

    private void performUpdateCheck(PluginCall call, UpdateCheckCallback callback) {
        String urlString = call.getString("url");
        String channel = call.getString("channel", "production");

        if (urlString == null || urlString.isEmpty()) {
            call.reject("url is required");
            return;
        }

        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(urlString);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");

                // Send device metadata headers
                String deviceId = android.provider.Settings.Secure.getString(
                        getContext().getContentResolver(),
                        android.provider.Settings.Secure.ANDROID_ID
                );
                conn.setRequestProperty("X-Device-Identifier", deviceId != null ? deviceId : "unknown");
                conn.setRequestProperty("X-Platform", "android");
                conn.setRequestProperty("X-Bundle-Id", getPrefs().getString(KEY_ACTIVE_BUNDLE, ""));
                conn.setRequestProperty("X-Channel", channel);

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    call.reject("Update check failed with HTTP " + responseCode);
                    return;
                }

                java.io.BufferedReader in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream())
                );
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    response.append(line);
                }
                in.close();

                org.json.JSONObject json = new org.json.JSONObject(response.toString());
                JSObject result = new JSObject();
                result.put("isUpdateAvailable", json.optBoolean("is_update_available", false));

                org.json.JSONObject latestBundle = json.optJSONObject("latest_bundle");
                if (latestBundle != null) {
                    result.put("latestBundle", JSObject.fromJSONObject(latestBundle));
                }

                org.json.JSONObject currentBundle = json.optJSONObject("current_bundle");
                if (currentBundle != null) {
                    result.put("currentBundle", JSObject.fromJSONObject(currentBundle));
                }

                result.put("downloadUrl", json.optString("download_url", null));
                callback.onResult(result);

            } catch (Exception e) {
                call.reject("Update check error: " + e.getMessage(), e);
            }
        }).start();
    }
}

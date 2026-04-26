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

    // Capacitor's built-in preferences for the WebView
    private static final String CAP_WEBVIEW_PREFS = "CapWebViewSettings";
    private static final String CAP_SERVER_PATH = "serverBasePath";

    private BundleManager bundleManager;

    @Override
    public void load() {
        super.load();
        bundleManager = new BundleManager(getContext());

        // Capacitor natively handles restoring the server path from CapWebViewSettings.
        // We only need to check if our bundle is still valid, and if not, clear it.
        String persistedPath = getPrefs().getString(KEY_ACTIVE_PATH, null);
        String persistedBundle = getPrefs().getString(KEY_ACTIVE_BUNDLE, null);

        if (persistedPath != null && persistedBundle != null) {
            File dir = new File(persistedPath);
            if (!dir.exists() || !dir.isDirectory()) {
                android.util.Log.w("CapUpdate", "Bundle path missing on startup: " + persistedPath);
                clearActiveBundle();
            } else {
                android.util.Log.d("CapUpdate", "Active bundle on startup: " + persistedBundle);
            }
        }
    }

    private SharedPreferences getPrefs() {
        return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private SharedPreferences getCapWebViewPrefs() {
        return getContext().getSharedPreferences(CAP_WEBVIEW_PREFS, Context.MODE_PRIVATE);
    }

    // ──────────────────────────────────────────────
    // Bundle Management
    // ──────────────────────────────────────────────

    @PluginMethod
    public void downloadBundle(PluginCall call) {
        String url = call.getString("url");
        String bundleId = call.getString("bundleId");
        String checksum = call.getString("checksum");

        if (url == null) {
            call.reject("Must provide a download URL");
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
        Boolean immediateObj = call.getBoolean("immediate");
        boolean immediate = immediateObj != null && immediateObj;

        if (bundleId == null) {
            call.reject("Must provide a bundleId");
            return;
        }

        new Thread(() -> {
            String bundlePath = bundleManager.getBundlePath(bundleId);
            if (bundlePath != null) {
                File rootDir = new File(bundlePath);
                File webRoot = bundleManager.findWebRoot(rootDir);
                String resolvedPath = (webRoot != null ? webRoot : rootDir).getAbsolutePath();

                // Save to our plugin preferences
                getPrefs().edit()
                        .putString(KEY_ACTIVE_BUNDLE, bundleId)
                        .putString(KEY_ACTIVE_PATH, resolvedPath)
                        .commit();

                // Save to Capacitor's built-in preferences so it loads on next cold start automatically
                getCapWebViewPrefs().edit()
                        .putString(CAP_SERVER_PATH, resolvedPath)
                        .commit();

                if (immediate) {
                    getActivity().runOnUiThread(() -> {
                        getBridge().setServerBasePath(resolvedPath);
                        getBridge().reload();
                    });
                }
                call.resolve();
            } else {
                call.reject("Bundle not found: " + bundleId);
            }
        }).start();
    }

    @PluginMethod
    public void getBundle(PluginCall call) {
        String activeBundle = getPrefs().getString(KEY_ACTIVE_BUNDLE, null);
        JSObject ret = new JSObject();
        ret.put("bundleId", activeBundle != null ? activeBundle : "built-in");
        ret.put("status", activeBundle != null ? "active" : "built-in");
        call.resolve(ret);
    }

    @PluginMethod
    public void getBundles(PluginCall call) {
        String activeBundle = getPrefs().getString(KEY_ACTIVE_BUNDLE, null);
        JSArray bundles = new JSArray();

        for (String bundleId : bundleManager.getBundleList()) {
            JSObject bundle = new JSObject();
            bundle.put("bundleId", bundleId);
            bundle.put("status", bundleId.equals(activeBundle) ? "active" : "downloaded");
            bundles.put(bundle);
        }

        JSObject ret = new JSObject();
        ret.put("bundles", bundles);
        call.resolve(ret);
    }

    @PluginMethod
    public void deleteBundle(PluginCall call) {
        String bundleId = call.getString("bundleId");
        String activeBundle = getPrefs().getString(KEY_ACTIVE_BUNDLE, null);

        if (bundleId == null) {
            call.reject("Must provide a bundleId");
            return;
        }

        if (bundleId.equals(activeBundle)) {
            call.reject("Cannot delete the active bundle");
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
        Boolean immediateObj = call.getBoolean("immediate");
        boolean immediate = immediateObj != null && immediateObj;

        clearActiveBundle();

        if (immediate) {
            getActivity().runOnUiThread(() -> {
                getBridge().setServerAssetPath("public");
                getBridge().reload();
            });
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
            String bundleId = latestBundle != null ? latestBundle.optString("id", null) : null;

            final String finalBundleId = bundleId;
            final JSObject finalLatestBundle = latestBundle;

            new Thread(() -> {
                try {
                    String resolvedId = bundleManager.downloadAndExtract(downloadUrl, finalBundleId, null);

                    // Resolve the web root path
                    String bundlePath = bundleManager.getBundlePath(resolvedId);
                    if (bundlePath != null) {
                        File rootDir = new File(bundlePath);
                        File webRoot = bundleManager.findWebRoot(rootDir);
                        String resolvedPath = (webRoot != null ? webRoot : rootDir).getAbsolutePath();

                        // 1. Save to plugin prefs
                        getPrefs().edit()
                                .putString(KEY_ACTIVE_BUNDLE, resolvedId)
                                .putString(KEY_ACTIVE_PATH, resolvedPath)
                                .commit();

                        // 2. Save to Capacitor built-in prefs
                        getCapWebViewPrefs().edit()
                                .putString(CAP_SERVER_PATH, resolvedPath)
                                .commit();

                        // 3. Resolve the call BEFORE the bridge reloads
                        // (Once the bridge reloads, this JS context is destroyed)
                        JSObject ret = new JSObject();
                        ret.put("updated", true);
                        ret.put("latestBundle", finalLatestBundle);
                        call.resolve(ret);

                        // 4. Trigger reload on UI thread
                        getActivity().runOnUiThread(() -> {
                            getBridge().setServerBasePath(resolvedPath);
                            getBridge().reload();
                        });
                    } else {
                        call.reject("Bundle downloaded but path not found");
                    }
                } catch (Exception e) {
                    android.util.Log.e("CapUpdate", "Sync error: " + e.getMessage());
                    call.reject("Sync failed: " + e.getMessage());
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

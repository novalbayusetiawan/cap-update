package dev.novals.capupdate;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manages downloading, extracting, and tracking OTA bundles on the filesystem.
 * Uses only built-in Java ZIP APIs — no external dependencies.
 */
public class BundleManager {

    private static final String BUNDLES_DIR = "cap_update_bundles";
    private final Context context;

    public BundleManager(Context context) {
        this.context = context;
    }

    /**
     * Get the root directory where all bundles are stored.
     */
    public File getBundlesDir() {
        File dir = new File(context.getFilesDir(), BUNDLES_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Download a ZIP from the given URL, extract it, and return the resolved bundle ID.
     *
     * @param urlString  The download URL.
     * @param bundleId   Optional bundle ID. If null, derived from URL filename.
     * @param checksum   Optional SHA-256 checksum for integrity verification.
     * @return The resolved bundle ID.
     */
    public String downloadAndExtract(String urlString, String bundleId, String checksum) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
        connection.connect();

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new Exception("Server returned HTTP " + responseCode + " " + connection.getResponseMessage());
        }

        // Resolve bundle ID
        if (bundleId == null || bundleId.isEmpty()) {
            // Try to get from response header first
            String headerBundleId = connection.getHeaderField("X-Bundle-Id");
            if (headerBundleId != null && !headerBundleId.isEmpty()) {
                bundleId = headerBundleId;
            } else {
                bundleId = deriveBundleIdFromUrl(urlString);
            }
        }

        // Download to temp file
        File tempZip = new File(context.getCacheDir(), "cap_update_" + bundleId + ".zip");
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(tempZip)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }

        // Verify checksum if provided
        if (checksum != null && !checksum.isEmpty()) {
            String computed = computeSha256(tempZip);
            if (!computed.equalsIgnoreCase(checksum)) {
                tempZip.delete();
                throw new Exception("Checksum mismatch. Expected: " + checksum + ", got: " + computed);
            }
        }

        // Prepare target directory (overwrite if exists)
        File targetDir = new File(getBundlesDir(), bundleId);
        if (targetDir.exists()) {
            deleteRecursive(targetDir);
        }
        targetDir.mkdirs();

        // Extract
        unzip(tempZip, targetDir);
        tempZip.delete();

        return bundleId;
    }

    /**
     * Get the absolute path to a bundle's directory, or null if it doesn't exist.
     */
    public String getBundlePath(String bundleId) {
        File dir = new File(getBundlesDir(), bundleId);
        return dir.exists() ? dir.getAbsolutePath() : null;
    }

    /**
     * List all downloaded bundle IDs.
     */
    public List<String> getBundleList() {
        List<String> list = new ArrayList<>();
        File[] files = getBundlesDir().listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    list.add(f.getName());
                }
            }
        }
        return list;
    }

    /**
     * Delete a bundle's directory.
     */
    public void deleteBundle(String bundleId) {
        File dir = new File(getBundlesDir(), bundleId);
        if (dir.exists()) {
            deleteRecursive(dir);
        }
    }

    /**
     * Recursively search for the directory containing index.html.
     * Handles cases where the ZIP has a wrapper directory.
     */
    public File findWebRoot(File dir) {
        if (new File(dir, "index.html").exists()) {
            return dir;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    File found = findWebRoot(child);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    // ──────────────────────────────────────────────
    // Private Helpers
    // ──────────────────────────────────────────────

    private String deriveBundleIdFromUrl(String url) {
        String filename = url.substring(url.lastIndexOf('/') + 1);
        // Strip query string
        int queryIndex = filename.indexOf('?');
        if (queryIndex > 0) {
            filename = filename.substring(0, queryIndex);
        }
        // Strip .zip extension
        if (filename.toLowerCase().endsWith(".zip")) {
            filename = filename.substring(0, filename.length() - 4);
        }
        // Sanitize
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void unzip(File zipFile, File targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File file = new File(targetDir, entry.getName());

                // Security: prevent zip slip attack
                String canonicalTarget = targetDir.getCanonicalPath();
                String canonicalFile = file.getCanonicalPath();
                if (!canonicalFile.startsWith(canonicalTarget + File.separator) && !canonicalFile.equals(canonicalTarget)) {
                    throw new IOException("Zip entry outside target directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    file.mkdirs();
                } else {
                    // Ensure parent dirs exist
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, count);
                        }
                    }
                }
            }
        }
    }

    private String computeSha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
        }
        return sb.toString();
    }

    private void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDir.delete();
    }
}

package com.example.loginapp.update;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.example.loginapp.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

public final class UpdateManager {

    // ===== Config =====
    private static final String MANIFEST_URL =
            "https://690eee55bd0fefc30a0617ce.mockapi.io/api/app_version";

    private static final String PREFS = "verdi_update";
    private static final String K_LAST_PROMPTED_API_VERSION = "last_prompted_api_version";

    public interface Ui { void onBusy(String msg); void onIdle(); }

    // ===== Public entrypoint =====
    public static void checkAndPrompt(Activity activity, Ui ui) {
        new Thread(() -> {
            try {
                UpdateInfo info = fetchManifest();
                int current = BuildConfig.VERSION_CODE;

                activity.runOnUiThread(() -> {
                    // Always prompt for a forced update
                    if (current < info.minSupported) {
                        showForcedDialog(activity, info);
                        return;
                    }

                    // Optional update: only if API version changed since last prompt
                    if (current < info.latest && shouldPromptForThisApiVersion(activity, info.latest)) {
                        showOptionalDialog(activity, info);
                        // Remember we prompted for this server version
                        setLastPromptedApiVersion(activity, info.latest);
                    }
                });
            } catch (Exception ignore) { /* offline or endpoint down – skip */ }
        }).start();
    }

    // ===== Cache helpers =====
    private static boolean shouldPromptForThisApiVersion(Context c, int apiVersion) {
        return getLastPromptedApiVersion(c) != apiVersion;
    }

    private static int getLastPromptedApiVersion(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(K_LAST_PROMPTED_API_VERSION, -1);
    }

    private static void setLastPromptedApiVersion(Context c, int apiVersion) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(K_LAST_PROMPTED_API_VERSION, apiVersion).apply();
    }

    // ===== Networking =====
    private static UpdateInfo fetchManifest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            String json = readAll(in);
            JSONArray arr = new JSONArray(json);
            JSONObject o = arr.getJSONObject(0);
            int latest = o.getInt("versionCode");
            int min = o.getInt("minSupportedVersionCode");
            String url = o.getString("apkUrl");
            String notes = o.optString("changelog", "");
            return new UpdateInfo(latest, min, url, notes);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString();
    }

    // ===== Dialogs =====
    private static void showOptionalDialog(Activity a, UpdateInfo info) {
        new AlertDialog.Builder(a)
                .setTitle("Update available")
                .setMessage(info.notes.isEmpty() ? "A new version is ready." : info.notes)
                .setPositiveButton("Update", (d, w) -> startDownload(a, info))
                .setNegativeButton("Later", null)
                .show();
    }

    private static void showForcedDialog(Activity a, UpdateInfo info) {
        new AlertDialog.Builder(a)
                .setTitle("Update required")
                .setCancelable(false)
                .setMessage(info.notes.isEmpty() ? "Please update to continue." : info.notes)
                .setPositiveButton("Update now", (d, w) -> startDownload(a, info))
                .show();
    }

    // ===== Download + install =====
    private static void startDownload(Activity a, UpdateInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !a.getPackageManager().canRequestPackageInstalls()) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
            Toast.makeText(a, "Enable \"Allow from this source\", then tap Update again.", Toast.LENGTH_LONG).show();
            return;
        }

        DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri = Uri.parse(info.apkUrl);
        String fileName = "verdi-update.apk";

        DownloadManager.Request req = new DownloadManager.Request(uri)
                .setTitle("Downloading update")
                .setDescription("Verdi Driver")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true);

        long id = dm.enqueue(req);

        BroadcastReceiver r = new BroadcastReceiver() {
            @SuppressLint("Range")
            @Override public void onReceive(Context context, Intent intent) {
                long doneId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (doneId != id) return;
                a.unregisterReceiver(this);

                DownloadManager.Query q = new DownloadManager.Query().setFilterById(doneId);
                try (Cursor c = dm.query(q)) {
                    if (c != null && c.moveToFirst()
                            && c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) {

                        Uri apkUri = dm.getUriForDownloadedFile(doneId);
                        if (apkUri == null) {
                            String localUri = c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                            if (localUri != null && localUri.startsWith("file:")) {
                                File f = new File(Uri.parse(localUri).getPath());
                                apkUri = FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", f);
                            }
                        }
                        if (apkUri != null) {
                            maybeUninstallOrInstall(a, apkUri);
                        } else {
                            Toast.makeText(a, "Download failed.", Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Toast.makeText(a, "Download failed.", Toast.LENGTH_LONG).show();
                    }
                }
            }
        };
        ContextCompat.registerReceiver(
                a, r, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );

        Toast.makeText(a, "Downloading update…", Toast.LENGTH_SHORT).show();
    }

    /** If keystore differs, we must uninstall first; otherwise install directly. */
    private static void maybeUninstallOrInstall(Activity a, Uri apkUri) {
        try {
            PackageManager pm = a.getPackageManager();
            PackageInfo current = pm.getPackageInfo(a.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);

            File maybeFile = null;
            if ("file".equalsIgnoreCase(apkUri.getScheme())) {
                maybeFile = new File(apkUri.getPath());
            } else if ("content".equalsIgnoreCase(apkUri.getScheme())) {
                File dst = new File(a.getCacheDir(), "tmp-install.apk");
                try (InputStream in = a.getContentResolver().openInputStream(apkUri);
                     OutputStream out = new FileOutputStream(dst)) {
                    if (in != null) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        maybeFile = dst;
                    }
                }
            }

            if (maybeFile != null) {
                PackageInfo downloaded = pm.getPackageArchiveInfo(
                        maybeFile.getAbsolutePath(),
                        PackageManager.GET_SIGNING_CERTIFICATES);

                boolean same;
                if (Build.VERSION.SDK_INT >= 28) {
                    same = Arrays.equals(
                            current.signingInfo.getApkContentsSigners(),
                            downloaded != null ? downloaded.signingInfo.getApkContentsSigners() : new Signature[0]
                    );
                } else {
                    same = Arrays.equals(
                            current.signatures,
                            downloaded != null ? downloaded.signatures : new Signature[0]
                    );
                }

                if (!same) {
                    new AlertDialog.Builder(a)
                            .setTitle("Update can’t install")
                            .setMessage("This update is signed differently. Uninstall the existing app, then install the new one.")
                            .setCancelable(false)
                            .setPositiveButton("Uninstall", (d, w) -> {
                                Intent uninstall = new Intent(Intent.ACTION_DELETE,
                                        Uri.parse("package:" + a.getPackageName()));
                                a.startActivity(uninstall);
                                Toast.makeText(a, "After uninstall, install the new APK from Downloads.", Toast.LENGTH_LONG).show();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    return;
                }
            }
        } catch (Exception ignored) { /* if signature check fails, try normal install */ }

        installApk(a, apkUri);
    }

    private static void installApk(Activity a, Uri apkUri) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(apkUri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i);
}
}
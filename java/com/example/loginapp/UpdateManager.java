package com.example.loginapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UpdateManager {

    private static final String UPDATE_API =
            "https://690eee55bd0fefc30a0617ce.mockapi.io/api/app_version";

    public static void checkForUpdate(Activity activity) {
        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(UPDATE_API)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws java.io.IOException {
                if (!response.isSuccessful() || response.body() == null) return;

                try {
                    String body = response.body().string();
                    JSONArray arr = new JSONArray(body);
                    if (arr.length() == 0) return;

                    JSONObject obj = arr.getJSONObject(0);

                    int apiVersionCode = obj.optInt("versionCode", 0);
                    int minSupportedVersionCode = obj.optInt("minSupportedVersionCode", 0);
                    String apkUrl = obj.optString("apkUrl", "");
                    String changelog = obj.optString("changelog", "");

                    int currentVersionCode = getCurrentVersionCode(activity);

                    if (apiVersionCode > currentVersionCode) {
                        boolean forceUpdate = currentVersionCode < minSupportedVersionCode;

                        activity.runOnUiThread(() ->
                                showUpdateDialog(activity, apiVersionCode, apkUrl, changelog, forceUpdate)
                        );
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private static int getCurrentVersionCode(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return 0;
        }
    }

    private static void showUpdateDialog(Activity activity, int latestVersionCode,
                                         String apkUrl, String changelog, boolean forceUpdate) {

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Update Available");
        builder.setMessage(
                "New version available: " + latestVersionCode + "\n\n" +
                        "Changes:\n" + changelog
        );

        builder.setCancelable(!forceUpdate);

        builder.setPositiveButton("Update", (dialog, which) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !activity.getPackageManager().canRequestPackageInstalls()) {

                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } else {
                downloadAndInstall(activity, apkUrl);
            }
        });

        if (!forceUpdate) {
            builder.setNegativeButton("Later", null);
        }

        builder.show();
    }

    private static void downloadAndInstall(Activity activity, String apkUrl) {
        String fileName = "verdi-update.apk";

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
        request.setTitle("Downloading Update");
        request.setDescription("Please wait...");
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        );
        request.setDestinationInExternalFilesDir(
                activity,
                Environment.DIRECTORY_DOWNLOADS,
                fileName
        );
        request.setMimeType("application/vnd.android.package-archive");

        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        long downloadId = dm.enqueue(request);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (completedId == downloadId) {
                    installApk(activity, fileName);
                    try {
                        activity.unregisterReceiver(this);
                    } catch (Exception ignored) {}
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(receiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    private static void installApk(Activity activity, String fileName) {
        File apkFile = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);

        Uri uri = FileProvider.getUriForFile(
                activity,
                activity.getPackageName() + ".provider",
                apkFile
        );

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            e.printStackTrace();
        }
    }
}
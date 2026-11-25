package com.colle.latterdaylauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {

    private static final String VERSION_URL =
            "https://raw.githubusercontent.com/pixelKc/Latter-Day-Launcher/main/version.json";

    public static void checkForUpdates(Activity activity, boolean manualCheck) {
        new Thread(() -> {
            try {
                URL url = new URL(VERSION_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                int remoteVersionCode = json.getInt("versionCode");
                String remoteVersionName = json.getString("versionName");
                String apkUrl = json.getString("apkUrl");
                String notes = json.optString("releaseNotes", "");

                PackageInfo pInfo = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                long currentVersionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? pInfo.getLongVersionCode()
                        : pInfo.versionCode;

                activity.runOnUiThread(() -> {
                    if (remoteVersionCode > currentVersionCode) {
                        showUpdateDialog(activity, remoteVersionName, notes, apkUrl);
                    } else if (manualCheck) {
                        Toast.makeText(activity, "You're on the latest version!", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                if (manualCheck) {
                    activity.runOnUiThread(() ->
                            Toast.makeText(activity, "Update check failed.", Toast.LENGTH_SHORT).show()
                    );
                }
            }
        }).start();
    }

    private static void showUpdateDialog(Activity activity, String versionName, String notes, String apkUrl) {
        new AlertDialog.Builder(activity)
                .setTitle("Update Available (v" + versionName + ")")
                .setMessage(notes.isEmpty() ? "A new version of Latter-Day Launcher is ready." : notes)
                .setPositiveButton("Update", (dialog, which) -> downloadAndInstall(activity, apkUrl))
                .setNegativeButton("Later", null)
                .show();
    }

    private static void downloadAndInstall(Activity activity, String apkUrl) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
                Toast.makeText(activity, "Allow installs for Latter-Day Launcher, then try again.", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(activity, "Enable Unknown Sources in TV Settings -> Security & Restrictions.", Toast.LENGTH_LONG).show();
            }
            return;
        }

        ProgressDialog progressDialog = new ProgressDialog(activity);
        progressDialog.setMessage("Downloading update...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        new Thread(() -> {
            try {
                File destination = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");
                if (destination.exists()) destination.delete();

                URL url = new URL(apkUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true); // Important for GitHub Releases!
                conn.connect();

                InputStream input = new BufferedInputStream(conn.getInputStream());
                FileOutputStream output = new FileOutputStream(destination);

                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }

                output.flush();
                output.close();
                input.close();

                activity.runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Uri apkUri = Uri.parse("content://com.colle.latterdaylauncher.fileprovider/update.apk");
                    Intent installIntent = new Intent(Intent.ACTION_VIEW);
                    installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(installIntent);
                });

            } catch (Exception e) {
                e.printStackTrace();
                activity.runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(activity, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}

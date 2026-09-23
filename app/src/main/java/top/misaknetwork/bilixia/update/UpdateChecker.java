/*
 * BiliXia - 本地音视频合并工具
 * Copyright (C) 2026 MisakNetwork
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package top.misaknetwork.bilixia.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String OWNER = "MikotoNetwork";
    private static final String REPO  = "BiliXia";
    private static final String LATEST_API =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";

    private final Context ctx;                        
    private final WeakReference<Activity> activityRef; 
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    public UpdateChecker(Activity activity) {
        this.activityRef = new WeakReference<>(activity);
        this.ctx = activity.getApplicationContext();
    }

    public void check(boolean silentIfNoUpdate) {
        if (!running.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                doCheck(silentIfNoUpdate);
            } catch (Throwable t) {
                Log.e(TAG, "检查更新失败", t);
                if (!silentIfNoUpdate) {
                    main.post(() -> Toast.makeText(ctx,
                            "检查更新失败：" + t.getMessage(),
                            Toast.LENGTH_LONG).show());
                }
            } finally {
                running.set(false);
            }
        });
    }

    private void doCheck(boolean silentIfNoUpdate) throws Exception {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "无网络，跳过更新检查");
            if (!silentIfNoUpdate) {
                main.post(() -> Toast.makeText(ctx,
                        "无网络连接", Toast.LENGTH_SHORT).show());
            }
            return;
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(LATEST_API).openConnection();
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "BiliXia-App");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);

            String jsonStr = readAll(conn.getInputStream());
            JSONObject json = new JSONObject(jsonStr);
            String tag = json.optString("tag_name", "");
            String latest = tag.startsWith("v") ? tag.substring(1) : tag;
            String body = json.optString("body", "");
            String current = getCurrentVersion();

            if (!isNewer(latest, current)) {
                if (!silentIfNoUpdate) {
                    main.post(() -> Toast.makeText(ctx,
                            "已是最新版本 v" + current,
                            Toast.LENGTH_SHORT).show());
                }
                return;
            }

            JSONArray assets = json.optJSONArray("assets");
            String apkUrl = null;
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name", "").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url");
                        break;
                    }
                }
            }
            if (apkUrl == null) throw new IOException("Release 里没有 APK");

            final String urlFinal = apkUrl;
            final String bodyFinal = body;
            main.post(() -> showUpdateDialog(latest, bodyFinal, urlFinal));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readAll(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n));
        is.close();
        return sb.toString();
    }

    private String getCurrentVersion() {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.0";
        }
    }

    private boolean isNewer(String latest, String current) {
        try {
            String[] a = latest.split("\\.");
            String[] b = current.split("\\.");
            int len = Math.max(a.length, b.length);
            for (int i = 0; i < len; i++) {
                int x = i < a.length ? Integer.parseInt(a[i].trim()) : 0;
                int y = i < b.length ? Integer.parseInt(b[i].trim()) : 0;
                if (x > y) return true;
                if (x < y) return false;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void showUpdateDialog(String version, String changelog, String url) {
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.w(TAG, "Activity 已不可用，跳过弹窗");
            return;
        }

        new AlertDialog.Builder(activity)          // ★ 用 Activity，不是 ctx
                .setTitle("发现新版本 v" + version)
                .setMessage(changelog.isEmpty() ? "是否下载并安装？" : changelog)
                .setPositiveButton("下载并安装", (d, w) -> downloadAndInstall(url))
                .setNegativeButton("稍后", null)
                .show();
    }

    private void downloadAndInstall(String url) {
        File dir = ctx.getExternalCacheDir();
        if (dir == null) dir = ctx.getCacheDir();
        final File apkFile = new File(dir, "update.apk");
        if (apkFile.exists()) apkFile.delete();

        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        ProgressDialog pd = new ProgressDialog(activity);   // ★ 同样用 Activity
        pd.setMessage("正在下载…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setCancelable(false);
        pd.show();

        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", "BiliXia-App");
                conn.connect();

                int total = conn.getContentLength();
                InputStream is = new BufferedInputStream(conn.getInputStream());
                FileOutputStream fos = new FileOutputStream(apkFile);
                byte[] buf = new byte[8192];
                int n, sum = 0;
                while ((n = is.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    sum += n;
                    if (total > 0) {
                        final int p = sum * 100 / total;
                        main.post(() -> pd.setProgress(p));
                    }
                }
                fos.close();
                is.close();

                main.post(() -> {
                    pd.dismiss();
                    installApk(apkFile);
                });
            } catch (Throwable t) {
                Log.e(TAG, "下载失败", t);
                main.post(() -> {
                    pd.dismiss();
                    Toast.makeText(ctx, "下载失败：" + t.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void installApk(File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + ".fileprovider", apkFile);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            uri = Uri.fromFile(apkFile);
        }

        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        ctx.startActivity(intent);
    }

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
    }
}
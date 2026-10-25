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
package top.misaknetwork.bilixia;

import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import top.misaknetwork.bilixia.tool.AudioVideoMuxer;
import top.misaknetwork.bilixia.update.UpdateChecker;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AudioMuxer";

    private static final String KEY_VIDEO      = "state_video_uri";
    private static final String KEY_AUDIO      = "state_audio_uri";
    private static final String KEY_LAST_MEDIA = "state_last_media_uri";

    /** 更新检查的最小间隔：24 小时 */
    private static final long UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /** 相册子目录名（相对 Movies） */
    private static final String GALLERY_SUBDIR = "BiliXia";

    /** 临时文件最长保留时间（合并失败/中断的残留会被自动清理） */
    private static final long TMP_TTL_MS = 24L * 60 * 60 * 1000;

    private Uri videoUri;
    private Uri audioUri;
    /** 最近一次成功保存到相册的媒体条目 */
    private Uri lastMediaUri;

    /** 合并中（含"复制到相册"阶段）：防止重复点击与状态丢失 */
    private boolean isMerging = false;

    /** 去重播报用 */
    private int lastAnnouncedPercent = -1;

    private TextView tvVideo, tvAudio, tvStatus;
    private ProgressBar progressBar;
    private Button btnPickVideo, btnPickAudio, btnMerge, btnAbout;

    private AudioVideoMuxer muxer;

    /** 单线程后台执行复制，避免阻塞 UI 与 muxer 回调线程 */
    private final ExecutorService copyExecutor = Executors.newSingleThreadExecutor();

    /** 合并中拦截返回 */
    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            toast(getString(R.string.toast_merging));
        }
    };

    private final ActivityResultLauncher<String[]> videoPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> onPicked(uri, true));

    private final ActivityResultLauncher<String[]> audioPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> onPicked(uri, false));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvVideo      = findViewById(R.id.tvVideo);
        tvAudio      = findViewById(R.id.tvAudio);
        tvStatus     = findViewById(R.id.tvStatus);
        progressBar  = findViewById(R.id.progressBar);
        btnMerge     = findViewById(R.id.btnMerge);
        btnAbout     = findViewById(R.id.btnAbout);
        btnPickVideo = findViewById(R.id.btnPickVideo);
        btnPickAudio = findViewById(R.id.btnPickAudio);

        btnAbout.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, About.class)));

        btnPickVideo.setOnClickListener(v -> videoPicker.launch(new String[]{"video/*"}));
        btnPickAudio.setOnClickListener(v -> audioPicker.launch(new String[]{"audio/*"}));
        btnMerge.setOnClickListener(v -> startMerge());

        // 成功后点击状态文字即可分享
        tvStatus.setOnClickListener(v -> {
            if (lastMediaUri != null) shareMediaUri(lastMediaUri);
        });

        getOnBackPressedDispatcher().addCallback(this, backCallback);

        muxer = new AudioVideoMuxer(this);

        if (savedInstanceState != null) restoreState(savedInstanceState);

        checkUpdateIfDue();

        // 后台清理一天前的临时文件（上次异常退出的残留）
        copyExecutor.execute(this::cleanupOldTmpFiles);

        updateUiState();
    }

    // ------------------------------------------------------------------
    // 状态恢复
    // ------------------------------------------------------------------

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (videoUri != null)     outState.putString(KEY_VIDEO, videoUri.toString());
        if (audioUri != null)     outState.putString(KEY_AUDIO, audioUri.toString());
        if (lastMediaUri != null) outState.putString(KEY_LAST_MEDIA, lastMediaUri.toString());
    }

    private void restoreState(@NonNull Bundle state) {
        String v = state.getString(KEY_VIDEO);
        if (v != null) {
            videoUri = Uri.parse(v);
            tvVideo.setText(getString(R.string.label_video, displayNameOf(videoUri)));
        }
        String a = state.getString(KEY_AUDIO);
        if (a != null) {
            audioUri = Uri.parse(a);
            tvAudio.setText(getString(R.string.label_audio, displayNameOf(audioUri)));
        }
        String m = state.getString(KEY_LAST_MEDIA);
        if (m != null) lastMediaUri = Uri.parse(m);
    }

    // ------------------------------------------------------------------
    // 文件选择
    // ------------------------------------------------------------------

    private void onPicked(Uri uri, boolean isVideo) {
        if (uri == null) return;
        if (!takePermission(uri)) {
            toast(getString(R.string.toast_permission_failed));
        }
        if (isVideo) {
            videoUri = uri;
            tvVideo.setText(getString(R.string.label_video, displayNameOf(uri)));
        } else {
            audioUri = uri;
            tvAudio.setText(getString(R.string.label_audio, displayNameOf(uri)));
        }
        updateUiState();
    }

    private boolean takePermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "takePersistableUriPermission failed: " + e.getMessage());
            return false;
        }
    }

    private String displayNameOf(Uri uri) {
        if (uri == null) return getString(R.string.unknown_file);
        try (Cursor c = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "query display name failed: " + e.getMessage());
        }
        String seg = uri.getLastPathSegment();
        return seg != null ? seg : getString(R.string.unknown_file);
    }

    // ------------------------------------------------------------------
    // 合并流程
    // ------------------------------------------------------------------

    private void startMerge() {
        if (isMerging) return;
        if (videoUri == null || audioUri == null) {
            toast(getString(R.string.toast_pick_both));
            return;
        }

        // 1. 临时目录（应用私有，必然可写）
        File baseTmp = getExternalFilesDir(null);
        if (baseTmp == null) baseTmp = getCacheDir();
        File tmpDir = new File(baseTmp, "merge_tmp");
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            toast(getString(R.string.toast_dir_failed));
            return;
        }

        // 2. 唯一临时文件
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        final File tmpFile = uniqueFile(tmpDir, "merge_" + timeStamp, ".mp4");

        // 3. 进入合并状态
        isMerging = true;
        lastAnnouncedPercent = -1;
        lastMediaUri = null;
        backCallback.setEnabled(true);
        updateUiState();

        progressBar.setProgress(0);
        tvStatus.setText(R.string.status_prepare);
        tvStatus.announceForAccessibility(getString(R.string.announce_start));

        // 4. 执行合并
        try {
            muxer.merge(videoUri, audioUri, tmpFile, new AudioVideoMuxer.Callback() {
                @Override
                public void onProgress(int percent) {
                    postUi(() -> {
                        progressBar.setProgress(percent);
                        tvStatus.setText(getString(R.string.status_merging, percent));
                        if (percent >= 10 && percent < 100
                                && percent % 10 == 0
                                && percent != lastAnnouncedPercent) {
                            lastAnnouncedPercent = percent;
                            tvStatus.announceForAccessibility(
                                    getString(R.string.announce_progress, percent));
                        }
                    });
                }

                @Override
                public void onSuccess(File output) {
                    // 合并完成 → 进入保存到相册阶段（isMerging 保持 true）
                    postUi(() -> {
                        progressBar.setProgress(100);
                        tvStatus.setText(R.string.status_saving);
                        tvStatus.announceForAccessibility(getString(R.string.announce_saving));
                    });
                    publishToGallery(output);
                }

                @Override
                public void onError(String message) {
                    if (tmpFile.exists()) tmpFile.delete();
                    postUi(() -> {
                        isMerging = false;
                        backCallback.setEnabled(false);
                        tvStatus.setText(getString(R.string.status_error, message));
                        tvStatus.announceForAccessibility(
                                getString(R.string.announce_fail, message));
                        updateUiState();
                        toast(getString(R.string.toast_fail));
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "merge start failed", e);
            if (tmpFile.exists()) tmpFile.delete();
            isMerging = false;
            backCallback.setEnabled(false);
            updateUiState();
            tvStatus.setText(getString(R.string.status_error,
                    String.valueOf(e.getMessage())));
            toast(getString(R.string.toast_merge_start_failed,
                    String.valueOf(e.getMessage())));
        }
    }

    // ------------------------------------------------------------------
    // 保存到相册：MediaStore pending 流程
    // ------------------------------------------------------------------

    private void publishToGallery(File tmpFile) {
        final String displayName = "B站侠_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date())
                + ".mp4";

        copyExecutor.execute(() -> {
            Uri itemUri = null;
            boolean ok = false;
            String err = null;
            try {
                // 1) 插入 pending 记录（此时相册不可见）
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/" + GALLERY_SUBDIR);
                values.put(MediaStore.Video.Media.IS_PENDING, 1);

                Uri collection = MediaStore.Video.Media
                        .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

                itemUri = getContentResolver().insert(collection, values);
                if (itemUri == null) {
                    throw new IllegalStateException("insert MediaStore failed");
                }

                // 2) 流式复制
                try (InputStream is = new FileInputStream(tmpFile);
                     OutputStream os = getContentResolver().openOutputStream(itemUri)) {
                    if (os == null) throw new IllegalStateException("openOutputStream null");
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
                }

                // 3) 清除 pending → 相册可见
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(itemUri, done, null, null);

                ok = true;
            } catch (Exception e) {
                Log.e(TAG, "publish to gallery failed", e);
                err = e.getMessage();
                if (itemUri != null) {
                    try { getContentResolver().delete(itemUri, null, null); }
                    catch (Exception ignored) {}
                }
            } finally {
                if (tmpFile.exists()) tmpFile.delete();
            }

            final Uri finalUri = itemUri;
            final boolean success = ok;
            final String finalErr = err;

            postUi(() -> {
                isMerging = false;
                backCallback.setEnabled(false);
                if (success) {
                    lastMediaUri = finalUri;
                    tvStatus.setText(getString(R.string.status_success, displayName));
                    tvStatus.announceForAccessibility(getString(R.string.announce_done));
                    toast(getString(R.string.toast_done));
                } else {
                    tvStatus.setText(getString(R.string.status_error,
                            getString(R.string.toast_save_failed,
                                    String.valueOf(finalErr))));
                    tvStatus.announceForAccessibility(getString(R.string.announce_fail,
                            String.valueOf(finalErr)));
                    toast(getString(R.string.toast_save_failed,
                            String.valueOf(finalErr)));
                }
                updateUiState();
            });
        });
    }

    // ------------------------------------------------------------------
    // 分享（直接分享 MediaStore Uri，不需要 FileProvider）
    // ------------------------------------------------------------------

    private void shareMediaUri(Uri mediaUri) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND)
                    .setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, mediaUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.share_via)));
        } catch (Exception e) {
            Log.w(TAG, "share failed", e);
            toast(getString(R.string.share_failed));
        }
    }

    // ------------------------------------------------------------------
    // 更新检查（24h 缓存）
    // ------------------------------------------------------------------

    private void checkUpdateIfDue() {
        SharedPreferences sp = getSharedPreferences("update_check", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = sp.getLong("last_check_ts", 0L);
        if (now - last < UPDATE_CHECK_INTERVAL_MS) return;
        sp.edit().putLong("last_check_ts", now).apply();
        try {
            new UpdateChecker(this).check(true);
        } catch (Exception e) {
            Log.w(TAG, "检查更新失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 临时文件清理
    // ------------------------------------------------------------------

    private void cleanupOldTmpFiles() {
        File baseTmp = getExternalFilesDir(null);
        if (baseTmp == null) baseTmp = getCacheDir();
        File tmpDir = new File(baseTmp, "merge_tmp");
        if (!tmpDir.exists()) return;
        File[] files = tmpDir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (now - f.lastModified() > TMP_TTL_MS) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    // ------------------------------------------------------------------
    // UI 辅助
    // ------------------------------------------------------------------

    private void updateUiState() {
        boolean ready = videoUri != null && audioUri != null;
        btnMerge.setEnabled(!isMerging && ready);
        btnPickVideo.setEnabled(!isMerging);
        btnPickAudio.setEnabled(!isMerging);
    }

    private void postUi(Runnable action) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            action.run();
        });
    }

    private void toast(String str) {
        Toast.makeText(this, str, Toast.LENGTH_LONG).show();
    }

    private File uniqueFile(File dir, String baseName, String ext) {
        File f = new File(dir, baseName + ext);
        int i = 1;
        while (f.exists()) f = new File(dir, baseName + "_" + (i++) + ext);
        return f;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (muxer != null) muxer.release();
        // 允许已提交的复制任务完成，但不再接受新任务
        copyExecutor.shutdown();
    }
}
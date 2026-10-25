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
package top.misaknetwork.bilixia.tool;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Statistics;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioVideoMuxer {

    private static final String TAG = "AudioVideoMuxer";

    /** 输入缓存文件最长保留时间（异常退出的残留会被下次合并清理） */
    private static final long INPUT_TTL_MS = 2L * 60 * 60 * 1000; // 2h

    /** 复制缓冲区大小 */
    private static final int COPY_BUF = 64 * 1024;

    public interface Callback {
        void onProgress(int percent);
        void onSuccess(File output);
        void onError(String message);
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 已释放：不再接受新任务，回调也不再触发 */
    private volatile boolean released = false;

    /** 已请求取消：不再回调，ffmpeg 会被终止 */
    private volatile boolean cancelRequested = false;

    /** 当前 ffmpeg session，用于取消 */
    private volatile FFmpegSession currentSession;

    public AudioVideoMuxer(Context context) {
        // 用 applicationContext，避免持有 Activity 导致泄漏
        this.context = context.getApplicationContext();
    }

    /**
     * 合并视频和音频。
     *
     * @param videoUri   视频 Uri
     * @param audioUri   音频 Uri
     * @param outputFile 输出文件，由调用方决定路径（需可写）
     * @param callback   主线程回调
     */
    public void merge(Uri videoUri, Uri audioUri, File outputFile, Callback callback) {
        if (released) {
            callback.onError("Muxer 已释放");
            return;
        }
        cancelRequested = false;

        io.execute(() -> {
            File videoTmp = null;
            File audioTmp = null;
            try {
                // 0. 清理上次异常退出的残留
                cleanupOldInputCache();

                // 1. 输入复制到缓存（唯一文件名，避免连续合并冲突）
                //    ffmpeg 需要文件路径，且部分 content provider 是流式的（不可 seek），
                //    所以这一步不可避免。
                String token = System.currentTimeMillis() + "_" + Thread.currentThread().getId();
                videoTmp = copyToInputCache(videoUri, "in_v_" + token + ".tmp");
                audioTmp = copyToInputCache(audioUri, "in_a_" + token + ".tmp");

                if (released || cancelRequested) {
                    safeDelete(videoTmp);
                    safeDelete(audioTmp);
                    postError(callback, "已取消");
                    return;
                }

                // 2. 输出目录准备
                File parent = outputFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IllegalStateException("无法创建输出目录: " + parent);
                }
                if (outputFile.exists() && !outputFile.delete()) {
                    Log.w(TAG, "旧输出文件删除失败");
                }

                // 3. 探测时长：-shortest 会截断到较短者，进度取较短者更准
                long vDur = probeDurationMs(videoTmp.getAbsolutePath());
                long aDur = probeDurationMs(audioTmp.getAbsolutePath());
                long durationMs = minPositive(vDur, aDur);

                // 4. ffmpeg 参数
                //    注：音频转码为 aac/192k 是为了最大兼容性；
                //    若确认源音频已是 AAC，可改为 "-c:a", "copy" 省 CPU。
                String[] args = new String[]{
                        "-y",
                        "-i", videoTmp.getAbsolutePath(),
                        "-i", audioTmp.getAbsolutePath(),
                        "-map", "0:v:0",
                        "-map", "1:a:0",
                        "-c:v", "copy",
                        "-c:a", "aac",
                        "-b:a", "192k",
                        "-shortest",
                        "-movflags", "+faststart",   // moov 前置，便于流式播放
                        outputFile.getAbsolutePath()
                };

                // 5. 保存引用以便回调里清理（lambda 捕获需 effectively final）
                final File fVideoTmp = videoTmp;
                final File fAudioTmp = audioTmp;
                final long fDuration = durationMs;

                // 6. 执行
                FFmpegKit.executeWithArgumentsAsync(
                        args,
                        session -> onComplete(session, outputFile, fVideoTmp, fAudioTmp, callback),
                        log -> Log.d(TAG, log.getMessage()),
                        statistics -> onStatistics(statistics, fDuration, callback));

            } catch (Throwable t) {
                safeDelete(videoTmp);
                safeDelete(audioTmp);
                if (!released && !cancelRequested) {
                    Log.e(TAG, "merge error", t);
                    String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                    postError(callback, "异常: " + msg);
                }
            }
        });
    }

    /** ffmpeg 完成回调（内部线程） */
    private void onComplete(FFmpegSession session, File outputFile,
                            File videoTmp, File audioTmp, Callback callback) {
        currentSession = null;

        // 清理输入缓存
        safeDelete(videoTmp);
        safeDelete(audioTmp);

        if (released || cancelRequested) {
            // 已取消/已销毁：不留半成品
            safeDelete(outputFile);
            return;
        }

        ReturnCode rc = session.getReturnCode();
        if (ReturnCode.isSuccess(rc)) {
            post(() -> {
                if (!released) callback.onSuccess(outputFile);
            });
            return;
        }

        // 失败：清掉残缺文件
        safeDelete(outputFile);

        if (ReturnCode.isCancel(rc)) {
            postError(callback, "已取消");
            return;
        }

        String detail = safeGetOutput(session);
        Log.e(TAG, "ffmpeg failed: " + detail);
        postError(callback, "合并失败: " + detail);
    }

    /** ffmpeg statistics 回调（内部线程） */
    private void onStatistics(Statistics statistics, long durationMs, Callback callback) {
        if (released || cancelRequested || durationMs <= 0) return;
        long time = (long) statistics.getTime(); // 已处理时长（毫秒）
        int p = (int) (time * 100 / durationMs);
        p = Math.max(0, Math.min(99, p));  // 100% 留给 onSuccess
        final int percent = p;
        post(() -> {
            if (!released) callback.onProgress(percent);
        });
    }

    // ------------------------------------------------------------------
    // 取消 / 释放
    // ------------------------------------------------------------------

    /**
     * 取消当前合并。若无正在运行的合并，则无效果。
     * 注：ffmpeg-kit 的异步 API 不返回 sessionId，这里用全局 cancel，
     *     因此项目里只应有一处使用 ffmpeg-kit（当前项目满足）。
     */
    public void cancel() {
        cancelRequested = true;
        try {
            FFmpegKit.cancel();
        } catch (Throwable t) {
            Log.w(TAG, "cancel failed", t);
        }
    }

    /** Activity 销毁时调用 */
    public void release() {
        released = true;
        cancelRequested = true;
        try {
            FFmpegKit.cancel();
        } catch (Throwable ignored) {}
        currentSession = null;
        io.shutdownNow();
    }

    // ------------------------------------------------------------------
    // 输入缓存
    // ------------------------------------------------------------------

    private File inputCacheDir() {
        File dir = new File(context.getCacheDir(), "bilixia_in");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private File copyToInputCache(Uri uri, String name) throws Exception {
        File out = new File(inputCacheDir(), name);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new IllegalStateException("无法打开输入流: " + uri);
            byte[] buf = new byte[COPY_BUF];
            int len;
            while ((len = in.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
            fos.flush();
        }
        return out;
    }

    /** 清理 2 小时前残留的输入缓存（异常退出/崩溃留下的） */
    private void cleanupOldInputCache() {
        File[] files = inputCacheDir().listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (now - f.lastModified() > INPUT_TTL_MS) {
                safeDelete(f);
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private long probeDurationMs(String path) {
        try {
            MediaInformation info = FFprobeKit.getMediaInformation(path).getMediaInformation();
            if (info != null && info.getDuration() != null) {
                return (long) (Double.parseDouble(info.getDuration()) * 1000);
            }
        } catch (Exception e) {
            Log.w(TAG, "probeDurationMs failed: " + path, e);
        }
        return 0;
    }

    private static long minPositive(long a, long b) {
        if (a <= 0) return b;
        if (b <= 0) return a;
        return Math.min(a, b);
    }

    private static String safeGetOutput(FFmpegSession session) {
        try {
            String out = session.getOutput();
            if (out == null) return "未知错误";
            // 只取最后若干行，避免超长信息塞进 Toast
            String[] lines = out.trim().split("\n");
            int from = Math.max(0, lines.length - 5);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.length; i++) {
                if (i > from) sb.append('\n');
                sb.append(lines[i]);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "未知错误";
        }
    }

    private static void safeDelete(File f) {
        if (f == null) return;
        try {
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignored) {}
    }

    private void post(Runnable r) {
        main.post(r);
    }

    private void postError(Callback callback, String message) {
        post(() -> {
            if (!released) callback.onError(message);
        });
    }
}
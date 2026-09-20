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



import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioVideoMuxer {

    private static final String TAG = "AudioVideoMuxer";

    public interface Callback {
        void onProgress(int percent);
        void onSuccess(File output);
        void onError(String message);
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean released = false;

    public AudioVideoMuxer(Context context) {
        // 用 applicationContext，避免持有 Activity 导致泄漏
        this.context = context.getApplicationContext();
    }

    /**
     * 合并视频和音频
     *
     * @param videoUri   视频 Uri
     * @param audioUri   音频 Uri
     * @param outputFile 输出文件，由调用方决定路径
     * @param callback   主线程回调
     */
    public void merge(Uri videoUri, Uri audioUri, File outputFile, Callback callback) {
    if (released) {
        callback.onError("Muxer 已释放");
        return;
    }

    io.execute(() -> {
        try {
           
            File videoFile = copyToCache(videoUri, "input_video.tmp");
            File audioFile = copyToCache(audioUri, "input_audio.tmp");

           
            if (outputFile.exists() && !outputFile.delete()) {
                Log.w(TAG, "旧输出文件删除失败");
            }

            
            long durationMs = probeDurationMs(videoFile.getAbsolutePath());

            // FFmpeg 参数
            String[] args = new String[]{
                    "-y",
                    "-i", videoFile.getAbsolutePath(),
                    "-i", audioFile.getAbsolutePath(),
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-c:v", "copy",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-shortest",
                    outputFile.getAbsolutePath()
            };

            
            FFmpegKit.executeWithArgumentsAsync(
                    args,
                    session -> {
                        ReturnCode rc = session.getReturnCode();
                        if (ReturnCode.isSuccess(rc)) {
                            post(() -> callback.onSuccess(outputFile));
                        } else {
                            post(() -> callback.onError("合并失败: " + session.getFailStackTrace()));
                        }
                    },
                    log -> Log.d(TAG, log.getMessage()),
                    statistics -> {
                        if (durationMs > 0) {
                            int p = (int) (statistics.getTime() * 100 / durationMs);
                            p = Math.max(0, Math.min(99, p));
                            final int percent = p;
                            post(() -> callback.onProgress(percent));
                        }
                    });

        } catch (Throwable t) {  
            Log.e(TAG, "merge error", t);
            post(() -> callback.onError("异常: " + t.toString()));
        }
    });
}

    private File copyToCache(Uri uri, String name) throws Exception {
        File out = new File(context.getCacheDir(), name);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new IllegalStateException("无法打开输入流: " + uri);
            byte[] buf = new byte[64 * 1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
            fos.flush();
        }
        return out;
    }

    private long probeDurationMs(String path) {
        try {
            MediaInformation info = FFprobeKit.getMediaInformation(path).getMediaInformation();
            if (info != null && info.getDuration() != null) {
                return (long) (Double.parseDouble(info.getDuration()) * 1000);
            }
        } catch (Exception e) {
            Log.w(TAG, "probeDurationMs failed", e);
        }
        return 0;
    }

    private void post(Runnable r) {
        main.post(r);
    }

    /** Activity 销毁时调用 */
    public void release() {
        released = true;
        io.shutdownNow();
    }
}
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
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import top.misaknetwork.bilixia.tool.AudioVideoMuxer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import top.misaknetwork.bilixia.update.UpdateChecker;
public class MainActivity extends   AppCompatActivity{

    private static final String TAG = "AudioMuxer";
    
    private Uri videoUri;
    private Uri audioUri;

    private TextView tvVideo, tvAudio, tvStatus;
    private ProgressBar progressBar;
    private Button btnMerge;
    private AudioVideoMuxer muxer;

    


@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    tvVideo     = findViewById(R.id.tvVideo);
    tvAudio     = findViewById(R.id.tvAudio);
    tvStatus    = findViewById(R.id.tvStatus);
    progressBar = findViewById(R.id.progressBar);
    btnMerge    = findViewById(R.id.btnMerge);

    Button btnPickVideo = findViewById(R.id.btnPickVideo);
    Button btnPickAudio = findViewById(R.id.btnPickAudio);
    // 点击“关于”跳转
    findViewById(R.id.tvAbout).setOnClickListener(v -> {
     startActivity(new android.content.Intent(MainActivity.this, About.class));
});

 try{
  new UpdateChecker(this).check(true);  
 
 } catch(Exception e){
   toast("检测新版本失败！\n请检查网络连接！");
 }
  
   
  muxer = new AudioVideoMuxer(this);

    
    btnPickVideo.setOnClickListener(v ->
            videoPicker.launch(new String[]{"video/*"}));
    btnPickAudio.setOnClickListener(v ->
            audioPicker.launch(new String[]{"audio/*"}));
    btnMerge.setOnClickListener(v -> startMerge());

    
    refreshButton();
}

    
    private final ActivityResultLauncher<String[]> videoPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    takePermission(uri);
                    videoUri = uri;
                    tvVideo.setText("视频: " + uri.getLastPathSegment());
                    refreshButton();
                }
            });

    
    private final ActivityResultLauncher<String[]> audioPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    takePermission(uri);
                    audioUri = uri;
                    tvAudio.setText("音频: " + uri.getLastPathSegment());
                    refreshButton();
                }
            });

    
 



private void startMerge() {
    if (videoUri == null || audioUri == null) {
        toast("请先选择视频和音频");
        return;
    }

    btnMerge.setEnabled(false);
    progressBar.setProgress(0);
    tvStatus.setText("准备中…");


File baseDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
if (baseDir == null) baseDir = getFilesDir();
File outDir = new File(baseDir, "BXia");
if (!outDir.exists() && !outDir.mkdirs()) {
    toast("无法创建输出目录");
    btnMerge.setEnabled(true); 
    return;
}


String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        .format(new Date());
String fileName = "B站侠_" + timeStamp + ".mp4";
File outFile = new File(outDir, fileName);
    muxer.merge(videoUri, audioUri, outFile, new AudioVideoMuxer.Callback() {
        @Override
        public void onProgress(int percent) {
            progressBar.setProgress(percent);
            tvStatus.setText("处理中… " + percent + "%");
        }

        @Override
        public void onSuccess(File output) {
            btnMerge.setEnabled(true);
            progressBar.setProgress(100);
            tvStatus.setText("合并成功：\n" + output.getAbsolutePath());

            MediaScannerConnection.scanFile(
                    MainActivity.this,
                    new String[]{output.getAbsolutePath()},
                    null, null);

            toast("合并完成");
        }

        @Override
        public void onError(String message) {
            btnMerge.setEnabled(true);
            tvStatus.setText(message);
            toast("合并失败");
        }
    });
}
        
        private void toast(String str) {
         Toast.makeText(this,str,Toast.LENGTH_LONG).show();
            
        	
        }
    
    
    private void takePermission(Uri uri) {
    try {
        getContentResolver().takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (Exception e) {
        Log.w(TAG, "takePersistableUriPermission failed: " + e.getMessage());
    }
}

private void refreshButton() {
    btnMerge.setEnabled(videoUri != null && audioUri != null);
}

@Override
protected void onDestroy() {
    super.onDestroy();
    if (muxer != null) {
        muxer.release();
    }
}
}
/*
 * BiliXia - 本地音视频合并工具
 * Copyright (C) 2026 MisakNetwork
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package top.misaknetwork.bilixia;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import top.misaknetwork.bilixia.update.UpdateChecker;

public class About extends AppCompatActivity {
  private Button btnToWeb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        btnToWeb = findViewById(R.id.btnToWeb);
        btnToWeb.setOnClickListener(v -> {
          Uri.parse("https://bilixia.misaknetwork.top/");
        });

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("关于");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        
        try {
            String versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            TextView tvVersion = findViewById(R.id.tvVersion);
            tvVersion.setText("版本 " + versionName);
            tvVersion.setContentDescription("当前版本号 " + versionName);
        } catch (Exception ignored) {}

        
        findViewById(R.id.tvAuthor).setOnClickListener(v -> {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/MikotoNetwork/BiliXia/")));
        });

        
        Button btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        btnCheckUpdate.setOnClickListener(v -> {
            btnCheckUpdate.setEnabled(false);
            btnCheckUpdate.announceForAccessibility("正在检查更新，请稍候");
            new UpdateChecker(this).check(false);
            btnCheckUpdate.postDelayed(() -> btnCheckUpdate.setEnabled(true), 2000);
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
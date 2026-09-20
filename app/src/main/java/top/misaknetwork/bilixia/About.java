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

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.net.Uri;
import top.misaknetwork.bilixia.update.UpdateChecker;

public class About extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        
           
               
     TextView tvAuthor = findViewById(R.id.tvAuthor);
     findViewById(R.id.btnCheckUpdate).setOnClickListener(v ->
        new UpdateChecker(this).check(false));
     tvAuthor.setOnClickListener(v -> {
     Intent intent = new Intent(Intent.ACTION_VIEW, 
     android.net.Uri.parse("https://github.com/MikotoNetwork"));
     startActivity(intent);
});

findViewById(R.id.tvSource).setOnClickListener(v -> {
    startActivity(new Intent(Intent.ACTION_VIEW,
            Uri.parse("https://github.com/misaknetwork/BiliXia")));
});


        
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("关于");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true); // 显示返回箭头
        }

        
        try {
            String versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            TextView tvVersion = findViewById(R.id.tvVersion);
            tvVersion.setText("版本 " + versionName);
        } catch (Exception ignored) {}
        
    }

    
    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
    
    
    
}
package com.shizuku.uninstaller;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;

public class Exec extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        String content = intent == null ? null : intent.getStringExtra("content");
        if (content != null && content.startsWith("js:")) {
        JsRunner.run(this, content.substring(3));
        finish();
        return;
    }
        Intent intent = getIntent();
        String content = intent == null ? null : intent.getStringExtra("content");

        // ===== 分支0：普通命令，必须放在最前、不碰任何 Shizuku API =====
        if (content != null && content.startsWith("plain:")) {
            String cmd = content.substring(6);
            runPlain(cmd);
            finish();
            return;
        }

        // ===== 以下原逻辑原封不动 =====
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku not ready", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        // ... 你原有的 Shizuku 授权检查 + ShizukuExec 执行代码，原样接在这里 ...
    }

        private void runPlain(String cmd) {
        String result;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            java.io.BufferedReader br =
                new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            p.waitFor();
            result = "cmd=" + cmd + "\nexit=" + p.exitValue() + "\nout=" + sb;
        } catch (Throwable t) {
            result = "ERR: " + t;
        }
        try {
            java.io.FileWriter fw =
                new java.io.FileWriter(getFilesDir() + "/plain_result.txt");
            fw.write(result);
            fw.close();
        } catch (Throwable ignored) {}
            
}
}

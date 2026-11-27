package com.shizuku.uninstaller;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.io.FileWriter;
import java.io.OutputStream;

public class Exec extends Activity {

    private static final String TAG = "ExecDebug";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ===== 普通命令验证分支：不依赖 Shizuku =====
        Intent it = getIntent();
        String cmd = it == null ? null : it.getStringExtra("content");
        log("onCreate, taskId=" + getTaskId());
        log("runCmd, checkPerm=true, cmd=" + cmd);

        if (cmd != null && cmd.startsWith("plain:")) {
            String realCmd = cmd.substring("plain:".length());
            log("plain branch, cmd=" + realCmd);
            try {
                Runtime.getRuntime().exec(realCmd);
                log("plain exec done");
            } catch (Throwable e) {
                log("plain exec error: " + e);
            }
            finish();
            return;
        }
        // ===== 以上为验证分支，验完可删 =====

        // 下面是原来的逻辑，一行没动
        if (!rikka.shizuku.Shizuku.pingBinder()) {
            log("Shizuku not running");
            finish();
            return;
        }

        log("Shizuku ping ok");
        // 后面你原来的授权检查、runCmdFromIntent 等，保持原样
        // 我这里只贴到 ping 成功，你原来后面那段原封不动接在下面就行
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        try (FileWriter fw = new FileWriter(getFilesDir() + "/exec_log.txt", true)) {
            fw.write(System.currentTimeMillis() + ": " + msg + "\n");
        } catch (Exception ignored) {}
        try (FileWriter fw2 = new FileWriter(
                "/sdcard/Download/exec_log.txt", true)) {
            fw2.write(System.currentTimeMillis() + ": " + msg + "\n");
        } catch (Exception ignored) {}
    }
}

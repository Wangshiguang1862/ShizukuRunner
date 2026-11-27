package com.shizuku.uninstaller;

import android.app.Activity;
import android.app.Service;
import android.app.UiModeManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;

import rikka.shizuku.Shizuku;

public class Exec extends Activity {

    TextView t1, t2;
    Process p;
    Thread h2, h3;
    boolean br = false;

    String originalTimeout = "60000";
    boolean timeoutChanged = false;

    /** 授权结果回调：用户点完弹窗后继续执行命令 */
    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    log("perm result, granted=" + (grantResult == PackageManager.PERMISSION_GRANTED));
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        runCmdFromIntent(getIntent(), false);
                    } else {
                        log("permission denied by user");
                        finish();
                    }
                }
            };

    protected MyHandler mHandler = new MyHandler(this);

    public static class MyHandler extends Handler {
        private final WeakReference<Exec> mOuter;

        public MyHandler(Exec activity) {
            mOuter = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            Exec a = mOuter.get();
            if (a == null || a.t2 == null) return;
            a.t2.append(msg.what == 1
                    ? (SpannableString) msg.obj
                    : String.valueOf(msg.obj));
        }
    }

    /** 写日志：同时落文件 + 走 logcat，双保险 */
    private void log(String msg) {
        Log.d("ExecDebug", msg);
        try (FileWriter fw = new FileWriter(
                getFilesDir() + "/exec_log.txt", true)) {
            fw.write(System.currentTimeMillis() + ": " + msg + "\n");
        } catch (Exception ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("onCreate, taskId=" + getTaskId());
        try {
            Shizuku.addRequestPermissionResultListener(permListener);
        } catch (Exception e) {
            log("add perm listener failed: " + e);
        }
        runCmdFromIntent(getIntent(), true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        log("onNewIntent, taskId=" + getTaskId());
        runCmdFromIntent(intent, true);
    }

    /**
     * 从 intent 取 cmd 并执行
     * @param checkPerm 是否先做 Shizuku 授权检查
     */
    private void runCmdFromIntent(Intent intent, boolean checkPerm) {
        final String cmd = intent == null ? null : intent.getStringExtra("content");
        log("runCmd, checkPerm=" + checkPerm + ", cmd=" + (cmd == null ? "null" : cmd));

        // ---- Shizuku 授权检查 ----
        if (checkPerm) {
            try {
                if (!Shizuku.pingBinder()) {
                    log("Shizuku not running");
                    return;
                }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    log("no permission, requesting...");
                    Shizuku.requestPermission(0);
                    return; // 等回调里再跑
                }
            } catch (Exception e) {
                log("perm check error: " + e);
            }
        }
        // ---- 授权检查结束 ----

        if (cmd == null || cmd.isEmpty()) {
            log("UI branch");
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setTitle("执行中");
                    if (((UiModeManager) getSystemService(Service.UI_MODE_SERVICE)).getNightMode()
                            == Configuration.UI_MODE_NIGHT_NO) {
                        setTheme(android.R.style.Theme_DeviceDefault_Light_Dialog);
                    }
                    getWindow().getAttributes().alpha = 0.85f;
                    setContentView(R.layout.exec);
                    t1 = findViewById(R.id.t1);
                    t2 = findViewById(R.id.t2);
                    t2.requestFocus();
                    t2.setOnKeyListener(new View.OnKeyListener() {
                        @Override
                        public boolean onKey(View view, int i, KeyEvent keyEvent) {
                            if (keyEvent.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                    && keyEvent.getAction() == KeyEvent.ACTION_UP) {
                                finish();
                            }
                            return false;
                        }
                    });
                }
            });
            new Thread(new Runnable() {
                @Override
                public void run() {
                    log("UI thread start exec");
                    ShizukuExec(cmd);
                    log("UI thread exec done");
                }
            }).start();
            return;
        }

        // 静默分支
        log("silent branch");
        new Thread(new Runnable() {
            @Override
            public void run() {
                log("silent thread start");
                ShizukuExec(cmd);
                log("silent thread exec done");
                // 调试期不 finish，确认执行完再手动杀
            }
        }).start();
    }

    public void ShizukuExec(String cmd) {
        final boolean silent = (t2 == null);
        final long time = System.currentTimeMillis();
        log("ShizukuExec start, silent=" + silent);

        try {
            try {
                Process gp = Shizuku.newProcess(
                        new String[]{"settings", "get", "system", "screen_off_timeout"}, null, null);
                BufferedReader gr = new BufferedReader(new InputStreamReader(gp.getInputStream()));
                String line = gr.readLine();
                if (line != null && line.trim().matches("\\d+")) {
                    originalTimeout = line.trim();
                    log("originalTimeout=" + originalTimeout);
                }
                gr.close();
                gp.waitFor();
            } catch (Exception e) {
                log("read timeout failed: " + e);
            }

            try {
                Shizuku.newProcess(
                        new String[]{"settings", "put", "system", "screen_off_timeout", "99999"},
                        null, null).waitFor();
                timeoutChanged = true;
                log("timeout changed to 99999");
            } catch (Exception e) {
                log("put timeout failed: " + e);
            }

            log("starting newProcess, cmd=" + cmd);
            p = Shizuku.newProcess(new String[]{"sh"}, null, null);
            OutputStream out = p.getOutputStream();
            out.write((cmd + "\nexit\n").getBytes());
            out.flush();
            out.close();
            log("cmd sent");

            h2 = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        String inline;
                        while ((inline = r.readLine()) != null) {
                            log("stdout: " + inline);
                            if (!silent && t2 != null) {
                                if (t2.length() > 2000 || br) break;
                                Message msg = new Message();
                                msg.what = 0;
                                msg.obj = inline.equals("") ? "\n" : inline + "\n";
                                mHandler.sendMessage(msg);
                            }
                        }
                        r.close();
                        log("h2 done");
                    } catch (Exception e) {
                        log("h2 error: " + e);
                    }
                }
            });

            h3 = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                        String inline;
                        while ((inline = r.readLine()) != null) {
                            log("stderr: " + inline);
                            if (!silent && t2 != null) {
                                if (t2.length() > 2000 || br) break;
                                Message msg = new Message();
                                msg.what = 1;
                                if (!inline.equals("")) {
                                    SpannableString ss = new SpannableString(inline + "\n");
                                    ss.setSpan(new ForegroundColorSpan(Color.RED),
                                            0, ss.length(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                                    msg.obj = ss;
                                }
                                mHandler.sendMessage(msg);
                            }
                        }
                        r.close();
                        log("h3 done");
                    } catch (Exception e) {
                        log("h3 error: " + e);
                    }
                }
            });

            h2.start();
            h3.start();

            int exitCode = p.waitFor();
            log("process exited, code=" + exitCode);

            try { h2.join(); } catch (InterruptedException ignored) {}
            try { h3.join(); } catch (InterruptedException ignored) {}
            log("all threads done");

            if (!silent && t1 != null) {
                final String exitValue = String.valueOf(exitCode);
                final long elapsed = System.currentTimeMillis() - time;
                t1.post(new Runnable() {
                    @Override
                    public void run() {
                        t1.setText(String.format("返回值：%s\n执行用时：%.2f秒", exitValue, elapsed / 1000f));
                        setTitle("执行完毕");
                    }
                });
            }

        } catch (Exception e) {
            log("ShizukuExec error: " + e);
        }
    }

    @Override
    protected void onDestroy() {
        log("onDestroy");
        br = true;

        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Exception ignored) {}

        if (timeoutChanged) {
            try {
                Shizuku.newProcess(
                        new String[]{"settings", "put", "system", "screen_off_timeout", originalTimeout},
                        null, null).waitFor();
                log("timeout restored to " + originalTimeout);
            } catch (Exception e) {
                log("restore timeout failed: " + e);
            }
            timeoutChanged = false;
        }

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (p != null) {
                        if (Build.VERSION.SDK_INT >= 26) p.destroyForcibly();
                        else p.destroy();
                    }
                    if (h2 != null) h2.interrupt();
                    if (h3 != null) h3.interrupt();
                } catch (Exception e) {
                    log("cleanup error: " + e);
                }
            }
        }, 1000);

        super.onDestroy();
    }
}

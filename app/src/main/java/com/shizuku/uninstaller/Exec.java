package com.shizuku.uninstaller;

import android.app.Activity;
import android.app.Service;
import android.app.UiModeManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
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

    /** 写调试日志到 /data/local/tmp/exec_log.txt */
    private void log(String msg) {
        try (FileWriter fw = new FileWriter("/data/local/tmp/exec_log.txt", true)) {
            fw.write(System.currentTimeMillis() + ": " + msg + "\n");
        } catch (Exception ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("onCreate start");

        final String cmd = getIntent().getStringExtra("content");
        log("cmd = " + (cmd == null ? "null" : cmd));

        if (cmd == null || cmd.isEmpty()) {
            log("UI branch");
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
            new Thread(() -> {
                log("UI thread start exec");
                ShizukuExec(cmd);
                log("UI thread exec done");
            }).start();
            return;
        }

        // 静默分支：不建界面，但也不提前 finish
        log("silent branch, cmd = " + cmd);
        new Thread(() -> {
            log("silent thread start");
            ShizukuExec(cmd);
            log("silent thread exec done");
            // 调试期先不 finish，确认执行完再手动杀进程
            // finish();
        }).start();
    }

    public void ShizukuExec(String cmd) {
        final boolean silent = (t2 == null);
        final long time = System.currentTimeMillis();
        log("ShizukuExec start, silent = " + silent);

        try {
            // 先读息屏值（在执行线程里，不阻塞主线程）
            try {
                Process gp = Shizuku.newProcess(
                        new String[]{"settings", "get", "system", "screen_off_timeout"}, null, null);
                BufferedReader gr = new BufferedReader(new InputStreamReader(gp.getInputStream()));
                String line = gr.readLine();
                if (line != null && line.trim().matches("\\d+")) {
                    originalTimeout = line.trim();
                    log("originalTimeout = " + originalTimeout);
                }
                gr.close();
                gp.waitFor();
            } catch (Exception e) {
                log("read timeout failed: " + e);
            }

            // 改息屏
            try {
                Shizuku.newProcess(
                        new String[]{"settings", "put", "system", "screen_off_timeout", "99999"},
                        null, null).waitFor();
                timeoutChanged = true;
                log("timeout changed to 99999");
            } catch (Exception e) {
                log("put timeout failed: " + e);
            }

            // 执行命令
            log("starting newProcess with cmd: " + cmd);
            p = Shizuku.newProcess(new String[]{"sh"}, null, null);
            OutputStream out = p.getOutputStream();
            out.write((cmd + "\nexit\n").getBytes());
            out.flush();
            out.close();
            log("cmd sent to shell");

            h2 = new Thread(() -> {
                try {
                    BufferedReader mReader = new BufferedReader(
                            new InputStreamReader(p.getInputStream()));
                    String inline;
                    while ((inline = mReader.readLine()) != null) {
                        log("stdout: " + inline);
                        if (!silent) {
                            if (t2.length() > 2000 || br) break;
                            Message msg = new Message();
                            msg.what = 0;
                            msg.obj = inline.equals("") ? "\n" : inline + "\n";
                            mHandler.sendMessage(msg);
                        }
                    }
                    mReader.close();
                    log("h2 done");
                } catch (Exception e) {
                    log("h2 error: " + e);
                }
            });

            h3 = new Thread(() -> {
                try {
                    BufferedReader mReader = new BufferedReader(
                            new InputStreamReader(p.getErrorStream()));
                    String inline;
                    while ((inline = mReader.readLine()) != null) {
                        log("stderr: " + inline);
                        if (!silent) {
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
                    mReader.close();
                    log("h3 done");
                } catch (Exception e) {
                    log("h3 error: " + e);
                }
            });

            h2.start();
            h3.start();

            // 等进程结束
            int exitCode = p.waitFor();
            log("process exited with code " + exitCode);

            // 等 h2/h3 读完
            try { h2.join(); } catch (InterruptedException e) { log("h2 join interrupted"); }
            try { h3.join(); } catch (InterruptedException e) { log("h3 join interrupted"); }
            log("all threads done");

            if (!silent) {
                final String exitValue = String.valueOf(exitCode);
                final long elapsed = System.currentTimeMillis() - time;
                t1.post(() -> {
                    t1.setText(String.format("返回值：%s\n执行用时：%.2f秒", exitValue, elapsed / 1000f));
                    setTitle("执行完毕");
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

        new Handler().postDelayed(() -> {
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
        }, 1000);

        super.onDestroy();
    }
}

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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;

import rikka.shizuku.Shizuku;

public class Exec extends Activity {

    TextView t1, t2;
    Process p;
    Thread h2, h3;
    boolean br = false;

    /* 记录进入时的息屏超时，退出时恢复；读不到就用 1 分钟 */
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 先记住用户原来的息屏时间
        try {
            Process gp = Shizuku.newProcess(
                    new String[]{"settings", "get", "system", "screen_off_timeout"}, null, null);
            BufferedReader gr = new BufferedReader(new InputStreamReader(gp.getInputStream()));
            String line = gr.readLine();
            if (line != null && line.trim().matches("\\d+")) {
                originalTimeout = line.trim();
            }
            gr.close();
            gp.waitFor();
        } catch (Exception ignored) {
        }

        // 执行期间把屏幕超时改长，防止息屏打断
        try {
            Shizuku.newProcess(
                    new String[]{"settings", "put", "system", "screen_off_timeout", "99999"},
                    null, null).waitFor();
            timeoutChanged = true;
        } catch (Exception ignored) {
        }

        final String cmd = getIntent().getStringExtra("content");

        if (cmd == null || cmd.isEmpty()) {
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
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ShizukuExec(cmd);
                }
            }).start();
            return;
        }

        // 有 content 时静默执行，不建界面
        new Thread(new Runnable() {
            @Override
            public void run() {
                ShizukuExec(cmd);
                finish();
            }
        }).start();
    }

    public void ShizukuExec(String cmd) {
        final boolean silent = (t2 == null);
        final long time = System.currentTimeMillis();
        try {
            p = Shizuku.newProcess(new String[]{"sh"}, null, null);
            OutputStream out = p.getOutputStream();
            out.write((cmd + "\nexit\n").getBytes());
            out.flush();
            out.close();

            h2 = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader mReader = new BufferedReader(
                                new InputStreamReader(p.getInputStream()));
                        String inline;
                        while ((inline = mReader.readLine()) != null) {
                            if (silent) break;
                            if (t2.length() > 2000 || br) break;
                            Message msg = new Message();
                            msg.what = 0;
                            msg.obj = inline.equals("") ? "\n" : inline + "\n";
                            mHandler.sendMessage(msg);
                        }
                        mReader.close();
                    } catch (Exception ignored) {
                    }
                }
            });
            h2.start();

            h3 = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader mReader = new BufferedReader(
                                new InputStreamReader(p.getErrorStream()));
                        String inline;
                        while ((inline = mReader.readLine()) != null) {
                            if (silent) break;
                            if (t2.length() > 2000 || br) break;
                            Message msg = new Message();
                            msg.what = 1;
                            if (inline.equals("")) {
                                msg.obj = null;
                            } else {
                                SpannableString ss = new SpannableString(inline + "\n");
                                ss.setSpan(new ForegroundColorSpan(Color.RED),
                                        0, ss.length(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                                msg.obj = ss;
                            }
                            mHandler.sendMessage(msg);
                        }
                        mReader.close();
                    } catch (Exception ignored) {
                    }
                }
            });
            h3.start();

            p.waitFor();
            final String exitValue = String.valueOf(p.exitValue());

            if (!silent) {
                t1.post(new Runnable() {
                    @Override
                    public void run() {
                        t1.setText(String.format("返回值：%s\n执行用时：%.2f秒",
                                exitValue, (System.currentTimeMillis() - time) / 1000f));
                        setTitle("执行完毕");
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        br = true;

        // 恢复用户原来的息屏时间
        if (timeoutChanged) {
            try {
                Shizuku.newProcess(
                        new String[]{"settings", "put", "system", "screen_off_timeout", originalTimeout},
                        null, null).waitFor();
            } catch (Exception ignored) {
            }
            timeoutChanged = false;
        }

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (p != null) {
                        if (Build.VERSION.SDK_INT >= 26) {
                            p.destroyForcibly();
                        } else {
                            p.destroy();
                        }
                    }
                    if (h2 != null) h2.interrupt();
                    if (h3 != null) h3.interrupt();
                } catch (Exception ignored) {
                }
            }
        }, 1000);

        super.onDestroy();
    }
}

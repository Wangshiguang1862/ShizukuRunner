package com.shizuku.uninstaller;

import android.app.Activity;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

public class JsRunner {

    public static void run(Activity activity, String jsCode) {
        StringBuilder output = new StringBuilder();

        try {
            ScriptEngine engine = new ScriptEngineManager().getEngineByName("rhino");

            engine.put("log", (ScriptEngine.Factory) null);
            engine.eval("var log = function(msg) { output.append(msg + '\\n'); };");
            engine.eval("var sh = function(cmd) { return execCmd(cmd, output); };");
            engine.eval("var sleep = function(ms) { try { Thread.sleep(ms); } catch(e) {} };");

            engine.put("output", output);
            engine.put("activity", activity);

            engine.eval("function execCmd(cmd, out) {" +
                    "  try {" +
                    "    var p = Runtime.getRuntime().exec(new String[]{'sh','-c',cmd});" +
                    "    var br = new BufferedReader(new InputStreamReader(p.getInputStream()));" +
                    "    var line; var result = '';" +
                    "    while ((line = br.readLine()) != null) { result += line + '\\n'; }" +
                    "    br.close(); p.waitFor();" +
                    "    return {stdout:result, stderr:'', exitCode:p.exitValue()};" +
                    "  } catch(e) { return {stdout:'', stderr:e.toString(), exitCode:-1}; }" +
                    "}");

            engine.eval(jsCode);

        } catch (Throwable t) {
            output.append("JS Error: ").append(t.toString());
        }

        String result = output.toString();
        activity.runOnUiThread(() -> {
            Toast.makeText(activity, result.isEmpty() ? "执行完毕（无输出）" : result, Toast.LENGTH_LONG).show();
        });
    }
}

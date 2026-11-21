package com.jarvis.app;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements JarvisService.JarvisCallback {

    private static final int PERMISSION_REQUEST = 7;

    private WebView web;
    private JarvisService service;
    private boolean bound = false;
    private boolean autoStarted = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((JarvisService.LocalBinder) binder).getService();
            service.setCallback(MainActivity.this);
            bound = true;
            callJs("status", "Подключено к ядру");
            if (hasMicPermission()) {
                service.listen();
                autoStarted = true;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        web.addJavascriptInterface(new Bridge(), "JarvisBridge");
        web.setBackgroundColor(0xFF04070B);
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        requestAllPermissions();
        startService(new Intent(this, JarvisService.class));
    }

    private String[] neededPermissions() {
        List<String> list = new ArrayList<String>();
        list.add(Manifest.permission.RECORD_AUDIO);
        list.add(Manifest.permission.READ_SMS);
        list.add(Manifest.permission.SEND_SMS);
        list.add(Manifest.permission.CALL_PHONE);
        list.add(Manifest.permission.READ_PHONE_STATE);
        list.add(Manifest.permission.READ_CALL_LOG);
        list.add(Manifest.permission.CAMERA);
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 26) {
            list.add("android.permission.ANSWER_PHONE_CALLS");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list.add("android.permission.POST_NOTIFICATIONS");
        }
        return list.toArray(new String[0]);
    }

    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> missing = new ArrayList<String>();
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (missing.size() > 0) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
        }
    }

    private boolean hasMicPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST && service != null) {
            if (hasMicPermission()) {
                service.resume();
                autoStarted = true;
                callJs("status", "Разрешения получены — слушаю");
            } else {
                callJs("status", "Микрофон не разрешён — нажмите «РАЗРЕШЕНИЯ»");
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, JarvisService.class), connection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bound) {
            unbindService(connection);
            bound = false;
        }
    }

    private void callJs(String function, String value) {
        final String script = "if (window.JarvisUI && typeof JarvisUI." + function
                + " === 'function') { JarvisUI." + function + "("
                + JSONObject.quote(value) + "); }";
        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(script, null);
            }
        });
    }

    @Override
    public void onStatus(String status) {
        callJs("status", status);
    }

    @Override
    public void onPartial(String text) {
        callJs("partial", text);
    }

    @Override
    public void onStream(String text) {
        callJs("stream", text);
    }

    @Override
    public void onHeard(String text) {
        callJs("heard", text);
    }

    @Override
    public void onAnswer(String text) {
        callJs("answer", text);
    }

    @Override
    public void onError(String text) {
        callJs("error", text);
    }

    public class Bridge {
        @JavascriptInterface
        public void requestPermissions() {
            requestAllPermissions();
        }

        @JavascriptInterface
        public void listen() {
            if (service != null) service.listen();
        }

        @JavascriptInterface
        public void stop() {
            if (service != null) service.stopListening();
        }

        @JavascriptInterface
        public void ask(String text) {
            if (service != null) service.ask(text);
        }

        @JavascriptInterface
        public void setContinuous(boolean on) {
            if (service != null) service.setContinuous(on);
        }

        @JavascriptInterface
        public boolean isContinuous() {
            return service != null && service.isContinuous();
        }

        @JavascriptInterface
        public void setEndpoint(String url) {
            if (service != null) service.setEndpoint(url);
        }

        @JavascriptInterface
        public String getEndpoint() {
            return service != null ? service.getEndpoint() : "";
        }

        @JavascriptInterface
        public String getEngines() {
            return service != null ? service.getEnginesJson() : "[]";
        }

        @JavascriptInterface
        public String getVoices() {
            return service != null ? service.getVoicesJson() : "[]";
        }

        @JavascriptInterface
        public void setEngine(String engine) {
            if (service != null) service.setEngine(engine);
        }

        @JavascriptInterface
        public void setVoice(String voice) {
            if (service != null) service.setVoice(voice);
        }

        @JavascriptInterface
        public String getVoice() {
            return service != null ? service.getVoice() : "";
        }

        @JavascriptInterface
        public void setTtsProvider(String provider) {
            if (service != null) service.setTtsProvider(provider);
        }

        @JavascriptInterface
        public String getTtsProvider() {
            return service != null ? service.getTtsProvider() : "system";
        }

        @JavascriptInterface
        public void setYandexKey(String key) {
            if (service != null) service.setYandexKey(key);
        }

        @JavascriptInterface
        public String getYandexKey() {
            return service != null ? service.getYandexKey() : "";
        }

        @JavascriptInterface
        public void notificationAccess() {
            if (service != null) service.openNotificationSettings();
        }

        @JavascriptInterface
        public String diagnostics() {
            return service != null ? service.getDiagnosticsJson() : "{}";
        }

        @JavascriptInterface
        public void test() {
            if (service != null) service.ask("Проверь связь и скажи, что ты на связи");
        }
    }
}

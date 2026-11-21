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

public class MainActivity extends Activity implements JarvisService.JarvisCallback {

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
        web.setBackgroundColor(0xFF05080C);
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
        }

        startService(new Intent(this, JarvisService.class));
    }

    private boolean hasMicPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && service != null && !autoStarted) {
            service.listen();
            autoStarted = true;
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
        public void listen() {
            if (service != null) {
                service.listen();
            }
        }

        @JavascriptInterface
        public void stop() {
            if (service != null) {
                service.stopListening();
            }
        }

        @JavascriptInterface
        public void ask(String text) {
            if (service != null) {
                service.ask(text);
            }
        }

        @JavascriptInterface
        public void setContinuous(boolean on) {
            if (service != null) {
                service.setContinuous(on);
            }
        }

        @JavascriptInterface
        public boolean isContinuous() {
            return service != null && service.isContinuous();
        }

        @JavascriptInterface
        public void setEndpoint(String url) {
            if (service != null) {
                service.setEndpoint(url);
            }
        }

        @JavascriptInterface
        public String getEndpoint() {
            return service != null ? service.getEndpoint() : "";
        }

        @JavascriptInterface
        public void test() {
            if (service != null) {
                service.ask("Проверь связь и скажи, что ты на связи");
            }
        }
    }
}

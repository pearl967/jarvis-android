package com.jarvis.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

public class JarvisService extends Service
        implements TextToSpeech.OnInitListener, RecognitionListener {

    public interface JarvisCallback {
        void onStatus(String status);
        void onPartial(String text);
        void onStream(String text);
        void onHeard(String text);
        void onAnswer(String text);
        void onError(String text);
    }

    public class LocalBinder extends Binder {
        public JarvisService getService() {
            return JarvisService.this;
        }
    }

    private static final String PERSONA =
            "Ты Джарвис. Отвечай только на русском, 1–2 коротких предложения. " +
            "Называй пользователя «сэр». Без markdown.";

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean continuous = false;
    private boolean listening = false;
    private boolean autoListen = true;
    private boolean started = false;
    private JarvisCallback callback;
    private PowerManager.WakeLock wakeLock;

    private String endpoint = "http://192.168.0.234:8001/v1/chat/completions";

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JARVIS::active");
        wakeLock.acquire();

        tts = new TextToSpeech(this, this);
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
            status("Система инициализирована");
        } else {
            status("Распознавание речи недоступно — используйте ввод текста");
        }
    }

    private void startAsForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                Object channel = channelClass
                        .getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance("jarvis", "JARVIS", 2);
                NotificationManager nm =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.getClass().getMethod("createNotificationChannel", channelClass)
                        .invoke(nm, channel);
            } catch (Exception ignored) {
            }
        }

        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("JARVIS")
                .setContentText("Активен — слушаю")
                .setOngoing(true);
        try {
            builder.getClass().getMethod("setChannelId", String.class)
                    .invoke(builder, "jarvis");
        } catch (Exception ignored) {
        }
        startForeground(1, builder.build());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setCallback(JarvisCallback cb) {
        this.callback = cb;
    }

    public void setContinuous(boolean on) {
        continuous = on;
        status(on ? "Непрерывный режим включён" : "Режим по фразе «Джарвис»");
    }

    public boolean isContinuous() {
        return continuous;
    }

    public void setEndpoint(String url) {
        if (url != null && url.trim().length() > 0) {
            endpoint = url.trim();
        }
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void listen() {
        if (recognizer == null) {
            status("Распознавание речи недоступно");
            return;
        }
        if (listening) {
            return;
        }
        autoListen = true;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        try {
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 150);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 550);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1100);
        } catch (Throwable ignored) {
        }
        try {
            recognizer.startListening(intent);
            listening = true;
            status("Слушаю...");
        } catch (Exception e) {
            status("Ошибка микрофона: " + e.getMessage());
        }
    }

    public void stopListening() {
        autoListen = false;
        if (recognizer != null && listening) {
            try {
                recognizer.stopListening();
            } catch (Exception ignored) {
            }
        }
        listening = false;
        status("Ожидание");
    }

    @Override
    public void onInit(int statusCode) {
        if (statusCode == TextToSpeech.SUCCESS) {
            int lang = tts.setLanguage(new Locale("ru"));
            if (lang == TextToSpeech.LANG_MISSING_DATA
                    || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US);
            }
            tts.setSpeechRate(0.95f);
            tts.setPitch(0.88f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    status("Говорю...");
                }

                @Override
                public void onDone(String utteranceId) {
                    if (continuous) {
                        restartAfter(900);
                    } else {
                        status("Скажите «Джарвис»");
                    }
                }

                @Override
                public void onError(String utteranceId) {
                    if (continuous) {
                        restartAfter(900);
                    }
                }
            });
            ttsReady = true;
            status("Готов, сэр");
        } else {
            status("Ошибка синтеза речи");
        }
    }

    public void speak(String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        if (ttsReady && tts != null) {
            String id = "jarvis_" + System.currentTimeMillis();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
        } else {
            status(text);
        }
    }

    public void ask(final String question) {
        if (question == null || question.trim().length() == 0) {
            return;
        }
        final long startedAt = System.currentTimeMillis();
        status("Обрабатываю...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String answer = callApi(question);
                final double seconds = (System.currentTimeMillis() - startedAt) / 1000.0;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        status(String.format(java.util.Locale.US,
                                "Ответ за %.1f с", seconds));
                        onAnswer(answer);
                        speak(answer);
                        restartAfter(900);
                    }
                });
            }
        }).start();
    }

    private String callApi(String question) {
        HttpURLConnection connection = null;
        final StringBuilder answer = new StringBuilder();
        try {
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", PERSONA);

            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", question);

            JSONArray messages = new JSONArray();
            messages.put(system);
            messages.put(user);

            JSONObject body = new JSONObject();
            body.put("model", "qwen2.5:0.5b");
            body.put("messages", messages);
            body.put("max_tokens", 96);
            body.put("temperature", 0.25);
            body.put("stream", true);

            URL url = new URL(endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);

            OutputStream os = connection.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = connection.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? connection.getInputStream() : connection.getErrorStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) {
                    break;
                }
                try {
                    JSONObject chunk = new JSONObject(payload);
                    JSONArray choices = chunk.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        continue;
                    }
                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) {
                        continue;
                    }
                    String piece = delta.optString("content", "");
                    if (piece.length() > 0) {
                        answer.append(piece);
                        final String snapshot = answer.toString();
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                onStream(snapshot);
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            }
            reader.close();
            if (code < 200 || code >= 300) {
                return "Ошибка связи с ядром: " + code;
            }
            if (answer.length() == 0) {
                return "Не получил ответа от ядра, сэр.";
            }
            String text = answer.toString().trim();
            if (!text.toLowerCase(new Locale("ru")).contains("сэр")) {
                text = "Сэр, " + text;
            }
            return text;
        } catch (Exception e) {
            return "Не могу связаться с ядром. Проверьте адрес сервера. " + e.getMessage();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void processSpeech(String text) {
        final String original = text;
        String lower = text.toLowerCase(new Locale("ru"));
        boolean wake = lower.contains("джарвис") || lower.contains("jarvis")
                || lower.contains("чарвис") || lower.contains("чарви");

        if (!continuous && !wake) {
            status("Скажите «Джарвис», чтобы разбудить меня");
            restartAfter(600);
            return;
        }

        if (wake) {
            text = text.replaceAll("(?i)(джарвис|jarvis|чарвис|чарви)", "").trim();
        }

        onHeard(original);
        if (text.length() == 0) {
            speak("К вашим услугам, сэр.");
            restartAfter(900);
            return;
        }
        ask(text);
    }

    private void restartAfter(long delay) {
        listening = false;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (autoListen) {
                    listen();
                }
            }
        }, delay);
    }

    private void status(String s) {
        if (callback != null) {
            callback.onStatus(s);
        }
    }

    private void onPartial(String s) {
        if (callback != null) {
            callback.onPartial(s);
        }
    }

    private void onStream(String s) {
        if (callback != null) {
            callback.onStream(s);
        }
    }

    private void onHeard(String s) {
        if (callback != null) {
            callback.onHeard(s);
        }
    }

    private void onAnswer(String s) {
        if (callback != null) {
            callback.onAnswer(s);
        }
    }

    private void onError(String s) {
        if (callback != null) {
            callback.onError(s);
        }
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        status("Слушаю...");
    }

    @Override
    public void onBeginningOfSpeech() {
        if (tts != null) {
            try {
                if (tts.isSpeaking()) {
                    tts.stop();
                    status("Перебиваю себя, сэр");
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        status("Говорите...");
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
        status("Обрабатываю речь...");
    }

    @Override
    public void onError(int error) {
        listening = false;
        String message;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
                message = "Не расслышал, повторите";
                break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                message = "Тишина — возвращаюсь в режим ожидания";
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                message = "Нет разрешения на микрофон";
                break;
            default:
                message = "Ошибка распознавания: " + error;
        }
        status(message);
        onError(message);
        if (continuous) {
            restartAfter(1200);
        }
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && matches.size() > 0) {
            processSpeech(matches.get(0));
        } else {
            restartAfter(600);
        }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> matches = partialResults.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && matches.size() > 0) {
            onPartial(matches.get(0));
        }
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }

    @Override
    public void onDestroy() {
        if (recognizer != null) {
            try {
                recognizer.stopListening();
                recognizer.destroy();
            } catch (Exception ignored) {
            }
        }
        if (tts != null) {
            tts.shutdown();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}

package com.jarvis.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.MediaPlayer;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.telephony.SmsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            "Называй пользователя сэр. Без markdown.";

    private static final String ANSWER_CALL_PERMISSION =
            "android.permission.ANSWER_PHONE_CALLS";

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private MediaPlayer mediaPlayer;
    private boolean ttsReady = false;
    private boolean continuous = false;
    private boolean listening = false;
    private boolean autoListen = true;
    private volatile int answerGeneration = 0;
    private volatile int speechGeneration = 0;
    private JarvisCallback callback;
    private PowerManager.WakeLock wakeLock;

    private String endpoint = "http://192.168.0.234:8001/v1/chat/completions";
    private String ttsProvider = "system";   // system | yandex
    private String preferredVoice = "";
    private String yandexKey = "";
    private String lastSmsAddress = "";

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JARVIS::active");
        wakeLock.acquire();

        initTts("");
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

    // ---------- TTS engine / voice management ----------

    private void initTts(String engine) {
        if (tts != null) {
            try { tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
        }
        ttsReady = false;
        if (engine == null || engine.length() == 0) {
            tts = new TextToSpeech(this, this);
        } else {
            tts = new TextToSpeech(this, this, engine);
        }
    }

    @Override
    public void onInit(int statusCode) {
        if (statusCode != TextToSpeech.SUCCESS || tts == null) {
            status("Ошибка синтеза речи");
            return;
        }
        int lang = tts.setLanguage(new Locale("ru"));
        if (lang == TextToSpeech.LANG_MISSING_DATA
                || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US);
        }
        if (preferredVoice.length() > 0) {
            applyVoice(preferredVoice);
        }
        tts.setSpeechRate(1.0f);
        tts.setPitch(0.95f);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                status("Говорю...");
            }

            @Override
            public void onDone(String utteranceId) {
                restartAfter(700);
            }

            @Override
            public void onError(String utteranceId) {
                restartAfter(900);
            }
        });
        ttsReady = true;
        status("Готов, сэр");
    }

    private void applyVoice(String name) {
        if (tts == null) return;
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return;
            for (Voice v : voices) {
                if (v.getName().equals(name)) {
                    tts.setVoice(v);
                    preferredVoice = name;
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    public String getEnginesJson() {
        JSONArray array = new JSONArray();
        if (tts != null) {
            try {
                for (TextToSpeech.EngineInfo info : tts.getEngines()) {
                    JSONObject o = new JSONObject();
                    o.put("name", info.name);
                    o.put("label", info.label);
                    array.put(o);
                }
            } catch (Exception ignored) {
            }
        }
        return array.toString();
    }

    public String getVoicesJson() {
        JSONArray array = new JSONArray();
        if (tts != null) {
            try {
                Set<Voice> voices = tts.getVoices();
                if (voices != null) {
                    for (Voice v : voices) {
                        JSONObject o = new JSONObject();
                        o.put("name", v.getName());
                        o.put("locale", v.getLocale().toString());
                        o.put("quality", v.getQuality());
                        o.put("latency", v.getLatency());
                        array.put(o);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return array.toString();
    }

    public void setEngine(String engine) {
        initTts(engine);
        status("Движок речи переключён");
    }

    public void setVoice(String name) {
        preferredVoice = name;
        applyVoice(name);
        status("Голос: " + name);
    }

    public String getVoice() {
        if (tts != null) {
            try {
                Voice v = tts.getVoice();
                if (v != null) return v.getName();
            } catch (Exception ignored) {
            }
        }
        return preferredVoice;
    }

    public void setTtsProvider(String provider) {
        ttsProvider = provider;
        status("Голос: " + ("yandex".equals(provider) ? "Yandex SpeechKit" : "системный"));
    }

    public String getTtsProvider() {
        return ttsProvider;
    }

    public void setYandexKey(String key) {
        yandexKey = key == null ? "" : key.trim();
        status(yandexKey.length() > 0 ? "Ключ Yandex сохранён" : "Ключ Yandex удалён");
    }

    public String getYandexKey() {
        return yandexKey;
    }

    // ---------- Speaking ----------

    public void speak(String text) {
        if (text == null || text.length() == 0) return;
        if ("yandex".equals(ttsProvider) && yandexKey.length() > 0) {
            speakYandex(text, speechGeneration);
            return;
        }
        if (ttsReady && tts != null) {
            String id = "jarvis_" + System.currentTimeMillis();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
        } else {
            status(text);
        }
    }

    private void speakYandex(final String text, final int generation) {
        stopMedia();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String base = endpoint;
                    int idx = base.indexOf("/v1/");
                    if (idx > 0) base = base.substring(0, idx);
                    String url = base + "/tts?text=" + URLEncoder.encode(text, "UTF-8")
                            + "&voice=marina&role=neutral&key="
                            + URLEncoder.encode(yandexKey, "UTF-8");
                    MediaPlayer mp = new MediaPlayer();
                    mp.setDataSource(url);
                    mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer player) {
                            if (generation != speechGeneration) {
                                try {
                                    player.stop();
                                    player.release();
                                } catch (Exception ignored) {
                                }
                                if (mediaPlayer == player) mediaPlayer = null;
                                return;
                            }
                            player.start();
                            status("Говорю (Yandex)...");
                        }
                    });
                    mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        @Override
                        public void onCompletion(MediaPlayer player) {
                            if (generation == speechGeneration) restartAfter(700);
                        }
                    });
                    mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override
                        public boolean onError(MediaPlayer player, int what, int extra) {
                            if (generation != speechGeneration) return true;
                            status("Yandex недоступен — системный голос");
                            if (ttsReady && tts != null) {
                                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                                        "jarvis_fallback");
                            }
                            return true;
                        }
                    });
                    mediaPlayer = mp;
                    mp.prepareAsync();
                } catch (Exception e) {
                    if (generation != speechGeneration) return;
                    status("Ошибка Yandex TTS: " + e.getMessage());
                    if (ttsReady && tts != null) {
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_fallback");
                    }
                }
            }
        }).start();
    }

    private void stopMedia() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
    }

    /** Speaks a streamed sentence, adding the "сэр" address only to the first chunk. */
    private void speakChunk(String text, boolean[] spoken, int generation) {
        if (generation != answerGeneration) return;
        if (text == null || text.trim().length() == 0) return;
        String t = text.trim();
        if (!spoken[0] && !t.toLowerCase(new Locale("ru")).contains("сэр")) {
            t = "Сэр, " + t;
        }
        spoken[0] = true;
        speak(t);
    }

    // ---------- Service control ----------

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
        if (url != null && url.trim().length() > 0) endpoint = url.trim();
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void listen() {
        if (recognizer == null) {
            status("Распознавание речи недоступно");
            return;
        }
        if (listening) return;
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
        answerGeneration++;
        speechGeneration++;
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) { }
        }
        stopMedia();
        if (recognizer != null && listening) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
        }
        listening = false;
        status("Остановлено, сэр");
    }

    private void restartAfter(long delay) {
        listening = false;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (autoListen) listen();
            }
        }, delay);
    }

    // ---------- Thinking ----------

    public void ask(final String question) {
        if (question == null || question.trim().length() == 0) return;
        if (handleLocalCommand(question)) return;
        final int myGeneration = ++answerGeneration;
        final long startedAt = System.currentTimeMillis();
        status("Обрабатываю...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String answer = callApi(question, myGeneration);
                final double seconds = (System.currentTimeMillis() - startedAt) / 1000.0;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (myGeneration != answerGeneration) return;
                        status(String.format(Locale.US, "Ответ за %.1f с", seconds));
                        onAnswer(answer);
                        restartAfter(900);
                    }
                });
            }
        }).start();
    }

    private String callApi(String question, final int generation) {
        HttpURLConnection connection = null;
        final StringBuilder answer = new StringBuilder();
        final StringBuilder pending = new StringBuilder();
        final boolean[] spoken = {false};
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
            body.put("model", "qwen3:0.6b");
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
                if (generation != answerGeneration) break;
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) break;
                try {
                    JSONObject chunk = new JSONObject(payload);
                    JSONArray choices = chunk.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) continue;
                    String piece = delta.optString("content", "");
                    if (piece.length() > 0) {
                        answer.append(piece);
                        pending.append(piece);
                        final String snapshot = answer.toString();
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (generation != answerGeneration) return;
                                onStream(snapshot);
                            }
                        });
                        String ready = pending.toString().trim();
                        if (ready.length() > 12 && (ready.endsWith(".")
                                || ready.endsWith("!") || ready.endsWith("?"))) {
                            final String sentence = ready;
                            pending.setLength(0);
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (generation != answerGeneration) return;
                                    speakChunk(sentence, spoken, generation);
                                }
                            });
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            reader.close();
            if (code < 200 || code >= 300) return "Ошибка связи с ядром: " + code;
            if (answer.length() == 0) return "Не получил ответа от ядра, сэр.";
            String text = answer.toString().trim();
            if (!text.toLowerCase(new Locale("ru")).contains("сэр")) {
                text = "Сэр, " + text;
            }
            String rest = pending.toString().trim();
            if (rest.length() > 0) {
                final String finalChunk = rest;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != answerGeneration) return;
                        speakChunk(finalChunk, spoken, generation);
                    }
                });
            } else if (!spoken[0]) {
                final String full = text;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != answerGeneration) return;
                        speakChunk(full, spoken, generation);
                    }
                });
            }
            return text;
        } catch (Exception e) {
            return "Не могу связаться с ядром. Проверьте адрес сервера. " + e.getMessage();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    // ---------- Local commands: fast, deterministic, offline ----------

    private boolean has(String q, String... words) {
        for (String w : words) if (q.contains(w)) return true;
        return false;
    }

    private boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void answerAndSpeak(String text) {
        onAnswer(text);
        speak(text);
        restartAfter(700);
    }

    private boolean handleLocalCommand(String raw) {
        if (raw == null) return false;
        String q = raw.toLowerCase(new Locale("ru")).trim();

        if (has(q, "стоп", "остановись", "хватит", "замолчи", "прекрати", "выключись")) {
            answerAndSpeak("Останавливаюсь, сэр.");
            main.postDelayed(new Runnable() {
                @Override public void run() { stopListening(); }
            }, 1400);
            return true;
        }
        if (has(q, "который час", "сколько времени", "что за время")) {
            String t = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            answerAndSpeak("Сэр, сейчас " + t + ".");
            return true;
        }
        if (has(q, "какое число", "какой день", "какая дата")) {
            String d = new SimpleDateFormat("d MMMM yyyy", new Locale("ru")).format(new Date());
            answerAndSpeak("Сегодня " + d + ", сэр.");
            return true;
        }
        if (has(q, "батарея", "заряд", "аккумулятор")) {
            answerAndSpeak(getBatteryText());
            return true;
        }
        if (has(q, "кто ты", "как тебя зовут")) {
            answerAndSpeak("Я Джарвис, ваш локальный помощник, сэр.");
            return true;
        }
        if (has(q, "спасибо", "благодарю")) {
            answerAndSpeak("Рад помочь, сэр.");
            return true;
        }
        if (has(q, "как дела", "как настройки", "как сам")) {
            answerAndSpeak("Все системы в норме, сэр.");
            return true;
        }

        Matcher math = Pattern.compile(
                "([0-9]+(?:\\.[0-9]+)?)\\s*([+\\-*/x×:])\\s*([0-9]+(?:\\.[0-9]+)?)")
                .matcher(q);
        if (math.find()) {
            try {
                double a = Double.parseDouble(math.group(1));
                double b = Double.parseDouble(math.group(3));
                String op = math.group(2);
                double r;
                if ("+".equals(op)) r = a + b;
                else if ("-".equals(op)) r = a - b;
                else if ("*".equals(op) || "x".equals(op) || "×".equals(op)) r = a * b;
                else {
                    if (b == 0) {
                        answerAndSpeak("Делить на ноль нельзя, сэр.");
                        return true;
                    }
                    r = a / b;
                }
                String result = r == Math.rint(r)
                        ? String.valueOf((long) r)
                        : String.format(Locale.US, "%.2f", r);
                answerAndSpeak("Сэр, результат " + result + ".");
            } catch (Exception ignored) {
                answerAndSpeak("Не смог посчитать, сэр.");
            }
            return true;
        }

        if (has(q, "прочитай смс", "прочитай сообщ", "какие смс", "последнее сообщ")) {
            readLastSms();
            return true;
        }
        if (q.startsWith("ответь") && !has(q, "на звонок")) {
            String reply = raw.replaceAll("(?i)^ответь\\s*(на\\s*смс\\s*)?", "").trim();
            if (reply.length() == 0) {
                answerAndSpeak("Что передать, сэр?");
            } else {
                replySms(reply);
            }
            return true;
        }
        Matcher send = Pattern.compile(
                "(?:отправь|напиши)\\s+(?:смс\\s+)?([+\\d][\\d\\s\\-+()]{5,})\\s+(.+)",
                Pattern.UNICODE_CASE).matcher(raw);
        if (send.find()) {
            sendSms(send.group(1).replaceAll("[\\s\\-()]", ""), send.group(2));
            return true;
        }

        if (has(q, "ответь на звонок", "прими звонок", "подними трубку")) {
            callCommand(true);
            return true;
        }
        if (has(q, "отбей звонок", "отклони звонок", "положи трубку", "сбрось звонок")) {
            callCommand(false);
            return true;
        }

        if (q.startsWith("открой ")) {
            launchApp(raw.substring(7).trim());
            return true;
        }

        Matcher reminder = Pattern.compile(
                "напомни через\\s+(\\d+)\\s+([а-яё]+)\\s+(.+)",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(q);
        if (reminder.find()) {
            try {
                scheduleReminder(Integer.parseInt(reminder.group(1)),
                        reminder.group(2), reminder.group(3));
            } catch (Exception ignored) {
                answerAndSpeak("Не понял срок напоминания, сэр.");
            }
            return true;
        }
        if (has(q, "прочитай уведомлен", "какие уведомлен", "последние уведомлен")) {
            readNotifications();
            return true;
        }
        if (has(q, "фонарик")) {
            toggleTorch(!has(q, "выключи", "погаси", "отключи"));
            return true;
        }
        if (has(q, "поставь на паузу", "пауза", "останови музыку", "выключи музыку")) {
            controlMedia("pause");
            return true;
        }
        if (has(q, "продолжи музыку", "играй", "включи музыку")) {
            controlMedia("play");
            return true;
        }
        if (has(q, "следующий трек", "следующая песня", "переключи трек")) {
            controlMedia("next");
            return true;
        }
        if (has(q, "предыдущий трек", "предыдущая песня")) {
            controlMedia("prev");
            return true;
        }
        if (has(q, "настройки wi-fi", "вайфай", "wi-fi")) {
            openWifiSettings();
            return true;
        }
        return false;
    }

    private String getBatteryText() {
        try {
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return "Не вижу состояние батареи, сэр.";
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            if (level < 0) return "Не вижу состояние батареи, сэр.";
            int percent = level * 100 / scale;
            return plugged != 0
                    ? "Батарея " + percent + " процентов, идёт зарядка, сэр."
                    : "Батарея " + percent + " процентов, сэр.";
        } catch (Exception e) {
            return "Не вижу состояние батареи, сэр.";
        }
    }

    private void readLastSms() {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            answerAndSpeak("Дайте доступ к сообщениям, сэр.");
            return;
        }
        try {
            ContentResolver cr = getContentResolver();
            Cursor c = cr.query(Uri.parse("content://sms/inbox"),
                    new String[]{"address", "body", "date"}, null, null, "date DESC");
            if (c != null && c.moveToFirst()) {
                lastSmsAddress = c.getString(0);
                String body = c.getString(1);
                c.close();
                answerAndSpeak("Сообщение от " + lastSmsAddress + ". " + body);
            } else {
                if (c != null) c.close();
                answerAndSpeak("Входящих сообщений нет, сэр.");
            }
        } catch (Exception e) {
            answerAndSpeak("Не смог прочитать сообщения, сэр.");
        }
    }

    private void replySms(String text) {
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            answerAndSpeak("Дайте разрешение отправлять сообщения, сэр.");
            return;
        }
        if (lastSmsAddress.length() == 0) {
            answerAndSpeak("Сначала прочитайте сообщение, сэр.");
            return;
        }
        sendSms(lastSmsAddress, text);
    }

    private void sendSms(String number, String text) {
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            answerAndSpeak("Дайте разрешение отправлять сообщения, сэр.");
            return;
        }
        try {
            SmsManager.getDefault().sendTextMessage(number, null, text, null, null);
            answerAndSpeak("Сообщение отправлено, сэр.");
        } catch (Exception e) {
            answerAndSpeak("Не смог отправить сообщение, сэр.");
        }
    }

    private void callCommand(final boolean accept) {
        if (!hasPermission(ANSWER_CALL_PERMISSION)) {
            answerAndSpeak("Нужно разрешение на управление звонками, сэр.");
            return;
        }
        try {
            Object telecom = getSystemService("telecom");
            if (telecom == null) throw new Exception("no telecom");
            String method = accept ? "acceptRingingCall" : "endCall";
            telecom.getClass().getMethod(method).invoke(telecom);
            answerAndSpeak(accept ? "Отвечаю на звонок, сэр." : "Звонок отклонён, сэр.");
        } catch (Exception e) {
            answerAndSpeak("Для управления звонками сделайте JARVIS приложением для звонков, сэр.");
        }
    }

    private void launchApp(String query) {
        try {
            PackageManager pm = getPackageManager();
            List<ResolveInfo> apps = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            String q = query.toLowerCase(new Locale("ru"));
            for (ResolveInfo info : apps) {
                String label = info.loadLabel(pm).toString();
                if (label.toLowerCase(new Locale("ru")).contains(q)) {
                    Intent launch = pm.getLaunchIntentForPackage(info.activityInfo.packageName);
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launch);
                        answerAndSpeak("Открываю " + label + ", сэр.");
                        return;
                    }
                }
            }
            answerAndSpeak("Не нашёл приложение " + query + ", сэр.");
        } catch (Exception e) {
            answerAndSpeak("Не смог открыть приложение, сэр.");
        }
    }

    private boolean hasNotificationAccess() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            return enabled != null && enabled.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    public void openNotificationSettings() {
        try {
            Intent i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private void readNotifications() {
        if (!hasNotificationAccess()) {
            openNotificationSettings();
            answerAndSpeak("Дайте JARVIS доступ к уведомлениям, сэр.");
            return;
        }
        String latest = NotificationReader.latest();
        if (latest.length() == 0) {
            answerAndSpeak("Уведомлений пока нет, сэр.");
        } else {
            answerAndSpeak("Последние уведомления. " + latest);
        }
    }

    private void controlMedia(String action) {
        if (!hasNotificationAccess()) {
            openNotificationSettings();
            answerAndSpeak("Для управления музыкой нужен доступ к уведомлениям, сэр.");
            return;
        }
        try {
            MediaSessionManager manager =
                    (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) throw new Exception("no media session manager");
            List<MediaController> sessions =
                    manager.getActiveSessions(new ComponentName(this, NotificationReader.class));
            if (sessions == null || sessions.size() == 0) {
                answerAndSpeak("Активной музыки не вижу, сэр.");
                return;
            }
            MediaController.TransportControls controls = sessions.get(0).getTransportControls();
            if ("play".equals(action)) controls.play();
            else if ("pause".equals(action)) controls.pause();
            else if ("next".equals(action)) controls.skipToNext();
            else if ("prev".equals(action)) controls.skipToPrevious();
            else if ("stop".equals(action)) controls.stop();
            answerAndSpeak("Готово, сэр.");
        } catch (Exception e) {
            answerAndSpeak("Не смог управлять музыкой, сэр.");
        }
    }

    private void toggleTorch(boolean on) {
        if (Build.VERSION.SDK_INT < 23 || !hasPermission(Manifest.permission.CAMERA)) {
            answerAndSpeak("Нужно разрешение на камеру для фонарика, сэр.");
            return;
        }
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) throw new Exception("no camera manager");
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    cm.setTorchMode(id, on);
                    answerAndSpeak(on ? "Фонарик включён, сэр." : "Фонарик выключен, сэр.");
                    return;
                }
            }
            answerAndSpeak("Фонарик не найден, сэр.");
        } catch (Exception e) {
            answerAndSpeak("Не смог переключить фонарик, сэр.");
        }
    }

    private void scheduleReminder(int amount, String unit, String text) {
        try {
            long millis;
            if (unit.startsWith("сек")) millis = amount * 1000L;
            else if (unit.startsWith("час")) millis = amount * 3600000L;
            else millis = amount * 60000L;
            Intent intent = new Intent(this, JarvisReceiver.class);
            intent.putExtra("message", text);
            int requestCode = (int) (System.currentTimeMillis() % 100000);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= 0x40000000; // FLAG_IMMUTABLE
            PendingIntent pending = PendingIntent.getBroadcast(this, requestCode, intent, flags);
            AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
            alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + millis, pending);
            answerAndSpeak("Напомню через " + amount + " " + unit + ": " + text + ", сэр.");
        } catch (Exception e) {
            answerAndSpeak("Не смог установить напоминание, сэр.");
        }
    }

    private void openWifiSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_WIFI_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            answerAndSpeak("Открываю настройки Wi-Fi, сэр.");
        } catch (Exception e) {
            answerAndSpeak("Не смог открыть настройки, сэр.");
        }
    }

    // ---------- Recognition callbacks ----------

    private void processSpeech(String text) {
        final String original = text;
        String lower = text.toLowerCase(new Locale("ru"));
        boolean wake = has(lower, "джарвис", "jarvis", "чарвис", "чарви");
        if (!continuous && !wake) {
            status("Скажите «Джарвис», чтобы разбудить меня");
            restartAfter(500);
            return;
        }
        if (wake) {
            text = text.replaceAll("(?i)(джарвис|jarvis|чарвис|чарви)", "").trim();
        }
        onHeard(original);
        if (text.length() == 0) {
            answerAndSpeak("К вашим услугам, сэр.");
            return;
        }
        ask(text);
    }

    private void status(String s) {
        if (callback != null) callback.onStatus(s);
    }

    private void onPartial(String s) {
        if (callback != null) callback.onPartial(s);
    }

    private void onStream(String s) {
        if (callback != null) callback.onStream(s);
    }

    private void onHeard(String s) {
        if (callback != null) callback.onHeard(s);
    }

    private void onAnswer(String s) {
        if (callback != null) callback.onAnswer(s);
    }

    private void onError(String s) {
        if (callback != null) callback.onError(s);
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
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) { }
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
                message = "Тишина — ожидаю";
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                message = "Нет разрешения на микрофон";
                break;
            default:
                message = "Ошибка распознавания: " + error;
        }
        status(message);
        onError(message);
        restartAfter(800);
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && matches.size() > 0) processSpeech(matches.get(0));
        else restartAfter(500);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> matches = partialResults.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && matches.size() > 0) onPartial(matches.get(0));
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
            try { tts.shutdown(); } catch (Exception ignored) { }
        }
        stopMedia();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}

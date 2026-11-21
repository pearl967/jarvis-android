package com.jarvis.app;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayDeque;
import java.util.Deque;

/** Keeps the last few notifications so JARVIS can read them on voice command. */
public class NotificationReader extends NotificationListenerService {

    private static final Deque<String> recent = new ArrayDeque<String>();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            String title = "";
            String text = "";
            if (sbn.getNotification().extras != null) {
                Object t = sbn.getNotification().extras.getCharSequence(
                        android.app.Notification.EXTRA_TITLE, "");
                Object b = sbn.getNotification().extras.getCharSequence(
                        android.app.Notification.EXTRA_TEXT, "");
                title = t == null ? "" : t.toString();
                text = b == null ? "" : b.toString();
            }
            String line = pkg + ": " + title + (text.length() > 0 ? " — " + text : "");
            synchronized (recent) {
                recent.addFirst(line);
                while (recent.size() > 10) recent.removeLast();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
    }

    public static String latest() {
        synchronized (recent) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (String s : recent) {
                if (i++ > 0) sb.append("\n");
                sb.append(s);
            }
            return sb.toString();
        }
    }

    public static void clear() {
        synchronized (recent) {
            recent.clear();
        }
    }
}

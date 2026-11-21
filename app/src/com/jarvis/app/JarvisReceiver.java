package com.jarvis.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Shows reminders scheduled by voice. */
public class JarvisReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String message = intent.getStringExtra("message");
        if (message == null || message.length() == 0) message = "Напоминание, сэр.";
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                try {
                    Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                    Object channel = channelClass
                            .getConstructor(String.class, CharSequence.class, int.class)
                            .newInstance("jarvis_reminder", "JARVIS", 3);
                    nm.getClass().getMethod("createNotificationChannel", channelClass)
                            .invoke(nm, channel);
                } catch (Exception ignored) {
                }
            }
            Notification.Builder builder = new Notification.Builder(context)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("JARVIS")
                    .setContentText(message)
                    .setAutoCancel(true);
            try {
                builder.getClass().getMethod("setChannelId", String.class)
                        .invoke(builder, "jarvis_reminder");
            } catch (Exception ignored) {
            }
            nm.notify(100, builder.build());
        } catch (Exception ignored) {
        }
    }
}

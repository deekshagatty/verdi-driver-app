package com.example.loginapp;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

public final class NotifUtils {
    private NotifUtils() {}

    // generic channel (no forced custom tone)
    public static final String CHANNEL_GENERAL = "general_notifs";

    // high-priority dispatch/task-offer channel with custom sound
    public static final String CH_FCM_TX = "verdi_fcm_tx";

    public static void ensureNotificationChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        // 1) General channel
        if (nm.getNotificationChannel(CHANNEL_GENERAL) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_GENERAL,
                    "General",
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("General notifications");
            nm.createNotificationChannel(ch);
        }

        // 2) Task Offer / Dispatch channel (loud, custom sound)
        if (nm.getNotificationChannel(CH_FCM_TX) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CH_FCM_TX,
                    "FCM Task Offers",
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("Task offer popups and prompts");

            // Build URI for res/raw/notify_common
            Uri sound = Uri.parse(
                    ContentResolver.SCHEME_ANDROID_RESOURCE + "://"
                            + ctx.getPackageName() + "/"
                            + R.raw.notify_common
            );

            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            ch.setSound(sound, aa);
            ch.enableVibration(true);
            ch.enableLights(true);

            nm.createNotificationChannel(ch);
        }
    }
}

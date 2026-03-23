// app/src/main/java/com/example/loginapp/push/MyFcmService.java
package com.example.loginapp.push;

import static android.content.Context.MODE_PRIVATE;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.loginapp.AuthPrefs;
import com.example.loginapp.HomeActivity;
import com.example.loginapp.NotifUtils;
import com.example.loginapp.R;
import com.example.loginapp.net.ApiClient;
import com.example.loginapp.net.ApiService;
import com.example.loginapp.net.model.GenericResponse;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MyFcmService extends FirebaseMessagingService {

    private static final String TAG = "FCM";

    @Override
    public void onCreate() {
        super.onCreate();
        NotifUtils.ensureNotificationChannel(this);
    }

    @Override
    public void onNewToken(@NonNull String token) {
        Log.d(TAG, "New FCM token: " + token);

        getSharedPreferences("verdi_prefs", MODE_PRIVATE)
                .edit()
                .putString("fcm_token", token)
                .apply();

        long driverId = AuthPrefs.driverId(this);
        String bearerHeader = resolveBearerHeader(this);

        if (driverId > 0 && bearerHeader != null) {
            attachTokenToApi(bearerHeader, token);
        } else {
            Log.w(TAG, "No auth/driver yet; token cached. Will upload after login.");
        }
    }

    public static void pushCachedToken(@NonNull android.content.Context ctx) {
        String cached = ctx.getSharedPreferences("verdi_prefs", MODE_PRIVATE)
                .getString("fcm_token", null);
        if (cached == null || cached.isEmpty()) return;

        String bearerHeader = resolveBearerHeader(ctx);
        if (bearerHeader == null) return;

        ApiService api = ApiClient.get().create(ApiService.class);
        api.uploadFcmToken(bearerHeader, cached).enqueue(new Callback<GenericResponse>() {
            @Override public void onResponse(Call<GenericResponse> call, Response<GenericResponse> resp) {
                Log.d(TAG, "uploadFcmToken cached push code=" + resp.code());
            }
            @Override public void onFailure(Call<GenericResponse> call, Throwable t) {
                Log.w(TAG, "uploadFcmToken cached push failed", t);
            }
        });
    }

    @Nullable
    private static String resolveBearerHeader(@NonNull android.content.Context ctx) {
        String asIs = AuthPrefs.bearer(ctx);
        if (asIs != null && !asIs.trim().isEmpty()) return asIs;

        String raw = AuthPrefs.token(ctx);
        if (raw != null && !raw.trim().isEmpty()) return "Bearer " + raw;

        return null;
    }

    private void attachTokenToApi(@NonNull String bearerHeader, @NonNull String fcmToken) {
        ApiService api = ApiClient.get().create(ApiService.class);
        api.uploadFcmToken(bearerHeader, fcmToken).enqueue(new Callback<GenericResponse>() {
            @Override public void onResponse(Call<GenericResponse> call, Response<GenericResponse> resp) {
                Log.d(TAG, "uploadFcmToken response code=" + resp.code());
            }
            @Override public void onFailure(Call<GenericResponse> call, Throwable t) {
                Log.w(TAG, "uploadFcmToken failed", t);
            }
        });
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage rm) {
        Map<String, String> data = rm.getData();

        String notifBody  = (rm.getNotification() != null) ? rm.getNotification().getBody()  : null;
        String notifTitle = (rm.getNotification() != null) ? rm.getNotification().getTitle() : null;

        // ✅ IMPORTANT FIX: for DATA-only push, notifBody is null, so use data["body"] as fallback
        String bodyForHandler = (notifBody != null && !notifBody.trim().isEmpty())
                ? notifBody
                : (data.get("body") != null ? data.get("body") : null);

        // ✅ 1) FIRST: handle admin status updates (FCM / Ably formatted)
        try {
            boolean handled = TaskStatusPushHandler.handleIfStatusUpdate(
                    getApplicationContext(),
                    data,
                    bodyForHandler
            );
            Log.d(TAG, "handleIfStatusUpdate handled=" + handled
                    + " title=" + notifTitle
                    + " bodyForHandler=" + bodyForHandler
                    + " data=" + data);

            // ✅ if it's a status push, don't treat it as "offer"
            if (handled) return;

        } catch (Exception e) {
            Log.e(TAG, "handleIfStatusUpdate crashed", e);
            // continue to normal offer flow
        }

        // ✅ 2) Normal OFFER flow
        String body = (rm.getNotification() != null && rm.getNotification().getBody() != null)
                ? rm.getNotification().getBody()
                : data.get("body");

        String title = (rm.getNotification() != null && rm.getNotification().getTitle() != null)
                ? rm.getNotification().getTitle()
                : (data.get("title") != null ? data.get("title") : "Verdi");

        if (title == null) title = "Verdi";
        if (body == null) body = "";

        String rawPayload = data.containsKey("tx_payload")
                ? data.get("tx_payload")
                : (data.containsKey("transaction_id")
                ? data.get("transaction_id")
                : body);

        TxPayload payload = parseTxPayload(rawPayload);

        long txId = (payload != null) ? payload.txId : 0;
        int secs  = (payload != null && payload.secs > 0) ? payload.secs : 0;

        if (txId <= 0 && data.containsKey("transaction_id")) {
            try { txId = Long.parseLong(data.get("transaction_id")); } catch (Exception ignored) {}
        }
        if (secs <= 0 && data.containsKey("secs")) {
            try { secs = Integer.parseInt(data.get("secs")); } catch (Exception ignored) {}
        }
        if (secs <= 0) secs = 30;

        Log.d(TAG, "OFFER RX rawPayload=" + rawPayload + " txId=" + txId + " secs=" + secs);

//        // ✅ Save offer so Home can show card even if app killed
//        if (txId > 0) {
//            HomeActivity.persistOfferToPrefs(getApplicationContext(), txId, secs);
//
//            // ✅ If Home is open, update instantly
//            Intent b = new Intent(HomeActivity.ACTION_FCM_OFFER)
//                    .putExtra(HomeActivity.EXTRA_TX_ID, txId)
//                    .putExtra(HomeActivity.EXTRA_SECS, secs);
//            LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(b);
//        }

        PendingIntent pi = buildOpenHomePendingIntent(txId, secs, rawPayload, body);

        String channelToUse = (txId > 0) ? NotifUtils.CH_FCM_TX : NotifUtils.CHANNEL_GENERAL;

        NotificationCompat.Builder nb =
                new NotificationCompat.Builder(this, channelToUse)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pi)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Uri soundUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.notify_common);
            nb.setSound(soundUri);
            nb.setVibrate(new long[]{0, 300, 200, 300});
            nb.setDefaults(NotificationCompat.DEFAULT_LIGHTS);
        }

        if (Build.VERSION.SDK_INT >= 33 &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Notification suppressed: POST_NOTIFICATIONS not granted");
            return;
        }

        int notifId = (txId > 0)
                ? (int) (txId % Integer.MAX_VALUE)
                : (int) (System.currentTimeMillis() & 0x0FFFFFFF);

        NotificationManagerCompat.from(this).notify(notifId, nb.build());
    }

    private PendingIntent buildOpenHomePendingIntent(long txId, int secs,
                                                     @Nullable String rawPayload, @Nullable String body) {

        Intent open = new Intent(this, HomeActivity.class);

        long pushId = System.currentTimeMillis();

        // ✅ make intent unique (identity)
        open.putExtra("push_id", pushId);
        open.setAction("OPEN_OFFER_" + pushId);
        open.setData(Uri.parse("verdi://open_offer/" + txId + "/" + pushId));

        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        if (rawPayload != null) open.putExtra("tx_payload", rawPayload);
        if (body != null) open.putExtra("body", body);

        if (txId > 0) {
            open.putExtra(HomeActivity.EXTRA_TX_ID, txId);
            open.putExtra(HomeActivity.EXTRA_SECS, secs);
            open.putExtra("transaction_id", String.valueOf(txId));
        }

        // ✅ stable per push (not random time again)
        int reqCode = (int) (pushId & 0x7fffffff);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;

        return PendingIntent.getActivity(this, reqCode, open, flags);
    }


    private static final class TxPayload {
        final long txId;
        final int secs;
        TxPayload(long txId, int secs) { this.txId = txId; this.secs = secs; }
    }

    @Nullable
    private static TxPayload parseTxPayload(@Nullable String raw) {
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.isEmpty()) return null;

        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(\\d+)(?:\\s*,\\s*(\\d+))?$")
                    .matcher(raw);
            if (m.find()) {
                long id = Long.parseLong(m.group(1));
                int secs = (m.group(2) != null) ? Integer.parseInt(m.group(2)) : 0;
                return new TxPayload(id, secs);
            }

            m = java.util.regex.Pattern.compile("(\\d{1,})").matcher(raw);
            if (m.find()) {
                return new TxPayload(Long.parseLong(m.group(1)), 0);
            }
        } catch (Exception ignore) {}

        return null;
    }
}

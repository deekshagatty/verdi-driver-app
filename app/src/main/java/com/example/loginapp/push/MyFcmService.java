package com.example.loginapp.push;

import static android.content.Context.MODE_PRIVATE;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.loginapp.AuthPrefs;
import com.example.loginapp.HomeActivity;
import com.example.loginapp.NotifUtils;
import com.example.loginapp.R;
import com.example.loginapp.net.ApiClient;
import com.example.loginapp.net.ApiService;
import com.example.loginapp.net.model.GenericResponse;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.HashMap;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MyFcmService extends FirebaseMessagingService {

    private static final String TAG = "FCM";

    @Override
    public void onCreate() {
        super.onCreate();
        // make sure both channels exist (general + task offer sound channel)
        NotifUtils.ensureNotificationChannel(this);
    }

    @Override
    public void onNewToken(@NonNull String token) {
        Log.d(TAG, "New FCM token: " + token);

        // cache locally
        getSharedPreferences("verdi_prefs", MODE_PRIVATE)
                .edit()
                .putString("fcm_token", token)
                .apply();

        long driverId = AuthPrefs.driverId(this);
        String bearerHeader = resolveBearerHeader(this);

        if (driverId > 0 && bearerHeader != null) {
            attachTokenToApi(bearerHeader, driverId, token);
            writeFcmToFirestoreStatic(driverId, token);
        } else {
            Log.w(TAG, "No auth/driver yet; token cached. Will upload after login.");
        }
    }

    /**
     * Call this after successful login to push any cached FCM token to API + Firestore.
     */
    public static void pushCachedToken(@NonNull android.content.Context ctx) {
        String cached = ctx.getSharedPreferences("verdi_prefs", MODE_PRIVATE)
                .getString("fcm_token", null);
        if (cached == null || cached.isEmpty()) return;

        long driverId = AuthPrefs.driverId(ctx);
        String bearerHeader = resolveBearerHeader(ctx);
        if (driverId <= 0 || bearerHeader == null) return;

        // Send to API
        ApiService api = ApiClient.get().create(ApiService.class);
        String firebaseUid = ""; // or AuthPrefs.firebaseUid(ctx) if you maintain one
        api.attachFirebase(bearerHeader, driverId, firebaseUid, cached)
                .enqueue(new Callback<GenericResponse>() {
                    @Override
                    public void onResponse(Call<GenericResponse> call, Response<GenericResponse> resp) {
                        Log.d(TAG, "attachFirebase(fcm) cached push code=" + resp.code());
                    }

                    @Override
                    public void onFailure(Call<GenericResponse> call, Throwable t) {
                        Log.w(TAG, "attachFirebase(fcm) cached push failed", t);
                    }
                });
        writeFcmToFirestoreStatic(driverId, cached);
    }

    /**
     * Builds a proper "Bearer ..." header if only the raw token is stored.
     */
    private static String resolveBearerHeader(@NonNull android.content.Context ctx) {
        String asIs = AuthPrefs.bearer(ctx);
        if (asIs != null && !asIs.trim().isEmpty()) return asIs;

        String raw = AuthPrefs.token(ctx);
        if (raw != null && !raw.trim().isEmpty()) return "Bearer " + raw;

        return null;
    }

    /**
     * Hit your backend to upload FCM token so backend can target this device.
     */
    private void attachTokenToApi(@NonNull String bearerHeader, long driverId, @NonNull String fcmToken) {
        ApiService api = ApiClient.get().create(ApiService.class);
        api.uploadFcmToken(bearerHeader, fcmToken)
                .enqueue(new Callback<GenericResponse>() {
                    @Override
                    public void onResponse(Call<GenericResponse> call, Response<GenericResponse> resp) {
                        Log.d(TAG, "uploadFcmToken response code=" + resp.code());
                    }

                    @Override
                    public void onFailure(Call<GenericResponse> call, Throwable t) {
                        Log.w(TAG, "uploadFcmToken failed", t);
                    }
                });
    }

    /**
     * Writes device info + FCM token to Firestore under:
     *   drivers/{driverId}/private/device
     * and shadows some info on drivers/{driverId}
     */
    private static void writeFcmToFirestoreStatic(long driverId, String token) {
        if (driverId <= 0 || token == null || token.isEmpty()) return;

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        DocumentReference root = db.collection("drivers").document(String.valueOf(driverId));

        Map<String, Object> device = new HashMap<>();
        device.put("fcm_token", token);
        device.put("platform", "android");
        device.put("brand", Build.BRAND);
        device.put("model", Build.MODEL);
        device.put("sdk", Build.VERSION.SDK_INT);
        device.put("updated_at", FieldValue.serverTimestamp());

        root.collection("private").document("device")
                .set(device, SetOptions.merge())
                .addOnSuccessListener(v -> Log.d(TAG, "Firestore: device token saved"))
                .addOnFailureListener(e -> Log.e(TAG, "Firestore: device token write FAILED", e));

        Map<String, Object> shadow = new HashMap<>();
        shadow.put("driver_id", driverId);
        shadow.put("has_fcm", true);
        shadow.put("fcm_updated_at", FieldValue.serverTimestamp());

        root.set(shadow, SetOptions.merge())
                .addOnSuccessListener(v -> Log.d(TAG, "Firestore: driver shadow updated"))
                .addOnFailureListener(e -> Log.e(TAG, "Firestore: driver shadow update FAILED", e));
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage rm) {
        Map<String, String> data = rm.getData();

        String body = (rm.getNotification() != null && rm.getNotification().getBody() != null)
                ? rm.getNotification().getBody()
                : data.get("body");

        String title = (rm.getNotification() != null && rm.getNotification().getTitle() != null)
                ? rm.getNotification().getTitle()
                : (data.get("title") != null ? data.get("title") : "Verdi");

        // rawPayload can be "12345,10" (txId + timeoutSecs) OR just "12345"
        String rawPayload = data.containsKey("tx_payload")
                ? data.get("tx_payload")
                : (data.containsKey("transaction_id")
                ? data.get("transaction_id")
                : body);

        TxPayload payload = parseTxPayload(rawPayload);
        long txId = (payload != null) ? payload.txId : 0;
        int secs = (payload != null) ? payload.secs : 0; // 0 => app fallback (ex: 5s)

        // mirror this txId into Firestore (fcmtransactionsid) so HomeActivity can react
        if (txId > 0) {
            long driverId = AuthPrefs.driverId(this);
            if (driverId > 0) {
                FirebaseFirestore db = FirebaseFirestore.getInstance();
                DocumentReference root = db.collection("drivers").document(String.valueOf(driverId));

                // wire format: "txId,secs" if secs>0 else "txId"
                String wire = (secs > 0) ? (txId + "," + secs) : String.valueOf(txId);

                Map<String, Object> patch = new HashMap<>();
                patch.put("fcmtransactionsid", wire); // keep STRING to include "id,secs"
                patch.put("fcm_tx_updated_at", FieldValue.serverTimestamp());
                patch.put("driver_id", driverId);

                root.update(patch)
                        .addOnFailureListener(e -> root.set(patch, SetOptions.merge()));

                DocumentReference pres = root.collection("presence").document("current");
                pres.update(patch)
                        .addOnFailureListener(e -> pres.set(patch, SetOptions.merge()));
            } else {
                Log.w(TAG, "driverId=0; cannot write fcmtransactionsid");
            }
        } else {
            Log.w(TAG, "txId=0; nothing to write to fcmtransactionsid");
        }

        // tap -> open HomeActivity with info about the task
        Intent open = new Intent(this, HomeActivity.class)
                .setAction("com.example.loginapp.ACTION_OPEN_TX")
                .putExtra("push_tx_id", txId)
                .putExtra("push_tx_secs", secs)  // optional
                .putExtra("raw_body", body)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pi = androidx.core.app.TaskStackBuilder.create(this)
                .addNextIntentWithParentStack(open)
                .getPendingIntent(
                        (int) (System.currentTimeMillis() & 0x0FFFFFFF),
                        (Build.VERSION.SDK_INT >= 23)
                                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                                : PendingIntent.FLAG_UPDATE_CURRENT
                );

        // choose which channel to notify on:
        // - task offer / dispatch push -> loud channel with custom sound
        // - generic message -> normal channel
        String channelToUse = (txId > 0)
                ? NotifUtils.CH_FCM_TX
                : NotifUtils.CHANNEL_GENERAL;

        NotificationCompat.Builder nb =
                new NotificationCompat.Builder(this, channelToUse)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pi);

        // Pre-Oreo (<26): channel sound doesn't exist, set sound manually
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            android.net.Uri soundUri = android.net.Uri.parse(
                    "android.resource://" + getPackageName() + "/" + R.raw.notify_common
            );
            nb.setSound(soundUri);
            nb.setVibrate(new long[]{0, 300, 200, 300}); // mild buzz pattern
        }

        // Android 13+ runtime notification permission check
        if (Build.VERSION.SDK_INT >= 33 &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Notification suppressed: POST_NOTIFICATIONS not granted");
            return;
        }

        NotificationManagerCompat.from(this)
                .notify((int) System.currentTimeMillis(), nb.build());
    }

    /**
     * Attempt to pull a transaction id from free text (fallback).
     * Tries patterns like "#1234" or "transaction id: 1234" etc.
     */
    static long extractTxIdFromText(@Nullable String text) {
        if (text == null) return 0;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:#|\\btx\\b|transaction(?:\\s*id)?\\s*[:#]?)\\s*(\\d+)",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(text);
            if (m.find()) return Long.parseLong(m.group(1));

            // fallback: first 3+ digit number
            m = java.util.regex.Pattern.compile("(\\d+)").matcher(text);
            if (m.find()) return Long.parseLong(m.group(1));
        } catch (Exception ignore) {}
        return 0;
    }

    /**
     * (Currently unused in this final flow, but kept if you want a clean Long instead of "id,secs".)
     */
    private static void writeFcmTransactionsIdToFirestore(long driverId, @Nullable Long txId) {
        if (driverId <= 0) return;
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        DocumentReference root = db.collection("drivers").document(String.valueOf(driverId));

        Map<String, Object> rootFields = new HashMap<>();
        rootFields.put("fcmtransactionsid", txId);
        rootFields.put("driver_id", driverId);
        root.set(rootFields, SetOptions.merge());

        Map<String, Object> presence = new HashMap<>();
        presence.put("fcmtransactionsid", txId);
        presence.put("driver_id", driverId);
        root.collection("presence").document("current").set(presence, SetOptions.merge());
    }

    // Small holder for "txId,secs" payload coming from FCM
    private static final class TxPayload {
        final long txId;
        final int secs; // 0 if absent
        TxPayload(long txId, int secs) {
            this.txId = txId;
            this.secs = secs;
        }
    }

    @Nullable
    private static TxPayload parseTxPayload(@Nullable String raw) {
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.isEmpty()) return null;

        try {
            // pattern: "12345,8" OR "12345"
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(\\d+)(?:\\s*,\\s*(\\d+))?$")
                    .matcher(raw);
            if (m.find()) {
                long id = Long.parseLong(m.group(1));
                int secs = (m.group(2) != null) ? Integer.parseInt(m.group(2)) : 0;
                return new TxPayload(id, secs);
            }

            // fallback: grab first number
            m = java.util.regex.Pattern.compile("(\\d{1,})").matcher(raw);
            if (m.find()) {
                return new TxPayload(Long.parseLong(m.group(1)), 0);
            }
        } catch (Exception ignore) {}

        return null;
    }
}

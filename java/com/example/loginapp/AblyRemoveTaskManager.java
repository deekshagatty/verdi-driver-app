package com.example.loginapp;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.ably.lib.realtime.AblyRealtime;
import io.ably.lib.realtime.Channel;
import io.ably.lib.realtime.ChannelState;
import io.ably.lib.realtime.ChannelStateListener;
import io.ably.lib.types.AblyException;
import io.ably.lib.types.ClientOptions;
import io.ably.lib.types.Message;
import io.ably.lib.types.Param;

/**
 * AblyRemoveTaskManager
 *
 * Listens to:
 *   Channel : private:drivers
 *   Event   : remove-task
 *
 * Data:
 *   { type: remove_trx_popup, transaction_id: 76, driver_id: 1, winner_device_id?: "uuid" }
 *
 * Emits:
 *   LocalBroadcast ACTION_TASK_REMOVED
 */
public final class AblyRemoveTaskManager {

    public static final String ACTION_TASK_REMOVED = "com.example.loginapp.ACTION_TASK_REMOVED";
    public static final String EXTRA_TX_ID = "tx_id";
    public static final String EXTRA_WINNER_DEVICE = "winner_device_id";

    private static final String TAG = "ABLY_REMOVE";
    private static final String AUTH_URL = "https://api.tryverdi.com/api/ably/driver_auth";
    private static final String CHANNEL  = "private:drivers";
    private static final String EVENT    = "remove-task";

    private final Context appCtx;

    private AblyRealtime ably;
    private Channel channel;
    private boolean subscribed = false;

    public AblyRemoveTaskManager(@NonNull Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public synchronized void start() {
        if (ably != null) return;

        String token = pickBearerToken(appCtx);
        if (token.isEmpty()) return;

        try {
            ClientOptions opts = new ClientOptions();
            opts.authUrl = AUTH_URL;
            opts.authHeaders = new Param[]{
                    new Param("Authorization", "Bearer " + token)
            };

            ably = new AblyRealtime(opts);
            channel = ably.channels.get(CHANNEL);

            channel.on((ChannelStateListener) state -> {
                if (state.current == ChannelState.attached && !subscribed) {
                    subscribed = true;
                    try {
                        channel.subscribe(EVENT, this::handleRemoveEvent);
                        Log.e(TAG, "Subscribed to remove-task");
                    } catch (AblyException e) {
                        Log.e(TAG, "subscribe error", e);
                    }
                }
            });

            channel.attach();
        } catch (AblyException e) {
            stop();
        }
    }

    public synchronized void stop() {
        try {
            if (channel != null) channel.unsubscribe();
            if (ably != null) ably.close();
        } catch (Throwable ignored) {}
        channel = null;
        ably = null;
        subscribed = false;
    }

    // ===================== PARSER ======================

    private void handleRemoveEvent(@NonNull Message msg) {
        try {
            Log.e(TAG, "RX name=" + msg.name + " data=" + msg.data);

            Object root = normalizeToObject(msg.data);
            if (root == null) return;

            // tx
            Long txId = firstLong(
                    getLong(root, "transaction_id"),
                    getLong(root, "transactionId")
            );
            if (txId == null || txId <= 0) return;

            // ✅ winner driver id (your payload uses driver_id)
            Long winnerDriverId = firstLong(
                    getLong(root, "driver_id"),
                    getLong(root, "driverId")
            );
            if (winnerDriverId == null || winnerDriverId <= 0) return;

            long myDriverId = AuthPrefs.driverId(appCtx);

            // ✅ ONLY WINNER KEEPS, ALL OTHER DRIVERS REMOVE
            if (myDriverId == winnerDriverId) {
                Log.e(TAG, "KEEP (winner) my=" + myDriverId + " winner=" + winnerDriverId + " tx=" + txId);
                return;
            }

            Log.e(TAG, "REMOVE (not winner) my=" + myDriverId + " winner=" + winnerDriverId + " tx=" + txId);

            Intent i = new Intent(ACTION_TASK_REMOVED);
            i.putExtra(EXTRA_TX_ID, txId);
            LocalBroadcastManager.getInstance(appCtx).sendBroadcast(i);

        } catch (Throwable t) {
            Log.e(TAG, "handleRemoveEvent error", t);
        }
    }


    // ===================== HELPERS ======================

    @NonNull
    private static String pickBearerToken(@NonNull Context ctx) {
        String raw = AuthPrefs.token(ctx);
        if (raw == null) raw = "";
        raw = raw.trim();
        if (raw.startsWith("Bearer ")) raw = raw.substring("Bearer ".length()).trim();
        return raw;
    }

    @Nullable
    private static Object normalizeToObject(@Nullable Object data) {
        if (data == null) return null;
        if (data instanceof JSONObject) return data;
        if (data instanceof Map) return data;
        try { return new JSONObject(String.valueOf(data)); }
        catch (Throwable ignore) { return null; }
    }

    @Nullable
    private static Long getLong(@Nullable Object data, @NonNull String key) {
        try {
            if (data instanceof JSONObject) return toLongOrNull(((JSONObject) data).opt(key));
            if (data instanceof Map) return toLongOrNull(((Map<?, ?>) data).get(key));
        } catch (Throwable ignore) {}
        return null;
    }

    @Nullable
    private static String getString(@Nullable Object data, @NonNull String key) {
        try {
            if (data instanceof JSONObject) return String.valueOf(((JSONObject) data).opt(key));
            if (data instanceof Map) return String.valueOf(((Map<?, ?>) data).get(key));
        } catch (Throwable ignore) {}
        return null;
    }

    @Nullable private static Long toLongOrNull(@Nullable Object v) {
        try { return v == null ? null : Long.parseLong(String.valueOf(v)); }
        catch (Throwable ignore) { return null; }
    }

    @Nullable private static Long firstLong(@Nullable Long... v) {
        for (Long x : v) if (x != null) return x;
        return null;
    }

    @Nullable private static String firstString(@Nullable String... v) {
        for (String x : v) if (x != null && !x.isEmpty()) return x;
        return null;
    }
}

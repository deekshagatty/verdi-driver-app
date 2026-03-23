package com.example.loginapp;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.loginapp.push.AblyTaskStatusHandler;

import org.json.JSONObject;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.ably.lib.realtime.AblyRealtime;
import io.ably.lib.realtime.Channel;
import io.ably.lib.realtime.ChannelState;
import io.ably.lib.realtime.ChannelStateListener;
import io.ably.lib.realtime.ConnectionStateListener;
import io.ably.lib.types.AblyException;
import io.ably.lib.types.ClientOptions;
import io.ably.lib.types.Message;
import io.ably.lib.types.Param;

/**
 * AblyPushManager (FULL)
 *
 * ✅ Connects to private:driver-{driverId}
 * ✅ Subscribes to events:
 *    - "driver-updates"
 *    - "auto-allocation"   (optional; keep if backend uses it)
 *
 * ✅ Parses:
 *    - type: assigned | active | unassigned | cancelled
 *    - txId: transaction_id OR transaction.id OR "tx_payload" like "130,10"
 *    - secs: secs OR seconds OR offer_secs OR from "tx_payload" like "130,10"
 *    - driver id: driver_id OR driver.id
 *    - online flag: isOnline / onDuty
 *
 * ✅ Emits:
 *    - onDuty(boolean)
 *    - onTx(TxEvent{txId, secs, type})
 */
public final class AblyPushManager {

    // --------- EVENTS ----------

    public static final class TxEvent {
        public final long txId;
        public final int secs; // offer seconds (e.g. 10)
        @NonNull public final String type; // assigned | active | unassigned | cancelled

        public TxEvent(long txId, int secs, @NonNull String type) {
            this.txId = txId;
            this.secs = secs;
            this.type = type;
        }

        @Override public String toString() {
            return "TxEvent{txId=" + txId + ", secs=" + secs + ", type=" + type + "}";
        }
    }

    public interface TxEventListener {
        void onTx(@NonNull TxEvent event);
    }

    public interface DutyListener {
        void onDuty(boolean on);
    }

    // --------- INTERNALS ----------

    private static final String TAG      = "ABLY";
    private static final String AUTH_URL = "https://api.tryverdi.com/api/ably/driver_auth";

    // ✅ Subscribe to both (if backend only uses one, the other will just be silent)
    private static final String[] EVENT_NAMES = new String[]{
            "driver-updates",
            "auto-allocation",

            // ✅ backend publish name (EXACT)
            "Task Status Updated",

    };


    private static final String PREFS = "verdi_prefs";
    private static final String KEY_ON_DUTY = "on_duty_toggle_state";
    public static final String ACTION_DUTY_CHANGED = "com.example.loginapp.ACTION_DUTY_CHANGED";
    public static final String EXTRA_ON = "on";


    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private AblyRealtime ably;
    private Channel channel;

    private TxEventListener txListener;
    private DutyListener dutyListener;

    private boolean subscribed = false;

    // Deduping (txId + type within short window)
    private long lastTxId = 0;
    private String lastType = "";
    private long lastTxAtMs = 0;

    private Boolean lastOnline = null;

    public AblyPushManager(@NonNull Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public synchronized void start(@NonNull TxEventListener txListener,
                                   @Nullable DutyListener dutyListener) {
        this.txListener = txListener;
        this.dutyListener = dutyListener;

        if (ably != null) {
            Log.w(TAG, "start(): already running");
            return;
        }

        final long myDriverId = AuthPrefs.driverId(ctx);
        if (myDriverId <= 0) {
            Log.e(TAG, "start(): driverId missing");
            return;
        }

        final String token = pickBearerToken(ctx);
        if (token.isEmpty()) {
            Log.e(TAG, "start(): token missing");
            return;
        }

        try {
            ClientOptions opts = new ClientOptions();
            opts.authUrl = AUTH_URL;

            // ✅ Ably Java needs Param[] for headers
            opts.authHeaders = new Param[]{
                    new Param("Authorization", "Bearer " + token)
            };

            ably = new AblyRealtime(opts);

            ably.connection.on(new ConnectionStateListener() {
                @Override
                public void onConnectionStateChanged(ConnectionStateChange state) {
                    Log.e(TAG, "CONN=" + state.current.name());
                    if (state.reason != null) Log.e(TAG, "CONN_REASON=" + state.reason.message);
                }
            });

            final String channelName = "private:driver-" + myDriverId;
            channel = ably.channels.get(channelName);

            channel.on(new ChannelStateListener() {
                @Override
                public void onChannelStateChanged(ChannelStateChange state) {
                    Log.e(TAG, "CHANNEL=" + channelName + " STATE=" + state.current.name());
                    if (state.reason != null) Log.e(TAG, "CHANNEL_REASON=" + state.reason.message);

                    if (state.current == ChannelState.attached && !subscribed) {
                        subscribed = true;
                        Log.e(TAG, "ATTACHED OK -> subscribe events...");

                        try {
                            for (String eventName : EVENT_NAMES) {
                                final String ev = eventName;
                                channel.subscribe(ev, new Channel.MessageListener() {
                                    @Override public void onMessage(Message message) {
                                        handleMessage(message, myDriverId, ev);
                                    }
                                });
                                Log.e(TAG, "SUBSCRIBED event=" + ev);
                            }
                        } catch (AblyException e) {
                            Log.e(TAG, "subscribe error", e);
                        }
                    }
                }
            });

            Log.e(TAG, "ATTACH channel=" + channelName);
            channel.attach();

        } catch (AblyException e) {
            Log.e(TAG, "Ably init error", e);
            stop();
        }
    }

    // Backward-compatible overload
    public synchronized void start(@NonNull TxEventListener txListener) {
        start(txListener, null);
    }

    public synchronized void stop() {
        try {
            if (channel != null) {
                try { channel.unsubscribe(); } catch (Throwable ignore) {}
                try { channel.detach(); } catch (Throwable ignore) {}
            }
            if (ably != null) {
                try { ably.close(); } catch (Throwable ignore) {}
            }
        } finally {
            channel = null;
            ably = null;
            txListener = null;
            dutyListener = null;
            subscribed = false;

            lastTxId = 0;
            lastType = "";
            lastTxAtMs = 0;
            lastOnline = null;
        }
        Log.e(TAG, "STOPPED");
    }

    private void handleMessage(@NonNull Message message, long myDriverId, @NonNull String eventName) {
        try {
            Log.e(TAG, "RX event=" + eventName + " name=" + message.name);
            Log.e(TAG, "RX data=" + String.valueOf(message.data));

            // =========================================================
            // ✅ 0) STATUS UPDATE EVENT (Admin status changed)
            // Event name = "Task Status Updated"
            // We DO NOT treat this as assignment/offer.
            // =========================================================
            final String msgName = safe(message.name);

            if (eventName.equalsIgnoreCase("Task Status Updated")
                    || msgName.equalsIgnoreCase("Task Status Updated")) {

                String raw = extractInnerData(message.data);

                Log.e(TAG, "ABLY STATUS RX name=" + msgName + " event=" + eventName + " raw=" + raw);

                boolean consumed = AblyTaskStatusHandler.handle(ctx, raw, raw);
                Log.e(TAG, "ABLY status consumed=" + consumed);
                return;
            }

            Boolean online = null;
            if (online != null) {
                if (lastOnline == null || lastOnline.booleanValue() != online.booleanValue()) {
                    lastOnline = online;
                    final boolean finalOnline = online;

                    Log.e(TAG, "ONLINE_UPDATE -> " + (finalOnline ? "1" : "0"));

                    // ✅ 1) Save to prefs ALWAYS (works even if HomeActivity is not active)
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_ON_DUTY, finalOnline)
                            .apply();

                    // ✅ 2) Broadcast so UI can update if it’s open
                    Intent bi = new Intent(ACTION_DUTY_CHANGED);
                    bi.putExtra(EXTRA_ON, finalOnline);
                    androidx.localbroadcastmanager.content.LocalBroadcastManager
                            .getInstance(ctx)
                            .sendBroadcast(bi);

                    // ✅ 3) Keep existing callback too (if HomeActivity is alive)
                    if (dutyListener != null) {
                        main.post(() -> dutyListener.onDuty(finalOnline));
                    }
                }
            }


            // =========================================================
            // ✅ 1) NORMAL ASSIGNMENT / DUTY EVENTS (existing logic)
            // =========================================================

            Object root = normalizeToObject(message.data);

            // ✅ Support nested: payload.driver
            Object driverObj = getObject(root, "driver");
            Object effectiveForDriver = (driverObj != null) ? driverObj : root;

            // driver id can be: driver_id OR driver.id
            Long msgDriverId = firstLong(
                    getLong(effectiveForDriver, "id"),
                    getLong(root, "driver_id"),
                    getLong(root, "driverId"),
                    getLong(driverObj, "id")
            );

            if (msgDriverId != null && msgDriverId > 0 && msgDriverId != myDriverId) {
                Log.e(TAG, "RX ignored: driver mismatch msg=" + msgDriverId + " mine=" + myDriverId);
                return;
            }

            // ✅ ONLINE FLAG: isOnline (preferred) or onDuty legacy
            online = firstBool(
                    getBool(effectiveForDriver, "isOnline", "is_online", "online"),
                    getBool(root, "isOnline", "is_online", "online"),
                    getBool(effectiveForDriver, "onDuty", "on_duty"),
                    getBool(root, "onDuty", "on_duty")
            );

            // ✅ TX ID: transaction_id OR transaction.id OR from tx_payload "130,10"
            Object txnObj = getObject(root, "transaction");

            Long txId = firstLong(
                    getLong(root, "transaction_id"),
                    getLong(root, "transactionId"),
                    getLong(txnObj, "id")
            );

            // If still missing, try tx_payload style "130,10"
            // If still missing, try offer payload style: root.data like "130,10"
            if (txId == null || txId <= 0) {
                String payload = firstString(
                        getString(root, "data"),
                        getString(root, "tx_payload", "payload"),
                        getString(txnObj, "data"),
                        getString(txnObj, "tx_payload", "payload")
                );

                TxPayload p = parseTxPayload(payload);
                if (p != null && p.txId > 0) txId = p.txId;
            }

            // ✅ SECS: direct fields OR from tx_payload "130,10"
            Integer secs = null;

            Long secsLong = firstLong(
                    getLong(root, "secs"),
                    getLong(root, "seconds"),
                    getLong(root, "offer_secs"),
                    getLong(txnObj, "secs"),
                    getLong(txnObj, "seconds"),
                    getLong(txnObj, "offer_secs")
            );
            if (secsLong != null) secs = secsLong.intValue();

            if (secs == null || secs <= 0) {
                String payload = firstString(
                        getString(root, "data"),
                        getString(root, "tx_payload", "payload"),
                        getString(txnObj, "data"),
                        getString(txnObj, "tx_payload", "payload")
                );

                TxPayload p = parseTxPayload(payload);
                if (p != null && p.secs > 0) secs = p.secs;
            }

            int finalSecs = (secs != null && secs > 0) ? secs : 0;

            // ✅ TASK TYPE: root.type OR transaction.type OR name/event fallback
            String type = firstString(
                    getString(root, "type", "task_type", "status"),
                    getString(txnObj, "type", "task_type", "status")
            );

            // Some backends send message.name as the type
            if (type == null || type.trim().isEmpty()) type = safe(message.name);

            // Some backends may only differentiate via event name
            if (type == null || type.trim().isEmpty()) type = safe(eventName);

            type = safe(type).toLowerCase();

            // Fire duty listener
            if (online != null && dutyListener != null) {
                if (lastOnline == null || lastOnline.booleanValue() != online.booleanValue()) {
                    lastOnline = online;
                    boolean finalOnline = online;
                    Log.e(TAG, "ONLINE_UPDATE -> " + (finalOnline ? "1" : "0"));

                    main.post(() -> {
                        if (dutyListener != null) dutyListener.onDuty(finalOnline);
                    });
                }
            }

            // Fire tx listener (dedupe by txId + type within 1.5s)
            if (txId != null && txId > 0 && txListener != null) {
                long now = System.currentTimeMillis();
                if (txId == lastTxId && type.equals(lastType) && (now - lastTxAtMs) < 1500L) {
                    Log.d(TAG, "Deduped tx=" + txId + " type=" + type);
                    return;
                }
                lastTxId = txId;
                lastType = type;
                lastTxAtMs = now;

                final TxEvent ev = new TxEvent(txId, finalSecs, type);

                main.post(() -> {
                    if (txListener != null) txListener.onTx(ev);
                });
            }

        } catch (Throwable t) {
            Log.e(TAG, "handleMessage error", t);
        }
    }


    // ---------------- helpers ----------------

    @NonNull
    private static String pickBearerToken(@NonNull Context ctx) {
        String raw = AuthPrefs.token(ctx);
        if (raw == null) raw = "";
        raw = raw.trim();
        if (raw.startsWith("Bearer ")) raw = raw.substring("Bearer ".length()).trim();
        return raw;
    }

    /** Normalize Ably message.data into either JSONObject/Map/String (we keep as Object) */
    @Nullable
    private static Object normalizeToObject(@Nullable Object data) {
        if (data == null) return null;

        if (data instanceof JSONObject) return data;
        if (data instanceof Map) return data;

        String s = String.valueOf(data).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;

        try { return new JSONObject(s); }
        catch (Throwable ignore) { return data; }
    }

    @Nullable
    private static Object getObject(@Nullable Object data, @NonNull String key) {
        if (data == null) return null;

        try {
            if (data instanceof JSONObject) {
                JSONObject j = (JSONObject) data;
                if (!j.has(key)) return null;
                Object v = j.opt(key);
                if (v instanceof JSONObject) return v;
                if (v instanceof Map) return v;
                if (v != null) {
                    String s = String.valueOf(v).trim();
                    try { return new JSONObject(s); } catch (Throwable ignore) {}
                }
            } else if (data instanceof Map) {
                Object v = ((Map<?, ?>) data).get(key);
                if (v instanceof Map || v instanceof JSONObject) return v;
                if (v != null) {
                    String s = String.valueOf(v).trim();
                    try { return new JSONObject(s); } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable ignore) {}

        return null;
    }

    @Nullable
    private static Long getLong(@Nullable Object data, @NonNull String key) {
        if (data == null) return null;

        try {
            if (data instanceof JSONObject) {
                JSONObject j = (JSONObject) data;
                if (!j.has(key)) return null;
                Object v = j.opt(key);
                return toLongOrNull(v);
            }

            if (data instanceof Map) {
                Object v = ((Map<?, ?>) data).get(key);
                return toLongOrNull(v);
            }

            String s = String.valueOf(data).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;

            try {
                JSONObject j = new JSONObject(s);
                if (j.has(key)) return toLongOrNull(j.opt(key));
            } catch (Throwable ignore) {}

            Matcher m = Pattern.compile(key + "\"?\\s*[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(s);
            if (m.find()) return Long.parseLong(m.group(1));

        } catch (Throwable ignore) {}

        return null;
    }

    @Nullable
    private static Boolean getBool(@Nullable Object data, @NonNull String... keys) {
        if (data == null) return null;

        for (String key : keys) {
            Object v = null;

            try {
                if (data instanceof JSONObject) {
                    JSONObject j = (JSONObject) data;
                    if (j.has(key)) v = j.opt(key);
                } else if (data instanceof Map) {
                    v = ((Map<?, ?>) data).get(key);
                } else {
                    String s = String.valueOf(data).trim();
                    if (s.isEmpty() || "null".equalsIgnoreCase(s)) continue;

                    try {
                        JSONObject j = new JSONObject(s);
                        if (j.has(key)) v = j.opt(key);
                    } catch (Throwable ignore) {
                        Matcher m = Pattern
                                .compile(key + "\"?\\s*[:=]\\s*(true|false|1|0|yes|no|on|off)",
                                        Pattern.CASE_INSENSITIVE)
                                .matcher(s);
                        if (m.find()) v = m.group(1);
                    }
                }

                Boolean b = toBoolOrNull(v);
                if (b != null) return b;

            } catch (Throwable ignore) {}
        }

        return null;
    }

    @Nullable
    private static String getString(@Nullable Object data, @NonNull String... keys) {
        if (data == null) return null;

        for (String key : keys) {
            Object v = null;

            try {
                if (data instanceof JSONObject) {
                    JSONObject j = (JSONObject) data;
                    if (j.has(key)) v = j.opt(key);
                } else if (data instanceof Map) {
                    v = ((Map<?, ?>) data).get(key);
                } else {
                    String s = String.valueOf(data).trim();
                    if (s.isEmpty() || "null".equalsIgnoreCase(s)) continue;

                    try {
                        JSONObject j = new JSONObject(s);
                        if (j.has(key)) v = j.opt(key);
                    } catch (Throwable ignore) {
                        Matcher m = Pattern
                                .compile(key + "\"?\\s*[:=]\\s*\"?([a-zA-Z0-9_\\-]+)\"?",
                                        Pattern.CASE_INSENSITIVE)
                                .matcher(s);
                        if (m.find()) v = m.group(1);
                    }
                }

                if (v == null) continue;
                String out = String.valueOf(v).trim();
                if (out.isEmpty() || "null".equalsIgnoreCase(out)) continue;
                return out;

            } catch (Throwable ignore) {}
        }

        return null;
    }

    @Nullable
    private static Long firstLong(@Nullable Long... vals) {
        if (vals == null) return null;
        for (Long v : vals) if (v != null) return v;
        return null;
    }

    @Nullable
    private static Boolean firstBool(@Nullable Boolean... vals) {
        if (vals == null) return null;
        for (Boolean v : vals) if (v != null) return v;
        return null;
    }

    @Nullable
    private static String firstString(@Nullable String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null) {
                String s = v.trim();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    @Nullable
    private static Long toLongOrNull(@Nullable Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
            return Long.parseLong(s);
        } catch (Throwable ignore) {
            return null;
        }
    }

    @Nullable
    private static Boolean toBoolOrNull(@Nullable Object v) {
        if (v == null) return null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;

        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty() || s.equals("null")) return null;

        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off")) return false;

        return null;
    }

    @NonNull
    private static String safe(@Nullable String s) {
        return s == null ? "" : s.trim();
    }

    // ---- tx payload parser ----
    private static final class TxPayload {
        final long txId;
        final int secs;
        TxPayload(long txId, int secs) { this.txId = txId; this.secs = secs; }
    }

    /** Supports "130,10" or "130" */
    @Nullable
    private static TxPayload parseTxPayload(@Nullable String raw) {
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) return null;

        try {
            Matcher m = Pattern.compile("^(\\d+)(?:\\s*,\\s*(\\d+))?$").matcher(raw);
            if (m.find()) {
                long id = Long.parseLong(m.group(1));
                int secs = (m.group(2) != null) ? Integer.parseInt(m.group(2)) : 0;
                return new TxPayload(id, secs);
            }

            m = Pattern.compile("(\\d{1,})").matcher(raw);
            if (m.find()) return new TxPayload(Long.parseLong(m.group(1)), 0);

        } catch (Exception ignore) {}

        return null;
    }

    @NonNull
    private static String extractInnerData(@Nullable Object d) {
        if (d == null) return "";

        try {
            // Map (LinkedTreeMap, HashMap etc.)
            if (d instanceof Map) {
                Object v = ((Map<?, ?>) d).get("data");
                return v != null ? String.valueOf(v).trim() : "";
            }

            // JSONObject already
            if (d instanceof JSONObject) {
                return ((JSONObject) d).optString("data", "").trim();
            }

            // String -> maybe JSON -> maybe already "24,38,success"
            String s = String.valueOf(d).trim();
            if (s.isEmpty()) return "";

            // if it looks like json, parse it
            if (s.startsWith("{") && s.endsWith("}")) {
                JSONObject j = new JSONObject(s);
                return j.optString("data", s).trim();
            }

            return s;

        } catch (Throwable t) {
            return String.valueOf(d).trim();
        }
    }

}

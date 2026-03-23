package com.example.loginapp;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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

public final class AblySettingsManager {

    private static final String TAG = "ABLY_SETTINGS";

    // ✅ as requested
    private static final String AUTH_URL   = "https://api.tryverdi.com/api/ably/driver_auth";
    private static final String CHANNEL    = "private:drivers";
    private static final String EVENT_NAME = "update_settings";

    private final Context appCtx;

    private AblyRealtime ably;
    private Channel channel;
    private boolean subscribed = false;

    public AblySettingsManager(@NonNull Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public synchronized void start() {
        if (ably != null) {
            Log.w(TAG, "start(): already running");
            return;
        }

        final String token = pickBearerToken(appCtx);
        if (token.isEmpty()) {
            Log.e(TAG, "start(): token missing");
            return;
        }

        try {
            ClientOptions opts = new ClientOptions();
            opts.authUrl = AUTH_URL;

            // ✅ Ably Java needs Param[] headers
            opts.authHeaders = new Param[] {
                    new Param("Authorization", "Bearer " + token)
            };

            ably = new AblyRealtime(opts);

            channel = ably.channels.get(CHANNEL);

            channel.on(new ChannelStateListener() {
                @Override
                public void onChannelStateChanged(ChannelStateChange state) {
                    Log.e(TAG, "CHANNEL=" + CHANNEL + " STATE=" + state.current.name());
                    if (state.reason != null) Log.e(TAG, "CHANNEL_REASON=" + state.reason.message);

                    if (state.current == ChannelState.attached && !subscribed) {
                        subscribed = true;
                        Log.e(TAG, "ATTACHED OK -> subscribe event=" + EVENT_NAME);

                        try {
                            channel.subscribe(EVENT_NAME, msg -> handleSettings(msg));
                        } catch (AblyException e) {
                            Log.e(TAG, "subscribe error", e);
                        }
                    }
                }
            });

            Log.e(TAG, "ATTACH channel=" + CHANNEL);
            channel.attach();

        } catch (AblyException e) {
            Log.e(TAG, "Ably init error", e);
            stop();
        }
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
            subscribed = false;
        }
        Log.e(TAG, "STOPPED");
    }

    private void handleSettings(@NonNull Message message) {
        try {
            Log.e(TAG, "RX name=" + message.name);
            Log.e(TAG, "RX data=" + String.valueOf(message.data));

            Object root = normalizeToObject(message.data);

            String type = firstString(
                    getString(root, "type"),
                    getString(root, "name"),
                    getString(root, "setting")
            );
            if (type == null) type = "";
            type = type.trim().toLowerCase();

            Long interval = firstLong(
                    getLong(root, "interval"),
                    getLong(root, "value")
            );

            if (interval == null || interval <= 0) {
                Log.w(TAG, "settings ignored: interval missing");
                return;
            }

            if ("driver_polling".equals(type)) {
                long ms = interval * 1000L; // seconds -> ms
                SettingsPrefs.setDriverPollingMs(appCtx, ms);
                Log.e(TAG, "✅ driver_polling -> " + interval + "s (" + ms + "ms)");
                return;
            }

            if ("trip_polling".equals(type)) {
                SettingsPrefs.setTripPollingMeters(appCtx, interval.floatValue());
                Log.e(TAG, "✅ trip_polling -> " + interval + " meters");
                return;
            }

            Log.w(TAG, "Unknown type=" + type + " interval=" + interval);

        } catch (Throwable t) {
            Log.e(TAG, "handleSettings error", t);
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
    private static Long getLong(@Nullable Object data, @NonNull String key) {
        if (data == null) return null;

        try {
            if (data instanceof JSONObject) {
                JSONObject j = (JSONObject) data;
                if (!j.has(key)) return null;
                return toLongOrNull(j.opt(key));
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
    private static String getString(@Nullable Object data, @NonNull String key) {
        if (data == null) return null;

        try {
            Object v = null;

            if (data instanceof JSONObject) {
                JSONObject j = (JSONObject) data;
                if (j.has(key)) v = j.opt(key);
            } else if (data instanceof Map) {
                v = ((Map<?, ?>) data).get(key);
            } else {
                String s = String.valueOf(data).trim();
                if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;

                try {
                    JSONObject j = new JSONObject(s);
                    if (j.has(key)) v = j.opt(key);
                } catch (Throwable ignore) {}
            }

            if (v == null) return null;
            String out = String.valueOf(v).trim();
            if (out.isEmpty() || "null".equalsIgnoreCase(out)) return null;
            return out;

        } catch (Throwable ignore) {}

        return null;
    }

    @Nullable
    private static Long firstLong(@Nullable Long... vals) {
        if (vals == null) return null;
        for (Long v : vals) if (v != null) return v;
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
}

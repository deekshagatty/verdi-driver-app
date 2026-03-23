package com.example.loginapp;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

public final class SettingsPrefs {

    private SettingsPrefs() {}

    private static final String PREFS = "verdi_prefs";

    // driver polling (milliseconds)
    private static final String KEY_DRIVER_POLLING_MS = "driver_polling_ms";
    private static final long DEFAULT_DRIVER_POLLING_MS = 10_000L;

    // trip polling (meters)
    private static final String KEY_TRIP_POLLING_METERS = "trip_polling_meters";
    private static final float DEFAULT_TRIP_POLLING_METERS = 100f;

    private static SharedPreferences sp(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static long driverPollingMs(@NonNull Context ctx) {
        return sp(ctx).getLong(KEY_DRIVER_POLLING_MS, DEFAULT_DRIVER_POLLING_MS);
    }

    public static void setDriverPollingMs(@NonNull Context ctx, long ms) {
        if (ms < 2_000L) ms = 2_000L;           // safety min
        if (ms > 300_000L) ms = 300_000L;       // safety max 5 min
        sp(ctx).edit().putLong(KEY_DRIVER_POLLING_MS, ms).apply();
    }

    public static float tripPollingMeters(@NonNull Context ctx) {
        return sp(ctx).getFloat(KEY_TRIP_POLLING_METERS, DEFAULT_TRIP_POLLING_METERS);
    }

    public static void setTripPollingMeters(@NonNull Context ctx, float meters) {
        if (meters < 10f) meters = 10f;
        if (meters > 5_000f) meters = 5_000f;
        sp(ctx).edit().putFloat(KEY_TRIP_POLLING_METERS, meters).apply();
    }

    // keys for listeners
    public static String keyDriverPollingMs() { return KEY_DRIVER_POLLING_MS; }
    public static String keyTripPollingMeters() { return KEY_TRIP_POLLING_METERS; }
}

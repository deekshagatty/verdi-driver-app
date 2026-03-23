package com.example.loginapp;

import android.content.Context;
import android.content.SharedPreferences;

public final class AuthPrefs {
    private static final String P = "verdi_prefs";
    private static final String K_TOKEN    = "auth_token";
    private static final String K_DRIVERID = "driver_id";
    private static final String K_NAME     = "driver_name";
    private static final String K_USERNAME = "driver_username";
    private static final String K_PHONE    = "driver_phone";
    private static final String K_LOGIN_TS    = "login_ts";
    private static final String K_LAST_ACTIVE = "last_active_ts";
    private static final String K_EXPIRES_AT  = "session_expires_at";
    // 20 minutes sliding expiry
    private static final long SESSION_TTL_MS = 20L * 60L * 1000L;
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }
    public static String token(Context c)     { return sp(c).getString(K_TOKEN, null); }
    public static long   driverId(Context c)  { return sp(c).getLong(K_DRIVERID, 0L); }
    public static String name(Context c)      { return sp(c).getString(K_NAME, null); }
    public static String username(Context c)  { return sp(c).getString(K_USERNAME, null); }
    public static String phone(Context c)     { return sp(c).getString(K_PHONE, null); }
    public static String bearer(Context c) {
        String t = token(c);
        return (t == null || t.isEmpty()) ? "" : "Bearer " + t;
    }
    public static boolean isSessionValid(Context c) {
        String t = token(c);
        if (t == null || t.isEmpty()) return false;
        long exp = sp(c).getLong(K_EXPIRES_AT, 0L);
        return System.currentTimeMillis() < exp;
    }
    public static void touchSession(Context c) {
        String t = token(c);
        if (t == null || t.isEmpty()) return;
        long now = System.currentTimeMillis();
        sp(c).edit()
                .putLong(K_LAST_ACTIVE, now)
                .putLong(K_EXPIRES_AT, now + SESSION_TTL_MS)
                .apply();
    }

    public static void clearLogin(Context c) {
        sp(c).edit()
                .remove(K_TOKEN)
                .remove(K_DRIVERID)
                .remove(K_NAME)
                .remove(K_USERNAME)
                .remove(K_PHONE)
                .remove(K_LOGIN_TS)
                .remove(K_LAST_ACTIVE)
                .remove(K_EXPIRES_AT)
                .apply();
    }

    public static void save(Context c, String token, long driverId, String name, String username, String phone) {
        saveLogin(c, token, driverId, name, username, phone);
    }

    public static void saveLogin(Context c, String token, long driverId, String name, String username, String phone){
        long now = System.currentTimeMillis();
        sp(c).edit()
                .putString(K_TOKEN, token)
                .putLong(K_DRIVERID, driverId)
                .putString(K_NAME, name)
                .putString(K_USERNAME, username)
                .putString(K_PHONE, phone)
                .putLong(K_LOGIN_TS, now)
                .putLong(K_LAST_ACTIVE, now)
                .putLong(K_EXPIRES_AT, now + SESSION_TTL_MS)
                .apply();
    }

    public static void clear(Context c) {
        sp(c).edit()
                .remove(K_TOKEN)
                .remove(K_DRIVERID)
                .remove(K_NAME)
                .remove(K_USERNAME)
                .remove(K_PHONE)
                .apply();
    }
}

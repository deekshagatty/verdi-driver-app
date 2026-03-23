// AuthPrefs.java  ✅ FULL CODE (stores USERNAME as K_NAME so drawer shows username)
// package: com.example.loginapp

package com.example.loginapp;

import android.content.Context;
import android.content.SharedPreferences;

public final class AuthPrefs {
    private static final String P = "verdi_prefs";

    private static final String K_TOKEN    = "auth_token";
    private static final String K_DRIVERID = "driver_id";

    // ✅ This is what HomeActivity reads for drawer title:
    // String name = AuthPrefs.name(this);
    // tvDrawerTitle.setText("VERDI - " + name)
    private static final String K_NAME     = "driver_name";

    private static final String K_LOGIN_TS    = "login_ts";
    private static final String K_LAST_ACTIVE = "last_active_ts";

    // ✅ local logout flag (ONLY this should invalidate a session)
    private static final String K_LOGGED_OUT = "is_logged_out";

    private AuthPrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    public static String token(Context c)     { return sp(c).getString(K_TOKEN, null); }
    public static long   driverId(Context c)  { return sp(c).getLong(K_DRIVERID, 0L); }

    // ✅ drawer reads this
    public static String name(Context c)      { return sp(c).getString(K_NAME, null); }

    public static String bearer(Context c) {
        String t = token(c);
        if (t == null) return "";
        t = t.trim();
        if (t.isEmpty()) return "";
        return t.startsWith("Bearer ") ? t : ("Bearer " + t);
    }

    public static boolean isLoggedOut(Context c) {
        return sp(c).getBoolean(K_LOGGED_OUT, true); // default true (until login)
    }

    // ✅ commit() so next Activity reads immediately
    public static void setLoggedOut(Context c, boolean loggedOut) {
        sp(c).edit().putBoolean(K_LOGGED_OUT, loggedOut).commit();
    }

    /**
     * ✅ Manual-only session validity:
     * - valid if NOT logged out AND token exists AND driverId exists
     * - NO time-based expiry
     */
    public static boolean isSessionValid(Context c) {
        if (isLoggedOut(c)) return false;

        String t = token(c);
        if (t == null || t.trim().isEmpty()) return false;

        long id = driverId(c);
        return id > 0;
    }

    /**
     * ✅ Optional analytics only (NO expiry extension)
     */
    public static void touchSession(Context c) {
        if (isLoggedOut(c)) return;
        String t = token(c);
        if (t == null || t.trim().isEmpty()) return;

        long now = System.currentTimeMillis();
        sp(c).edit().putLong(K_LAST_ACTIVE, now).apply();
    }

    public static void setDriverId(Context c, long driverId) {
        sp(c).edit().putLong(K_DRIVERID, driverId).apply();
    }

    public static void save(Context c, String token, long driverId, String name, String username, String phone) {
        saveLogin(c, token, driverId, name, username, phone);
    }

    /**
     * ✅ IMPORTANT CHANGE:
     * Store USERNAME into K_NAME so drawer title shows username.
     */
    public static void saveLogin(Context c, String token, long driverId, String name, String username, String phone){
        long now = System.currentTimeMillis();

        String drawerName =
                (username != null && !username.trim().isEmpty())
                        ? username.trim()
                        : (name != null ? name.trim() : "");

        sp(c).edit()
                .putString(K_TOKEN, token)
                .putLong(K_DRIVERID, driverId)

                // ✅ drawer shows username now
                .putString(K_NAME, drawerName)

                .putLong(K_LOGIN_TS, now)
                .putLong(K_LAST_ACTIVE, now)
                .putBoolean(K_LOGGED_OUT, false)
                .commit();
    }

    // ✅ commit() so logout is immediate
    public static void clearLogin(Context c) {
        sp(c).edit()
                .putBoolean(K_LOGGED_OUT, true)
                .remove(K_TOKEN)
                .remove(K_DRIVERID)
                .remove(K_NAME)
                .remove(K_LOGIN_TS)
                .remove(K_LAST_ACTIVE)
                .commit();
    }

    // keep for compatibility
    public static void clear(Context c) {
        clearLogin(c);
    }

    public static String safeBearer(Context ctx) {
        String b = bearer(ctx);
        if (b != null && b.trim().startsWith("Bearer ")) return b.trim();

        String raw = token(ctx);
        if (raw == null) raw = "";
        raw = raw.trim();
        if (raw.startsWith("Bearer ")) raw = raw.substring(7).trim();
        return raw.isEmpty() ? "" : ("Bearer " + raw);
    }

}

package com.example.loginapp;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Local-only driver state cache (NO Firestore).
 */
public final class FsDriver {

    private FsDriver() {}

    private static final String PREFS = "verdi_prefs";

    private static final String KEY_ACCEPTED_TX_IDS = "accepted_tx_ids_set";
    private static final String KEY_HAS_TASK        = "local_having_task";
    private static final String KEY_LAST_TX_ID      = "local_last_tx_id";

    // Keep consistent with HomeActivity / LocationPingService if you use it
    private static final String KEY_ACTIVE_TX       = "active_txid";

    // ---------- Accepted TX ----------

    public static void addAcceptedTxLocal(Context ctx, long txId) {
        if (txId <= 0) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(sp.getStringSet(KEY_ACCEPTED_TX_IDS, new HashSet<>()));
        set.add(String.valueOf(txId));
        sp.edit().putStringSet(KEY_ACCEPTED_TX_IDS, set).apply();
    }

    public static void removeAcceptedTxLocal(Context ctx, long txId) {
        if (txId <= 0) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(sp.getStringSet(KEY_ACCEPTED_TX_IDS, new HashSet<>()));
        set.remove(String.valueOf(txId));
        sp.edit().putStringSet(KEY_ACCEPTED_TX_IDS, set).apply();
    }

    public static boolean isTxAccepted(Context ctx, long txId) {
        if (txId <= 0) return false;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = sp.getStringSet(KEY_ACCEPTED_TX_IDS, new HashSet<>());
        return set != null && set.contains(String.valueOf(txId));
    }

    public static Set<Long> getAcceptedTxIds(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> raw = sp.getStringSet(KEY_ACCEPTED_TX_IDS, new HashSet<>());
        HashSet<Long> out = new HashSet<>();
        if (raw != null) {
            for (String s : raw) {
                try { out.add(Long.parseLong(s)); } catch (Exception ignored) {}
            }
        }
        return out;
    }

    public static void clearAcceptedTxIds(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_ACCEPTED_TX_IDS).apply();
    }

    // ---------- Having task ----------

    public static void setHavingTask(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_HAS_TASK, v).apply();
    }

    public static boolean hasTask(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_HAS_TASK, false);
    }

    // ---------- Last TX ----------

    public static void setLastTxId(Context ctx, @Nullable String txId) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_TX_ID, txId).apply();
    }

    public static @Nullable String getLastTxId(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_TX_ID, null);
    }

    // ---------- Active TX (optional) ----------

    public static void setActiveTx(Context ctx, long txId) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_ACTIVE_TX, txId).apply();
    }

    public static long getActiveTx(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_ACTIVE_TX, 0L);
    }

    public static void clearAllLocal(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_ACCEPTED_TX_IDS)
                .remove(KEY_HAS_TASK)
                .remove(KEY_LAST_TX_ID)
                .remove(KEY_ACTIVE_TX)
                .apply();
    }
}

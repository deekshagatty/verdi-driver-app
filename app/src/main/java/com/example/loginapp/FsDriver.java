package com.example.loginapp;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.HashSet;

public final class FsDriver {

    private FsDriver() { }

    private static final String PREFS = "verdi_prefs";
    private static final String KEY_ACCEPTED_TX_IDS = "accepted_tx_ids_set";

    public static @Nullable DocumentReference driverDoc(Context ctx) {
        long driverId = AuthPrefs.driverId(ctx);
        if (driverId <= 0) return null;
        return FirebaseFirestore.getInstance()
                .collection("drivers")
                .document(String.valueOf(driverId));
    }

    public static @Nullable DocumentReference presenceDoc(Context ctx) {
        DocumentReference d = driverDoc(ctx);
        return d == null ? null : d.collection("presence").document("current");
    }

    public static void addAcceptedTxLocal(Context ctx, long txId) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        HashSet<String> set = new HashSet<>(sp.getStringSet(KEY_ACCEPTED_TX_IDS, new HashSet<>()));
        set.add(String.valueOf(txId));
        sp.edit().putStringSet(KEY_ACCEPTED_TX_IDS, set).apply();
    }

    public static void mirrorHavingTask(Context ctx, boolean value) {
        DocumentReference d = driverDoc(ctx);
        DocumentReference p = presenceDoc(ctx);
        if (d == null || p == null) return;
        long driverId = AuthPrefs.driverId(ctx);

        HashMap<String, Object> root = new HashMap<>();
        root.put("havingtask", value);
        root.put("task_updated_at", FieldValue.serverTimestamp());
        root.put("driver_id", driverId);
        d.set(root, SetOptions.merge());

        HashMap<String, Object> presence = new HashMap<>();
        presence.put("havingtask", value);
        presence.put("task_updated_at", FieldValue.serverTimestamp());
        presence.put("driver_id", driverId);
        p.set(presence, SetOptions.merge());
    }

    public static void mirrorLastTransactionId(Context ctx, String txId) {
        if (txId == null || txId.isEmpty()) return;
        DocumentReference d = driverDoc(ctx);
        DocumentReference p = presenceDoc(ctx);
        if (d == null || p == null) return;
        long driverId = AuthPrefs.driverId(ctx);

        HashMap<String, Object> root = new HashMap<>();
        root.put("transactionsId", txId);
        root.put("last_transaction_at", FieldValue.serverTimestamp());
        root.put("driver_id", driverId);
        d.set(root, SetOptions.merge());

        HashMap<String, Object> presence = new HashMap<>();
        presence.put("last_transaction_id", txId);
        presence.put("last_transaction_at", FieldValue.serverTimestamp());
        presence.put("driver_id", driverId);
        p.set(presence, SetOptions.merge());
    }

    public static void addActiveTxFirestore(Context ctx, long txId) {
        DocumentReference d = driverDoc(ctx);
        if (d == null) return;
        long driverId = AuthPrefs.driverId(ctx);
        HashMap<String, Object> seed = new HashMap<>();
        seed.put("driver_id", driverId);
        d.set(seed, SetOptions.merge());
        d.update(
                "active_transactions", FieldValue.arrayUnion(txId),
                "havingtask", true,
                "task_updated_at", FieldValue.serverTimestamp(),
                "driver_id", driverId
        );
    }
}

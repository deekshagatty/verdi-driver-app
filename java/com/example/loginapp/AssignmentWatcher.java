package com.example.loginapp;

import android.app.Activity;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

public final class AssignmentWatcher {
    private static final String TAG = "AssignmentWatcher";
    private static final String PREFS = "verdi_prefs";
    private static final String KEY_PROCESSED_TX = "processed_tx_ids_set";
    // 🚫 Keep using inbox-only to avoid root field duplicate triggers.
    private static final boolean USE_INBOX_ONLY = true;
    private static AssignmentWatcher INSTANCE;
    public static AssignmentWatcher get() {
        if (INSTANCE == null) INSTANCE = new AssignmentWatcher();
        return INSTANCE;
    }
    private final Set<String> shownInbox = new HashSet<>();
    private ListenerRegistration transactionsWatcher;
    private ListenerRegistration inboxWatcher;
    private WeakReference<Activity> currentActivityRef = new WeakReference<>(null);
    private FirebaseFirestore db;
    private FirebaseAuth.AuthStateListener authListener;
    private boolean watchersAttached = false;
    private final Object lock = new Object();
    private final Set<String> inFlight = new HashSet<>();
    private android.content.Context appCtx;
    // global cool-down to block rapid re-show from any source (5s)
    private long lastAnyShownAtMs = 0L;
    private AssignmentWatcher() {}
    public void start(android.content.Context appContext) {
        this.appCtx = appContext.getApplicationContext();
        if (db == null) db = FirebaseFirestore.getInstance();
        if (watchersAttached) return;

        FirebaseAuth fa = FirebaseAuth.getInstance();
        if (fa.getCurrentUser() != null) {
            attachWatchers();
            return;
        }
        if (authListener != null) fa.removeAuthStateListener(authListener);
        authListener = firebaseAuth -> {
            if (firebaseAuth.getCurrentUser() != null && !watchersAttached) attachWatchers();
        };
        fa.addAuthStateListener(authListener);
    }

    public void stop() {
        if (transactionsWatcher != null) { transactionsWatcher.remove(); transactionsWatcher = null; }
        if (inboxWatcher != null) { inboxWatcher.remove(); inboxWatcher = null; }
        shownInbox.clear();
        synchronized (lock) { inFlight.clear(); }
        currentActivityRef = new WeakReference<>(null);
        watchersAttached = false;

        FirebaseAuth fa = FirebaseAuth.getInstance();
        if (authListener != null) { fa.removeAuthStateListener(authListener); authListener = null; }
    }

    public void attach(Activity a) { currentActivityRef = new WeakReference<>(a); }
    public void detach(Activity a) { if (currentActivityRef.get() == a) currentActivityRef = new WeakReference<>(null); }

    private void attachWatchers() {
        if (watchersAttached) return;
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        watchersAttached = true;
        long driverId = AuthPrefs.driverId(appCtx);
        if (driverId <= 0) {
            Log.w(TAG, "attachWatchers: missing driverId; not attaching.");
            return;
        }
        String driverDocId = String.valueOf(driverId);

        if (!USE_INBOX_ONLY) {
            if (transactionsWatcher != null) transactionsWatcher.remove();
            transactionsWatcher = db.collection("drivers").document(driverDocId)
                    .addSnapshotListener((snap, err) -> {
                        if (err != null || snap == null || !snap.exists()) return;
                        Object v = snap.get("transactionsId");
                        String txId = v == null ? null : String.valueOf(v);
                        if (txId == null || txId.trim().isEmpty()) return;
                        showPopup(txId);
                    });
        }

        if (inboxWatcher != null) inboxWatcher.remove();
        inboxWatcher = db.collection("drivers").document(driverDocId)
                .collection("inbox").orderBy("created_at")
                .addSnapshotListener((qs, err) -> {
                    if (err != null || qs == null) return;
                    for (DocumentChange dc : qs.getDocumentChanges()) {
                        if (dc.getType() != DocumentChange.Type.ADDED) continue;
                        String docId = dc.getDocument().getId();
                        if (!shownInbox.add(docId)) continue;
                        String txId = dc.getDocument().getString("transaction_id");
                        if (txId == null || txId.trim().isEmpty()) continue;
                        showPopup(txId);
                        dc.getDocument().getReference().update("status", "shown");
                    }
                });
    }

    private java.util.Set<String> loadProcessed() {
        android.content.SharedPreferences sp = appCtx.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        return new java.util.HashSet<>(sp.getStringSet(KEY_PROCESSED_TX, new java.util.HashSet<>()));
    }
    private void saveProcessed(java.util.Set<String> set) {
        android.content.SharedPreferences sp = appCtx.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        sp.edit().putStringSet(KEY_PROCESSED_TX, set).apply();
    }

    private String canonical(String txId) {
        if (txId == null) return null;
        String t = txId.trim();
        if (t.isEmpty()) return null;
        try {
            long n = Long.parseLong(t);
            return String.valueOf(n);
        } catch (Exception e) {
            return t;
        }
    }

    private void showPopup(String rawTxId) {
        String txId = canonical(rawTxId);
        if (txId == null) return;

        long now = System.currentTimeMillis();

        synchronized (lock) {
            // global cool-down (5s) to avoid back-to-back duplicates
            if (now - lastAnyShownAtMs < 5000) {
                return;
            }

            // skip if TaskPopup already visible
            if (TaskPopup.isShowing()) {
                return;
            }

            // skip if processed before (persisted)
            java.util.Set<String> processed = loadProcessed();
            if (processed.contains(txId)) {
                return;
            }

            // skip if another watcher already handling this tx
            if (inFlight.contains(txId)) {
                return;
            }

            // claim it: add to in-flight and processed immediately
            inFlight.add(txId);
            processed.add(txId);
            saveProcessed(processed);

            // record global show time now (prevents 2nd show even if same loop re-enters)
            lastAnyShownAtMs = now;
        }

        Activity a = currentActivityRef.get();
        if (a != null && !a.isFinishing() && !a.isDestroyed()) {
            a.runOnUiThread(() -> TaskPopup.show(a, Long.parseLong(txId)));
        } else {
            Log.d(TAG, "No foreground activity to show popup; will show when attached.");
        }
    }

//    public void onPopupDismissed(String rawTxId) {
//        String txId = canonical(rawTxId);
//        if (txId == null) return;
//        synchronized (lock) { inFlight.remove(txId); }
//    }
}

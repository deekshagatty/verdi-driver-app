// app/src/main/java/com/example/loginapp/LocationPingService.java
package com.example.loginapp;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.GeoPoint;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LocationPingService extends Service {
    private static final String TAG = "LocSvc";

    // Foreground + alert channels
    private static final String CH_FG = "duty_tracking";
    private static final int FG_NOTIF_ID = 11001;
    private static final String CH_ALERTS = "assign_alerts_v3";
    private static final int NOTIF_NEW_ASSIGN_BASE = 12000;

    // Cadence
    private static final long PERIOD_MS = 30_000L;     // 30s target
    private static final long WATCHDOG_MS = 45_000L;   // force fix if stale

    // Prefs / intents
    private static final String PREFS = "verdi_prefs";
    private static final String KEY_LAST_TXID = "last_seen_txid";
    private static final String ACTION_STOP_PINGS = "com.example.loginapp.ACTION_STOP_PINGS";

    // Core state
    private Handler handler;
    private FusedLocationProviderClient fused;
    private LocationCallback locationCallback;
    private LocationManager lm;

    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private FirebaseAuth.AuthStateListener authListener;

    private boolean ensuredOnceFields = false;
    private ListenerRegistration driverWatcher;

    private String driverName;
    private String driverUsername;
    private String driverPhone;

    private final Set<Long> activeTxIds = new HashSet<>();
    private android.content.BroadcastReceiver stopReceiver;

    private long lastLocMs = 0L;

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onCreate() {
        super.onCreate();

        fused = LocationServices.getFusedLocationProviderClient(this);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);

        ensureChannels();
        startInForeground(); // start FGS before requesting location

        if (auth.getCurrentUser() == null) auth.signInAnonymously();
        authListener = fb -> {
            FirebaseUser u = fb.getCurrentUser();
            if (u != null && !ensuredOnceFields) {
                ensuredOnceFields = true;
                ensureStaticFieldsOnce();
                startWatchingDriverDoc();
            }
        };
        auth.addAuthStateListener(authListener);

        handler = new Handler(Looper.getMainLooper());
        handler.post(tick);

        // Continuous callback
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) {
                    Log.d(TAG, "onLocationResult: null result");
                    return;
                }
                Location loc = null;
                if (result.getLocations() != null && !result.getLocations().isEmpty()) {
                    loc = result.getLocations().get(result.getLocations().size() - 1);
                } else {
                    loc = result.getLastLocation();
                }
                if (loc == null) {
                    Log.d(TAG, "onLocationResult: no locations");
                    return;
                }

                lastLocMs = System.currentTimeMillis();
                Log.d(TAG, "onLocationResult: " + loc.getLatitude() + "," + loc.getLongitude() + " acc=" + loc.getAccuracy());
                writePresence(loc);
                appendDriverRoutePoints(loc);
            }
        };

        // Start periodic updates, watchdog and poller
        startLocationUpdates();
        if (handler != null) {
            handler.postDelayed(locWatchdog, WATCHDOG_MS);
            handler.postDelayed(locationPoller, PERIOD_MS);
        }

        // Stop pings receiver (accept or explicit stop)
        stopReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                TriplePing.stop(c);
                SinglePing.stop(c);
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stopReceiver, new android.content.IntentFilter(ACTION_STOP_PINGS), Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(stopReceiver, new android.content.IntentFilter(TaskPopup.ACTION_TX_ACCEPTED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stopReceiver, new android.content.IntentFilter(ACTION_STOP_PINGS));
            registerReceiver(stopReceiver, new android.content.IntentFilter(TaskPopup.ACTION_TX_ACCEPTED));
        }

        Log.d(TAG, "hasPerms=" + hasPerms() + ", hasBackgroundPerm=" + hasBackgroundPerm());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            driverName = intent.getStringExtra("driver_name");
            driverUsername = intent.getStringExtra("driver_username");
            driverPhone = intent.getStringExtra("driver_phone");
        }
        if (driverName == null) driverName = AuthPrefs.name(this);
        if (driverUsername == null) driverUsername = AuthPrefs.username(this);
        if (driverPhone == null) driverPhone = AuthPrefs.phone(this);
        return START_STICKY;
    }

    // Heartbeat every 30s
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            writeHeartbeat();
            if (TriplePing.isRunning() && isAppInForeground()) {
                TriplePing.stop(getApplicationContext());
            }
            handler.postDelayed(this, PERIOD_MS);
        }
    };

    // Poller: guarantees a fix every 30s
    private final Runnable locationPoller = new Runnable() {
        @Override
        public void run() {
            tryPollCurrentLocation();
            if (handler != null) handler.postDelayed(this, PERIOD_MS);
        }
    };

    // Watchdog: if stream stalls >45s, one-shot fix and restart FLP
    private final Runnable locWatchdog = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (lastLocMs == 0L || (now - lastLocMs) > WATCHDOG_MS) {
                Log.w(TAG, "Watchdog fired; restarting FLP + one-shot fix");
                tryForceOneShotFix();
                tryRestartLocationUpdatesIfStale();
            }
            if (handler != null) handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            NotificationChannel fg = new NotificationChannel(
                    CH_FG, "On-Duty Tracking", NotificationManager.IMPORTANCE_LOW);
            fg.setDescription("Driver presence and location tracking");
            nm.createNotificationChannel(fg);

            NotificationChannel alerts = new NotificationChannel(
                    CH_ALERTS, "New Assignment Alerts", NotificationManager.IMPORTANCE_HIGH);
            alerts.setDescription("Heads-up alerts for new assignments (custom sound)");
            alerts.enableLights(true);
            alerts.enableVibration(true);
            alerts.setSound(null, null); // sound handled by Single/TriplePing
            nm.createNotificationChannel(alerts);
        }
    }

    private void startInForeground() {
        Notification n = new NotificationCompat.Builder(this, CH_FG)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("On-duty tracking")
                .setContentText("Listening for new assignments…")
                .setOngoing(true)
                .build();
        startForeground(FG_NOTIF_ID, n);
    }

    private boolean hasPerms() {
        return ActivityCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundPerm() {
        if (Build.VERSION.SDK_INT < 29) return true;
        return ActivityCompat.checkSelfPermission(this, "android.permission.ACCESS_BACKGROUND_LOCATION")
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isDeviceLocationEnabled() {
        try {
            boolean gps = lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean net = lm != null && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (!gps && !net) Log.w(TAG, "Device location providers are OFF");
            return gps || net;
        } catch (Throwable t) {
            Log.w(TAG, "isDeviceLocationEnabled error: " + t.getMessage());
            return false;
        }
    }

    // Continuous streaming request
    private void startLocationUpdates() {
        if (!hasPerms()) {
            Log.w(TAG, "No location permissions; not starting updates");
            return;
        }
        if (!isDeviceLocationEnabled()) {
            Log.w(TAG, "Location OFF; will still try one-shot fixes");
            tryForceOneShotFix();
        }

        LocationRequest req = new LocationRequest.Builder(PERIOD_MS) // 30s
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(PERIOD_MS)
                .setMaxUpdateDelayMillis(0)           // no batching
                .setMinUpdateDistanceMeters(0f)
                .setWaitForAccurateLocation(false)
                .build();

        if (ActivityCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permissions vanished mid-call");
            return;
        }
        Log.d(TAG, "requestLocationUpdates: 30s, maxDelay=0, high accuracy");
        fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());

        // Seed with last known
        fused.getLastLocation().addOnSuccessListener(loc -> {
            if (loc != null) {
                Log.d(TAG, "getLastLocation seed: " + loc.getLatitude() + "," + loc.getLongitude());
                lastLocMs = System.currentTimeMillis();
                writePresence(loc);
                appendDriverRoutePoints(loc);
            } else {
                Log.d(TAG, "getLastLocation: null");
            }
        });
    }

    private void stopLocationUpdates() {
        if (locationCallback != null) fused.removeLocationUpdates(locationCallback);
    }

    private void tryRestartLocationUpdatesIfStale() {
        try {
            stopLocationUpdates();
            startLocationUpdates();
        } catch (Throwable t) {
            Log.w(TAG, "tryRestartLocationUpdatesIfStale error: " + t.getMessage());
        }
    }

    // Polling path (every 30s)
    private void tryPollCurrentLocation() {
        if (!hasPerms()) {
            Log.w(TAG, "poll: no perms");
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        new com.google.android.gms.tasks.CancellationTokenSource().getToken())
                .addOnSuccessListener(loc -> {
                    if (loc != null) {
                        lastLocMs = System.currentTimeMillis();
                        Log.d(TAG, "poll fix: " + loc.getLatitude() + "," + loc.getLongitude());
                        writePresence(loc);
                        appendDriverRoutePoints(loc);
                    } else {
                        Log.d(TAG, "poll fix: null");
                    }
                })
                .addOnFailureListener(e -> Log.w(TAG, "poll getCurrentLocation failed: " + e));
    }

    // Watchdog one-shot
    private void tryForceOneShotFix() {
        if (!hasPerms()) return;
        com.google.android.gms.tasks.CancellationTokenSource cts =
                new com.google.android.gms.tasks.CancellationTokenSource();
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.getToken())
                .addOnSuccessListener(loc -> {
                    if (loc != null) {
                        lastLocMs = System.currentTimeMillis();
                        Log.d(TAG, "watchdog fix " + loc.getLatitude() + "," + loc.getLongitude());
                        writePresence(loc);
                        appendDriverRoutePoints(loc);
                    } else {
                        Log.w(TAG, "watchdog: getCurrentLocation null");
                    }
                })
                .addOnFailureListener(e -> Log.w(TAG, "watchdog getCurrentLocation failed: " + e));
    }

    private @Nullable DocumentReference driverDoc() {
        long driverId = AuthPrefs.driverId(this);
        if (driverId <= 0) {
            Log.w(TAG, "No driverId; skip writes");
            return null;
        }
        return db.collection("drivers").document(String.valueOf(driverId));
    }

    private void writeHeartbeat() {
        DocumentReference doc = driverDoc();
        if (doc == null) return;
        Map<String, Object> beat = new HashMap<>();
        beat.put("lastHeartbeat", FieldValue.serverTimestamp());
        doc.set(beat, SetOptions.merge());
    }

    private void writePresence(Location loc) {
        DocumentReference doc = driverDoc();
        if (doc == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("lat", loc.getLatitude());
        data.put("lng", loc.getLongitude());
        data.put("location", new GeoPoint(loc.getLatitude(), loc.getLongitude()));
        data.put("lastHeartbeat", FieldValue.serverTimestamp());
        data.put("updatedAt", FieldValue.serverTimestamp());
        doc.set(data, SetOptions.merge()).addOnFailureListener(e -> Log.w(TAG, "writePresence failed: " + e.getMessage()));
    }

    private void appendDriverRoutePoints(Location loc) {
        DocumentReference doc = driverDoc();
        if (doc == null) return;
        List<Long> txCopy;
        synchronized (activeTxIds) {
            if (activeTxIds.isEmpty()) return;
            txCopy = new ArrayList<>(activeTxIds);
        }
        Map<String, Object> point = new HashMap<>();
        point.put("ts_ms", System.currentTimeMillis());
        point.put("lat", loc.getLatitude());
        point.put("lng", loc.getLongitude());

        Map<String, Object> updates = new HashMap<>();
        for (long txId : txCopy) {
            updates.put("driver_route." + txId, FieldValue.arrayUnion(point));
        }
        updates.put("route_updated_at", FieldValue.serverTimestamp());
        doc.update(updates).addOnFailureListener(e -> Log.w(TAG, "appendDriverRoutePoints: " + e.getMessage()));
    }

    private void ensureStaticFieldsOnce() {
        DocumentReference doc = driverDoc();
        if (doc == null) return;
        doc.get().addOnSuccessListener(snap -> {
            Map<String, Object> init = new HashMap<>();
            boolean need = false;
            if (!snap.exists() || !snap.contains("transactionsId")) { init.put("transactionsId", null); need = true; }
            if (!snap.exists() || !snap.contains("havingtask")) { init.put("havingtask", false); need = true; }
            if (!snap.exists() || !snap.contains("active_transactions")) { init.put("active_transactions", new ArrayList<>()); need = true; }
            if (need) doc.set(init, SetOptions.merge());
        });
    }

    private void startWatchingDriverDoc() {
        stopWatchingDriverDoc();
        DocumentReference doc = driverDoc();
        if (doc == null) return;
        driverWatcher = doc.addSnapshotListener((snap, err) -> {
            if (err != null || snap == null || !snap.exists()) return;

            // Mirror transactionsId -> local last seen; alert if changed
            Object v = snap.get("transactionsId");
            String newId = (v == null) ? null : String.valueOf(v).trim();
            if (newId != null && !newId.isEmpty()) {
                String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_TXID, null);
                if (last == null || !last.equals(newId)) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_TXID, newId).apply();
                    handleNewAssignment(newId);
                }
            }

            // Track only tx that have a route start stamped
            Object listObj = snap.get("active_transactions");
            Set<Long> latest = new HashSet<>();
            if (listObj instanceof List<?>) {
                for (Object o : (List<?>) listObj) {
                    if (o == null) continue;
                    try { latest.add(Long.parseLong(String.valueOf(o))); } catch (Exception ignore) {}
                }
            }
            Set<Long> started = new HashSet<>();
            for (long txId : latest) {
                if (hasRouteStart(snap, txId)) {
                    started.add(txId);
                    seedDriverRouteArrayIfMissing(txId);
                }
            }
            synchronized (activeTxIds) {
                activeTxIds.clear();
                activeTxIds.addAll(started);
            }
        });
    }

    private void stopWatchingDriverDoc() {
        if (driverWatcher != null) {
            driverWatcher.remove();
            driverWatcher = null;
        }
    }

    private boolean hasRouteStart(@NonNull DocumentSnapshot snap, long txId) {
        try { return snap.get("driver_route_ts." + txId + ".start") != null; }
        catch (Exception e) { return false; }
    }

    private void handleNewAssignment(String txIdStr) {
        long txId = 0L;
        try { txId = Long.parseLong(txIdStr); } catch (Exception ignored) {}
        if (txId <= 0) return;

        showAssignmentNotification(txId);

        if (isAppInForeground()) {
            SinglePing.start(getApplicationContext());
            Intent i = new Intent(this, TaskDialogActivity.class)
                    .putExtra(TaskDialogActivity.EXTRA_TX_ID, txId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } else {
            TriplePing.start(getApplicationContext());
        }
    }

    private boolean isAppInForeground() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                || info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
    }

    private void showAssignmentNotification(long txId) {
        Intent open = new Intent(this, TaskDialogActivity.class)
                .putExtra(TaskDialogActivity.EXTRA_TX_ID, txId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = (Build.VERSION.SDK_INT >= 23)
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent contentPi = PendingIntent.getActivity(this, (int) txId, open, flags);

        Intent del = new Intent(ACTION_STOP_PINGS).setPackage(getPackageName());
        PendingIntent deletePi = PendingIntent.getBroadcast(
                this, (int) (txId % 1000), del,
                (Build.VERSION.SDK_INT >= 23)
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CH_ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("New Assignment #" + txId)
                .setContentText("Tap to view details")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setOnlyAlertOnce(true) // visual only; our sound is manual
                .setAutoCancel(true)
                .setVibrate(new long[]{0, 350, 200, 350})
                .setContentIntent(contentPi)
                .setDeleteIntent(deletePi);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_NEW_ASSIGN_BASE + (int) (txId % 1000), nb.build());
    }

    private void seedDriverRouteArrayIfMissing(long txId) {
        DocumentReference doc = driverDoc();
        if (doc == null || txId <= 0) return;
        Map<String, Object> seed = new HashMap<>();
        seed.put("driver_route." + txId, new ArrayList<>());
        doc.set(seed, SetOptions.merge()).addOnFailureListener(e -> Log.w(TAG, "seedDriverRouteArrayIfMissing: " + e.getMessage()));
    }

    @Override public void onDestroy() {
        super.onDestroy();
        if (handler != null) {
            handler.removeCallbacks(locationPoller);
            handler.removeCallbacks(locWatchdog);
            handler.removeCallbacksAndMessages(null);
        }
        if (authListener != null) auth.removeAuthStateListener(authListener);
        stopWatchingDriverDoc();
        stopLocationUpdates();
        try { unregisterReceiver(stopReceiver); } catch (Exception ignore) {}
        TriplePing.stop(this);
        SinglePing.stop(this);
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    // ======= Audio helpers (unchanged) =======
    private static MediaPlayer buildConfiguredPlayer(Context ctx) throws Exception {
        MediaPlayer p = new MediaPlayer();
        if (Build.VERSION.SDK_INT >= 21) {
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
        } else {
            p.setAudioStreamType(AudioManager.STREAM_NOTIFICATION);
        }
        AssetFileDescriptor afd = ctx.getResources().openRawResourceFd(R.raw.notify_common);
        p.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
        afd.close();
        p.setLooping(false);
        p.setVolume(1f, 1f);
        p.prepare();
        return p;
    }

    private static final class SinglePing {
        private static MediaPlayer mp;
        private static boolean running = false;
        private static Handler h;
        private static AudioManager am;
        private static AudioFocusRequest focusReq;

        static synchronized void start(Context ctx) {
            if (running) return;
            running = true;
            h = new Handler(Looper.getMainLooper());
            am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            try {
                int focusResult;
                if (Build.VERSION.SDK_INT >= 26) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build();
                    focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(attrs).setOnAudioFocusChangeListener(f -> {})
                            .build();
                    focusResult = am.requestAudioFocus(focusReq);
                } else {
                    focusResult = am.requestAudioFocus(null,
                            AudioManager.STREAM_NOTIFICATION, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                }
                if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.w(TAG, "SinglePing: audio focus not granted");
                }
                mp = buildConfiguredPlayer(ctx);
                mp.setOnCompletionListener(p -> stop(ctx));
                mp.start();
                h.postDelayed(() -> stop(ctx), 10_000L);
            } catch (Throwable t) {
                Log.e(TAG, "SinglePing start failed: " + t.getMessage());
                stop(ctx);
            }
        }
        static synchronized void stop(Context ctx) {
            if (!running) return;
            running = false;
            try { if (mp != null) { try { mp.stop(); } catch (Exception ignore) {} mp.release(); } }
            catch (Exception ignore) {}
            finally { mp = null; }
            try { if (h != null) h.removeCallbacksAndMessages(null); } catch (Exception ignore) {}
            if (am != null) {
                try {
                    if (Build.VERSION.SDK_INT >= 26 && focusReq != null) am.abandonAudioFocusRequest(focusReq);
                    else am.abandonAudioFocus(null);
                } catch (Exception ignore) {}
            }
            am = null; focusReq = null; h = null;
        }
    }

    private static final class TriplePing {
        private static MediaPlayer mp;
        private static int plays = 0;
        private static boolean running = false;
        private static AudioManager am;
        private static AudioFocusRequest focusReq;
        private static final long FAILSAFE_STOP_MS = 20_000L;
        private static Handler h;
        static synchronized void start(Context ctx) {
            if (running) return;
            running = true;
            plays = 0;
            h = new Handler(Looper.getMainLooper());

            am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int focusResult;
            if (Build.VERSION.SDK_INT >= 26) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(f -> {})
                        .build();
                focusResult = am.requestAudioFocus(focusReq);
            } else {
                focusResult = am.requestAudioFocus(null,
                        AudioManager.STREAM_NOTIFICATION, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
            if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "TriplePing: audio focus not granted");
            }

            try {
                mp = buildConfiguredPlayer(ctx);
                mp.setOnCompletionListener(p -> {
                    plays++;
                    if (plays < 3 && running) {
                        try { p.seekTo(0); p.start(); }
                        catch (Exception e) { Log.w(TAG, "TriplePing restart failed: " + e.getMessage()); stop(ctx); }
                    } else { stop(ctx); }
                });
                mp.start();
                h.postDelayed(() -> stop(ctx), FAILSAFE_STOP_MS);
            } catch (Throwable t) {
                Log.e(TAG, "TriplePing start failed: " + t.getMessage());
                stop(ctx);
            }
        }

        static synchronized void stop(Context ctx) {
            running = false;
            plays = 0;
            try { if (mp != null) { try { mp.stop(); } catch (Exception ignore) {} mp.release(); } }
            catch (Exception ignore) {}
            finally { mp = null; }
            try { if (h != null) h.removeCallbacksAndMessages(null); } catch (Exception ignore) {}
            if (am != null) {
                try {
                    if (Build.VERSION.SDK_INT >= 26 && focusReq != null) am.abandonAudioFocusRequest(focusReq);
                    else am.abandonAudioFocus(null);
                } catch (Exception ignore) {}
            }
            am = null; focusReq = null; h = null;
        }
        static synchronized boolean isRunning() { return running; }
    }
}

// LocationPingService.java ✅ FULL UPDATED (NO Firestore) + ✅ ABLY BACKGROUND OFFER NOTIFICATION SOUND
// ✅ What this adds:
// 1) Ably listener runs inside this Foreground Service (works when app is background)
// 2) When Ably "assigned/active/auto-allocation" comes -> show Android notification (SMS-like) with SOUND
// 3) Tapping notification opens HomeActivity with txId/secs, and your existing HomeActivity shows popup
//
// NOTE (Android 8+): notification sound comes from the NotificationChannel.
// If you change sound later, uninstall app once OR change channel id (CH_OFFER).
//
// NOTE (Android 13+): if POST_NOTIFICATIONS permission not granted, notification won't show/sound.

package com.example.loginapp;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.loginapp.net.ApiClient;
import com.example.loginapp.net.ApiService;
import com.example.loginapp.net.model.GenericResponse;
import com.example.loginapp.tracking.TaskTripTracker;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class LocationPingService extends Service {

    private static final String TAG = "LocSvc";

    // Actions
    public static final String ACTION_START_ON_DUTY = "com.example.loginapp.action.START_ON_DUTY";
    public static final String ACTION_STOP          = "com.example.loginapp.action.STOP";
    public static final String ACTION_FORCE_LOGOUT  = "com.example.loginapp.action.FORCE_LOGOUT";

    // Channels
    private static final String CH_FG     = "duty_tracking";
    private static final String CH_STATUS = "driver_status_alerts_v2";

    // ✅ Offer notification channel (change id if you change sound!)
    private static final String CH_OFFER  = "offer_channel_v3";

    private static final int FG_NOTIF_ID  = 11001;

    // Prefs
    private static final String PREFS         = "verdi_prefs";
    private static final String KEY_ACTIVE_TX = "active_txid";
    private static final String KEY_ON_DUTY   = "on_duty_toggle_state";

    // Pending offer keys (must match HomeActivity)
    private static final String KEY_PENDING_TX   = "pending_offer_tx";
    private static final String KEY_PENDING_SECS = "pending_offer_secs";
    private static final String KEY_PENDING_AT   = "pending_offer_at";


    // Watchdogs
    private static final long FRESH_FIX_TIMEOUT_MS   = 8_000L;
    private static final long TICK_HEALTH_WINDOW_MS  = 45_000L; // if no tick for 45s while onDuty=true -> restart
    private static final long HEALTH_CHECK_EVERY_MS  = 30_000L;

    private FusedLocationProviderClient fused;
    private LocationCallback locationCallback;

    private volatile Location lastLoc;
    private volatile long lastLocMs = 0L;

    private volatile boolean onDuty = false;

    // ✅ driver polling comes from prefs (default 10s)
    private volatile long apiPeriodMs = 10_000L;

    private ScheduledThreadPoolExecutor apiScheduler;
    private ExecutorService oneOffExec;

    private ApiService api;
    private TaskTripTracker tripTracker;

    private final AtomicBoolean freshFixInFlight = new AtomicBoolean(false);

    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    // ✅ WakeLock to keep CPU alive during lock screen / doze (while onDuty)
    private PowerManager.WakeLock wakeLock;

    // Heartbeat
    private volatile long lastTickAtMs = 0L;

    // ✅ Ably offers in background
    private AblyPushManager ablyPushManager;

    // ---------------------------------------------------------------------------------------------

    private boolean isDriverLoggedIn() {
        return AuthPrefs.isSessionValid(this) && AuthPrefs.driverId(this) > 0;
    }

    private boolean hasPerms() {
        return ActivityCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean readOnDutyPref() {
        return prefs.getBoolean(KEY_ON_DUTY, false);
    }

    private long readActiveTxPref() {
        return prefs.getLong(KEY_ACTIVE_TX, 0L);
    }

    private void writeOnDutyPref(boolean on) {
        prefs.edit().putBoolean(KEY_ON_DUTY, on).apply();
    }

    private boolean isDozing() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isDeviceIdleMode();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasInternet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null) return false;
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        } catch (Throwable t) {
            return true; // don't block if something fails
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        api = ApiClient.get().create(ApiService.class);
        fused = LocationServices.getFusedLocationProviderClient(this);
        tripTracker = new TaskTripTracker(getApplicationContext());

        oneOffExec = Executors.newSingleThreadExecutor();

        ensureChannels();
        ensureOfferChannel(); // ✅ offer notification sound channel
        startInForeground("Starting…");

        // ✅ load driver polling from prefs (set by your SettingsPrefs)
        apiPeriodMs = SettingsPrefs.driverPollingMs(this);
        if (apiPeriodMs <= 0) apiPeriodMs = 10_000L;
        Log.d(TAG, "Loaded driver polling from prefs: " + apiPeriodMs + "ms");

        Log.d(TAG, "onCreate driverId=" + AuthPrefs.driverId(this)
                + " session=" + AuthPrefs.isSessionValid(this));

        if (!isDriverLoggedIn()) {
            Log.w(TAG, "onCreate: no valid driver session, stopping service");
            stopSelf();
            return;
        }

        if (!hasPerms()) {
            Log.w(TAG, "onCreate: no location permission, stopping service");
            stopSelf();
            return;
        }

        buildLocationCallback();
        startLocationUpdates();

        // seed from prefs
        onDuty = readOnDutyPref();
        long tx = readActiveTxPref();
        tripTracker.setActiveTx(tx);

        Log.d(TAG, "onCreate: onDuty(pref)=" + onDuty + " activeTx(pref)=" + tx);

        attachPrefListener();

        if (onDuty) {
            acquireWakeLock();
            startApiPings(true);
            startAblyOffers(); // ✅ start background Ably offers
        } else {
            releaseWakeLock();
            stopAblyOffers();
        }

        Log.d(TAG, "Service started OK (NO Firestore + Ably Offers)");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannels();
        ensureOfferChannel();
        startInForeground("Running…");

        String action = (intent != null) ? intent.getAction() : null;

        // ✅ STOP: only when logout/off-duty or session invalid
        if (!isDriverLoggedIn()) {
            Log.w(TAG, "onStartCommand: no valid session → stopping");
            try { stopForeground(true); } catch (Exception ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!hasPerms()) {
            Log.w(TAG, "onStartCommand: no location perms → stopping");
            try { stopForeground(true); } catch (Exception ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            long interval = intent.getLongExtra("interval_ms", 0L);
            if (interval > 0) {
                apiPeriodMs = interval;
                SettingsPrefs.setDriverPollingMs(this, interval);
                Log.d(TAG, "onStartCommand interval_ms=" + apiPeriodMs);
                restartTimersIfNeeded();
            }
        }

        if (ACTION_STOP.equals(action)) {
            Log.d(TAG, "ACTION_STOP received");
            applyDuty(false, /*writePref=*/true, /*showStatusNotif=*/true);

            // ✅ kill foreground + service
            try { stopForeground(true); } catch (Exception ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START_ON_DUTY.equals(action)) {
            boolean immediate = intent == null || intent.getBooleanExtra("immediate", true);
            Log.d(TAG, "ACTION_START_ON_DUTY received immediate=" + immediate);

            applyDuty(true, /*writePref=*/true, /*showStatusNotif=*/true);
            if (immediate) doOneImmediateTick();

            return START_STICKY;
        }

        // default: follow prefs (system may restart service)
        boolean prefDuty = readOnDutyPref();
        if (prefDuty != onDuty) {
            Log.d(TAG, "onStartCommand: pref duty changed " + onDuty + " -> " + prefDuty);
            applyDuty(prefDuty, /*writePref=*/false, /*showStatusNotif=*/false);
        }

        long tx = readActiveTxPref();
        if (tripTracker != null) tripTracker.setActiveTx(tx);

        return START_STICKY;
    }


    // ✅ If user swipes away app, keep tracking while onDuty=true
    @SuppressLint("ScheduleExactAlarm")
    @Override
    public void onTaskRemoved(Intent rootIntent) {

        super.onTaskRemoved(rootIntent);

        boolean duty = readOnDutyPref();
        Log.w(TAG, "onTaskRemoved dutyPref=" + duty);

        if (!duty) return;

        Intent i = new Intent(getApplicationContext(), LocationPingService.class);
        i.setAction(ACTION_START_ON_DUTY);
        i.putExtra("immediate", true);

        PendingIntent pi = PendingIntent.getService(
                getApplicationContext(),
                2001,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) {
                long t = System.currentTimeMillis() + 1500; // 1.5s
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, t, pi);
                } else {
                    am.setExact(android.app.AlarmManager.RTC_WAKEUP, t, pi);
                }
                Log.w(TAG, "onTaskRemoved: scheduled restart via AlarmManager");
            }
        } catch (Throwable e) {
            Log.w(TAG, "onTaskRemoved alarm restart failed: " + e.getMessage());
        }
    }


    // ---------------------------------------------------------------------------------------------
    // Pref listener
    // ---------------------------------------------------------------------------------------------

    private void attachPrefListener() {
        if (prefListener != null) return;

        prefListener = (sp, key) -> {
            if (key == null) return;

            if (KEY_ON_DUTY.equals(key)) {
                boolean pref = readOnDutyPref();
                Log.w(TAG, "prefListener KEY_ON_DUTY -> " + pref);
                if (pref != onDuty) {
                    applyDuty(pref, /*writePref=*/false, /*showStatusNotif=*/true);
                }
                return;
            }

            if (KEY_ACTIVE_TX.equals(key)) {
                long tx = readActiveTxPref();
                Log.d(TAG, "prefListener: activeTx changed -> " + tx);
                if (tripTracker != null) tripTracker.setActiveTx(tx);
                return;
            }

            // ✅ driver polling updated from prefs
            if (SettingsPrefs.keyDriverPollingMs().equals(key)) {
                long newMs = SettingsPrefs.driverPollingMs(LocationPingService.this);
                if (newMs > 0 && newMs != apiPeriodMs) {
                    Log.d(TAG, "prefListener: driver_polling_ms changed -> " + newMs);
                    apiPeriodMs = newMs;
                    restartTimersIfNeeded();
                }
            }
        };

        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    private void detachPrefListener() {
        if (prefListener == null) return;
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefListener); } catch (Exception ignored) {}
        prefListener = null;
    }

    // ---------------------------------------------------------------------------------------------
    // WakeLock
    // ---------------------------------------------------------------------------------------------

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) return;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;

            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":LocSvcWl");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(); // no timeout

            Log.d(TAG, "WakeLock acquired");
        } catch (Throwable t) {
            Log.w(TAG, "WakeLock acquire failed: " + t.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "WakeLock released");
            }
        } catch (Throwable ignored) {}
        wakeLock = null;
    }

    // ---------------------------------------------------------------------------------------------
    // Duty apply
    // ---------------------------------------------------------------------------------------------

    private void applyDuty(boolean duty, boolean writePref, boolean showStatusNotif) {
        boolean changed = (onDuty != duty);
        onDuty = duty;

        if (writePref) writeOnDutyPref(duty);

        if (duty) {
            acquireWakeLock();
            startApiPings(true);
            startAblyOffers();
        } else {
            stopApiPings();
            releaseWakeLock();
            stopAblyOffers();
        }

        if (tripTracker != null) {
            long tx = readActiveTxPref();
            tripTracker.setActiveTx(duty ? tx : 0L);
        }

        // ✅ Always show status notification when requested (ON and OFF)
        if (showStatusNotif) {
            showStatusNotif(onDuty ? "You are ON duty" : "You are OFF duty");
        }
    }


    // ---------------------------------------------------------------------------------------------
    // Notifications / Channels
    // ---------------------------------------------------------------------------------------------

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel fg = new NotificationChannel(
                    CH_FG, "On-Duty Tracking", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(fg);

            NotificationChannel status = new NotificationChannel(
                    CH_STATUS, "Driver Online/Offline", NotificationManager.IMPORTANCE_HIGH);
            status.enableLights(true);
            status.enableVibration(true);

            try {
                Uri soundUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.msg);
                status.setSound(soundUri, null);
            } catch (Throwable ignore) {}

            nm.createNotificationChannel(status);
        }
    }

    // ✅ offer channel with sound (Android 8+)
    private void ensureOfferChannel() {
        if (Build.VERSION.SDK_INT < 26) return;

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        Uri soundUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.notify_common);

        android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel ch = new NotificationChannel(
                CH_OFFER,
                "Task Offers",
                NotificationManager.IMPORTANCE_HIGH
        );
        ch.enableVibration(true);
        ch.setSound(soundUri, attrs);

        nm.createNotificationChannel(ch);
    }

    private PendingIntent openHomePendingIntent() {
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = (Build.VERSION.SDK_INT >= 23) ? PendingIntent.FLAG_IMMUTABLE : 0;
        return PendingIntent.getActivity(this, 9001, i, flags);
    }

    private void startInForeground(String text) {
        Notification n = new NotificationCompat.Builder(this, CH_FG)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("On-duty tracking")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openHomePendingIntent())
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FG_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(FG_NOTIF_ID, n);
        }
    }

    private void updateForegroundText(String text) {
        Notification n = new NotificationCompat.Builder(this, CH_FG)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("On-duty tracking")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openHomePendingIntent())
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(FG_NOTIF_ID, n);
    }

    private void showStatusNotif(String msg) {
        Notification n = new NotificationCompat.Builder(this, CH_STATUS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Driver status")
                .setContentText(msg)
                .setAutoCancel(true)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify((int) System.currentTimeMillis(), n);
    }

    // ✅ SMS-like offer notification (sound in channel)
    private void showOfferNotification(long txId, int secs) {
        // For Android < 8
        Uri soundUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.notify_common);

        Intent open = new Intent(this, HomeActivity.class);
        open.putExtra(HomeActivity.EXTRA_TX_ID, txId);
        open.putExtra(HomeActivity.EXTRA_SECS, secs);

        // ✅ make pendingintent unique to avoid reuse
        open.setData(Uri.parse("verdi://open_offer/" + txId + "/" + System.currentTimeMillis()));
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(
                this,
                (int) (txId % Integer.MAX_VALUE),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_OFFER)
                .setSmallIcon(android.R.drawable.ic_dialog_email) // ✅ replace with your notification icon if you have one
                .setContentTitle("New Task Assigned")
                .setContentText("Task #" + txId)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pi);

        if (Build.VERSION.SDK_INT < 26) {
            b.setSound(soundUri);
            b.setVibrate(new long[]{0, 250, 150, 250});
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        NotificationManagerCompat.from(this).notify((int) (txId % Integer.MAX_VALUE), b.build());
    }

    // ---------------------------------------------------------------------------------------------
    // ✅ Ably Offers in Service (works in background)
    // ---------------------------------------------------------------------------------------------

    private void startAblyOffers() {
        if (ablyPushManager != null) return;

        ablyPushManager = new AblyPushManager(getApplicationContext());

        ablyPushManager.start(event -> {
            try {
                if (event == null) return;

                long txId = event.txId;
                int secs  = (event.secs > 0) ? event.secs : 0;
                String type = (event.type != null) ? event.type.trim().toLowerCase() : "";

                if (txId <= 0) return;

                // ✅ ALWAYS broadcast revoke to TaskDetail (even if onDuty=false)
                if ("cancelled".equals(type) || "unassigned".equals(type)) {

                    Intent revoke = new Intent(Actions.TASK_REVOKED);
                    revoke.putExtra(Actions.EXTRA_TX_ID, txId);
                    revoke.putExtra(Actions.EXTRA_REASON, type);

                    LocalBroadcastManager
                            .getInstance(getApplicationContext())
                            .sendBroadcast(revoke);

                    Log.e("REVOKE", "sent TASK_REVOKED tx=" + txId + " type=" + type);
                    return;
                }

                // ✅ Only show offers when on duty
                if (!onDuty) return;

                if (type.equals("assigned") || type.equals("active") || type.equals("auto-allocation")) {

                    HomeActivity.persistOfferToPrefs(getApplicationContext(), txId, secs);

                    SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                    sp.edit()
                            .putLong(KEY_PENDING_TX, txId)
                            .putInt(KEY_PENDING_SECS, secs)
                            .putLong(KEY_PENDING_AT, System.currentTimeMillis())
                            .apply();

                    showOfferNotification(txId, secs);
                }

            } catch (Throwable t) {
                Log.w(TAG, "Ably offer handler error: " + t.getMessage());
            }
        });

    }

    private void stopAblyOffers() {
        if (ablyPushManager != null) {
            try { ablyPushManager.stop(); } catch (Exception ignored) {}
            ablyPushManager = null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Location Updates
    // ---------------------------------------------------------------------------------------------

    private void buildLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc;
                if (result.getLocations() != null && !result.getLocations().isEmpty()) {
                    loc = result.getLocations().get(result.getLocations().size() - 1);
                } else {
                    loc = result.getLastLocation();
                }
                if (loc == null) return;

                lastLoc = loc;
                lastLocMs = System.currentTimeMillis();

                Log.d(TAG, "callback loc: " + loc.getLatitude() + "," + loc.getLongitude()
                        + " acc=" + loc.getAccuracy()
                        + " provider=" + loc.getProvider()
                        + " t=" + lastLocMs);

                updateForegroundText("Last fix: " + (System.currentTimeMillis() - lastLocMs) + "ms ago"
                        + " | poll=" + (apiPeriodMs / 1000) + "s"
                        + (isDozing() ? " | DOZE" : ""));

                if (onDuty && tripTracker != null) {
                    long activeTx = readActiveTxPref();
                    tripTracker.setActiveTx(activeTx);
                    tripTracker.onLocation(loc);
                }
            }
        };
    }

    private void startLocationUpdates() {
        if (!hasPerms()) return;

        try {
            long minInterval = Math.min(5_000L, apiPeriodMs);

            LocationRequest req = new LocationRequest.Builder(apiPeriodMs)
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(minInterval)
                    .setMinUpdateDistanceMeters(0f)
                    .setMaxUpdateDelayMillis(0)
                    .setWaitForAccurateLocation(false)
                    .build();

            fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
            Log.d(TAG, "requestLocationUpdates: HIGH_ACCURACY period=" + apiPeriodMs);

            fused.getLastLocation().addOnSuccessListener(loc -> {
                if (loc != null) {
                    lastLoc = loc;
                    lastLocMs = System.currentTimeMillis();
                    Log.d(TAG, "initial lastLocation: " + loc.getLatitude() + "," + loc.getLongitude());
                } else {
                    Log.d(TAG, "initial lastLocation: null");
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "startLocationUpdates error: " + e.getMessage(), e);
        }
    }

    private void stopLocationUpdates() {
        try {
            if (locationCallback != null && fused != null) fused.removeLocationUpdates(locationCallback);
        } catch (Exception ignore) {}
    }

    private void restartTimersIfNeeded() {
        Log.d(TAG, "restartTimersIfNeeded() new poll=" + apiPeriodMs + "ms");
        stopLocationUpdates();
        startLocationUpdates();

        if (onDuty) {
            stopApiPings();
            startApiPings(true);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // API Ping Scheduler (RESILIENT)
    // ---------------------------------------------------------------------------------------------

    private synchronized void startApiPings(boolean immediate) {
        if (apiScheduler != null && !apiScheduler.isShutdown() && !apiScheduler.isTerminated()) {
            if (immediate) doOneImmediateTick();
            return;
        }

        Log.d(TAG, "Starting API pings every " + (apiPeriodMs / 1000) + "s (immediate=" + immediate + ")");

        apiScheduler = new ScheduledThreadPoolExecutor(1);
        apiScheduler.setRemoveOnCancelPolicy(true);

        long initialDelay = immediate ? 0L : apiPeriodMs;

        apiScheduler.scheduleWithFixedDelay(() -> {
            try {
                tickOnceSafe();
            } catch (Throwable t) {
                Log.e(TAG, "tick crashed: " + t.getMessage(), t);
            }
        }, initialDelay, apiPeriodMs, TimeUnit.MILLISECONDS);

        apiScheduler.scheduleWithFixedDelay(() -> {
            if (!onDuty) return;
            boolean dead = apiScheduler == null || apiScheduler.isShutdown() || apiScheduler.isTerminated();
            if (dead) {
                Log.e(TAG, "Scheduler DEAD while onDuty=true → restarting");
                stopApiPings();
                startApiPings(true);
            }
        }, 30_000, 30_000, TimeUnit.MILLISECONDS);

        apiScheduler.scheduleWithFixedDelay(() -> {
            if (!onDuty) return;

            long now = System.currentTimeMillis();
            long age = (lastTickAtMs == 0) ? Long.MAX_VALUE : (now - lastTickAtMs);

            if (age > TICK_HEALTH_WINDOW_MS) {
                Log.e(TAG, "NO TICK for " + age + "ms while onDuty=true → restarting service");
                restartSelfNow();
            }
        }, HEALTH_CHECK_EVERY_MS, HEALTH_CHECK_EVERY_MS, TimeUnit.MILLISECONDS);
    }

    private void restartSelfNow() {
        try {
            Intent i = new Intent(getApplicationContext(), LocationPingService.class);
            i.setAction(ACTION_START_ON_DUTY);
            i.putExtra("immediate", true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);

            stopSelf();
        } catch (Throwable t) {
            Log.w(TAG, "restartSelfNow failed: " + t.getMessage());
        }
    }

    private void doOneImmediateTick() {
        if (oneOffExec == null) oneOffExec = Executors.newSingleThreadExecutor();
        oneOffExec.execute(() -> {
            try { tickOnceSafe(); } catch (Throwable t) { Log.w(TAG, "oneOff tick error: " + t.getMessage()); }
        });
    }

    private void tickOnceSafe() {
        lastTickAtMs = System.currentTimeMillis();
        Log.e(TAG, "TICK_HEARTBEAT duty=" + onDuty + " now=" + lastTickAtMs
                + " doze=" + isDozing()
                + " internet=" + hasInternet()
                + " poll=" + (apiPeriodMs / 1000) + "s");

        try {
            if (!onDuty) {
                Log.d(TAG, "TICK (onDuty=false) skip");
                return;
            }

            if (!hasPerms()) {
                Log.w(TAG, "TICK: no location perms");
                return;
            }

            long now = System.currentTimeMillis();
            long age = (lastLocMs == 0) ? -1 : (now - lastLocMs);

            Log.d(TAG, "TICK onDuty=true lastLoc=" + (lastLoc != null)
                    + " ageMs=" + age + " inflight=" + freshFixInFlight.get()
                    + " poll=" + (apiPeriodMs / 1000) + "s");

            Log.e(TAG, "DEBUG duty=" + onDuty
                    + " driverId=" + AuthPrefs.driverId(this)
                    + " bearerEmpty=" + (AuthPrefs.bearer(this) == null || AuthPrefs.bearer(this).trim().isEmpty())
                    + " hasInternet=" + hasInternet()
                    + " lastLoc=" + (lastLoc != null)
                    + " lastLocAgeMs=" + (lastLocMs == 0 ? -1 : (System.currentTimeMillis() - lastLocMs)));


            Location cached = lastLoc;

            if (cached != null) {
                sendToApi(cached.getLatitude(), cached.getLongitude());

                if (tripTracker != null) {
                    long activeTx = readActiveTxPref();
                    tripTracker.setActiveTx(activeTx);
                    tripTracker.onLocation(cached);
                }
            } else {
                Log.w(TAG, "TICK: lastLoc is null (no cached fix yet)");
            }

            requestFreshFixWithTimeout();

        } catch (Throwable t) {

            Log.w(TAG, "tick loop error: " + t.getMessage(), t);
        }
    }

    private void requestFreshFixWithTimeout() {
        if (!hasPerms()) return;

        if (!freshFixInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "freshFix: already in-flight");
            return;
        }

        Handler h = new Handler(Looper.getMainLooper());
        Runnable watchdog = () -> {
            if (freshFixInFlight.get()) {
                Log.w(TAG, "freshFix watchdog: forcing inflight=false (callback stuck)");
                freshFixInFlight.set(false);
            }
        };
        h.postDelayed(watchdog, FRESH_FIX_TIMEOUT_MS);

        try {
            CancellationTokenSource cts = new CancellationTokenSource();

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                    .addOnSuccessListener(loc -> {
                        h.removeCallbacks(watchdog);
                        freshFixInFlight.set(false);

                        if (loc == null) {
                            Log.w(TAG, "freshFix: getCurrentLocation returned NULL");
                            return;
                        }

                        lastLoc = loc;
                        lastLocMs = System.currentTimeMillis();

                        Log.d(TAG, "freshFix NEW loc: " + loc.getLatitude() + "," + loc.getLongitude()
                                + " acc=" + loc.getAccuracy()
                                + " doze=" + isDozing());

                        if (onDuty) sendToApi(loc.getLatitude(), loc.getLongitude());

                        if (onDuty && tripTracker != null) {
                            long activeTx = readActiveTxPref();
                            tripTracker.setActiveTx(activeTx);
                            tripTracker.onLocation(loc);
                        }
                    })
                    .addOnFailureListener(e -> {
                        h.removeCallbacks(watchdog);
                        freshFixInFlight.set(false);
                        Log.w(TAG, "freshFix failed: " + e);
                    });

        } catch (Throwable t) {
            h.removeCallbacks(watchdog);
            freshFixInFlight.set(false);
            Log.w(TAG, "freshFix exception: " + t.getMessage());
        }
    }

    private synchronized void stopApiPings() {
        if (apiScheduler == null) return;
        Log.d(TAG, "Stopping API pings");
        try {
            apiScheduler.shutdownNow();
        } catch (Throwable ignored) {}
        apiScheduler = null;
    }

    // ---------------------------------------------------------------------------------------------
    // Logout / 401 auto logout
    // ---------------------------------------------------------------------------------------------

    private final AtomicBoolean logoutInProgress = new AtomicBoolean(false);

    private void autoLogout(String reason) {
        if (!logoutInProgress.compareAndSet(false, true)) return;

        Log.e(TAG, "AUTO_LOGOUT: " + reason);

        applyDuty(false, /*writePref=*/true, /*showStatusNotif=*/true);

        AuthPrefs.clearLogin(this);

        try {
            Intent i = new Intent(ACTION_FORCE_LOGOUT);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Throwable t) {
            Log.w(TAG, "logout broadcast failed: " + t.getMessage());
        }

        stopSelf();
    }

    // ---------------------------------------------------------------------------------------------
    // API Call
    // ---------------------------------------------------------------------------------------------

    private void sendToApi(double lat, double lng) {
        if (!onDuty) return;

        long driverId = AuthPrefs.driverId(this);
        String bearer = AuthPrefs.bearer(this);

        Log.d(TAG, "sendToApi driverId=" + driverId + " lat=" + lat + " lng=" + lng
                + " internet=" + hasInternet()
                + " doze=" + isDozing());

        if (driverId <= 0) {
            Log.w(TAG, "sendToApi skipped: driverId is 0");
            return;
        }
        if (bearer == null || bearer.trim().isEmpty()) {
            Log.w(TAG, "sendToApi skipped: bearer token missing");
            return;
        }
        if (!hasInternet()) {
            Log.w(TAG, "sendToApi skipped: NO INTERNET");
            return;
        }

        RequestBody did = RequestBody.create(String.valueOf(driverId), MediaType.parse("text/plain"));
        RequestBody la  = RequestBody.create(String.valueOf(lat), MediaType.parse("text/plain"));
        RequestBody lo  = RequestBody.create(String.valueOf(lng), MediaType.parse("text/plain"));

        api.saveDriverLocation(bearer, did, la, lo).enqueue(new Callback<GenericResponse>() {
            @Override
            public void onResponse(@NonNull Call<GenericResponse> call,
                                   @NonNull Response<GenericResponse> response) {

                int code = response.code();
                Log.d(TAG, "save_driver_location code=" + code + " ok=" + response.isSuccessful());

                if (code == 401) {
                    autoLogout("save_driver_location returned 401");
                }
            }

            @Override
            public void onFailure(@NonNull Call<GenericResponse> call, @NonNull Throwable t) {
                Log.w(TAG, "save_driver_location FAIL: " + t.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Cleanup
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onDestroy() {
        super.onDestroy();
        detachPrefListener();
        stopAblyOffers();
        stopApiPings();
        stopLocationUpdates();
        releaseWakeLock();

        try {
            if (oneOffExec != null) {
                oneOffExec.shutdownNow();
                oneOffExec = null;
            }
        } catch (Exception ignored) {}

        Log.d(TAG, "onDestroy");
    }



    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

// HomeActivity.java  ✅ FULL (ABLY + API + PREFS only)  ✅ FIXED: address shows even on http 500 (fallback)
// package: com.example.loginapp

package com.example.loginapp;

import static android.content.Context.MODE_PRIVATE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.loginapp.net.ApiClient;
import com.example.loginapp.net.ApiService;
import com.example.loginapp.net.model.GenericResponse;
import com.example.loginapp.net.model.TaskDetailsResponse;
import com.example.loginapp.net.model.TaskPhase;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.Priority;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.Task;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Set;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import android.view.Menu;


public class HomeActivity extends BaseActivity {

    private static final int GROUP_TASK_DYNAMIC = 6000;
    private static final int ITEM_HEADER = 6001;
    private int dynamicIndex = 6100;


    private static final String KEY_SUPPRESS_ADMIN_TX = "suppress_admin_tx";
    private static final String KEY_SUPPRESS_ADMIN_AT = "suppress_admin_at";
    private static final long SUPPRESS_ADMIN_TTL_MS = 2 * 60 * 1000L; // 2 min

    private static final String CH_ADMIN = "verdi_admin_channel_v2";

    private static final int NOTIF_ADMIN_ID = 9201;


    private static final String TAG = "HomeActivity";

    private AblySettingsManager ablySettingsManager;
    private volatile boolean homeResumed = false;

    // ===== Permissions / Requests =====
    private static final int REQ_PERMS = 2001;      // location + notifications
    private static final int REQ_RESOLVE_LOC = 7001;
    private static final int REQ_LOC = 2001;

    // ✅ Pending offer cache (so popup works after minimize/background)
    private static final String KEY_PENDING_TX   = "pending_offer_tx";
    private static final String KEY_PENDING_SECS = "pending_offer_secs";
    private static final String KEY_PENDING_AT   = "pending_offer_at";
    private static final long   PENDING_TTL_MS   = 2 * 60 * 1000L; // 2 minutes

    // ===== Prefs =====
    private static final String PREFS = "verdi_prefs";
    private static final String KEY_ACTIVE_TX = "active_txid";
    private static final String KEY_ASSIGNED_TX_IDS = "assigned_tx_ids_set";
    private static final String KEY_ACCEPTED_TX_IDS = "accepted_tx_ids_set";
    private static final String KEY_ON_DUTY = "on_duty_toggle_state";

    // ===== Broadcast =====
    private static final String ACTION_TASK_PHASE = "com.example.loginapp.ACTION_TASK_PHASE";

    // ===== From FCM =====
    public static final String EXTRA_TX_ID = "tx_id";
    public static final String EXTRA_SECS  = "secs";

    // ===== UI =====
    private DrawerLayout drawerLayout;
    private NavigationView navView;
    private Switch switchDuty;
    private RecyclerView rvHomeTasks;
    private View emptyState;
    private RecyclerView.Adapter<?> homeAdapter;
    private TextView tvDate;

    // ===== API =====
    private ApiService api;

    // ===== Task list =====
    private final List<TaskItem> acceptedTasks = new ArrayList<>();
    final Map<Long, Phase> phaseMap = new HashMap<>();

    // ===== Locks =====
    private volatile boolean hasTaskLock = false;
    private volatile boolean logoutInProgress = false;

    // ===== Ably =====
    private AblyPushManager ablyPushManager;
    private long lastAblyTxId = 0;
    private long lastAblyAtMs = 0;

    // ===== Popup UI (FCM only) =====
    @Nullable private BottomSheetDialog fcmPopupDialog = null;
    @Nullable private Button fcmBtnDismiss = null;
    @Nullable private Button fcmBtnAccept = null;
    private long fcmCurrentTxId = 0L;
    private int  fcmCurrentSecs = 0;
    private CountDownTimer fcmPopupTimer;

    // ===== Receivers =====
    private boolean txReceiverRegistered = false;
    private boolean lbmReceiversRegistered = false;

    public enum Phase {
        PICKUP_STARTED, PICKUP_ARRIVED, PICKUP_COMPLETED, DELIVERY_ARRIVED, DELIVERY_COMPLETED
    }

    // =============================================================================================
    // Models
    // =============================================================================================

    static class TaskItem {
        final long serverId; // can be fake displayId if API rowId missing
        final long transactionId;
        final String title, address, phoneE164;
        final double lat, lng;
        final boolean isPickup;
        final long pickupId, deliveryId; // real ids (can be 0)
        final String paymentType, orderAmount, orderId;

        TaskItem(long serverId, long transactionId, String title, String address, String phoneE164,
                 double lat, double lng, boolean isPickup, long pickupId, long deliveryId,
                 String paymentType, String orderAmount, String orderId) {
            this.serverId = serverId;
            this.transactionId = transactionId;
            this.title = title;
            this.address = address;
            this.phoneE164 = phoneE164;
            this.lat = lat;
            this.lng = lng;
            this.isPickup = isPickup;
            this.pickupId = pickupId;
            this.deliveryId = deliveryId;
            this.paymentType = paymentType;
            this.orderAmount = orderAmount;
            this.orderId = orderId;
        }
    }

    // =============================================================================================
    // Lifecycle
    // =============================================================================================
    private AblyRemoveTaskManager removeTaskManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        drawerLayout = findViewById(R.id.drawerLayout);
        navView = findViewById(R.id.navigationView);
        tvDate = findViewById(R.id.tvDate);

        ImageView ivMenu = findViewById(R.id.ivMenu);
        if (ivMenu != null) ivMenu.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        ImageView ivClose = findViewById(R.id.ivClose);
        if (ivClose != null) ivClose.setOnClickListener(v -> drawerLayout.closeDrawer(GravityCompat.START));

        if (navView != null) navView.setNavigationItemSelectedListener(this::onNavItemSelected);

        // Header title
        TextView tvDrawerTitle = findViewById(R.id.tvDrawerTitle);
        String name = AuthPrefs.name(this);
        if (tvDrawerTitle != null) {
            if (name != null && !name.trim().isEmpty()) tvDrawerTitle.setText("VERDI - " + name.trim());
            else tvDrawerTitle.setText("VERDI - Driver");
        }

        updateDateText();

        rvHomeTasks = findViewById(R.id.rvHomeTasks);
        emptyState  = findViewById(R.id.emptyState);

        if (rvHomeTasks != null) {
            rvHomeTasks.setLayoutManager(new LinearLayoutManager(this));
            homeAdapter = new CombinedTasksAdapter(this, acceptedTasks, this::onStartCombinedClicked);
            rvHomeTasks.setAdapter(homeAdapter);
        }

        api = ApiClient.get().create(ApiService.class);

        switchDuty = findViewById(R.id.switchDuty);
        if (switchDuty != null) {
            boolean wasOn = getLocalOnDuty();
            switchDuty.setOnCheckedChangeListener(null);
            switchDuty.setChecked(wasOn);
            switchDuty.setOnCheckedChangeListener(dutyToggleListener);
        }

        updateHomeListVisibility();

        // ✅ Handles notification extras on cold start
        handleOfferIntent(getIntent());
        refreshHomeFromPrefs();

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1234);
        }

        // ✅ if you still use deep push intent parse
        handlePushIntent(getIntent());

        if (ablySettingsManager == null) {
            ablySettingsManager = new AblySettingsManager(getApplicationContext());
            ablySettingsManager.start();
        }

        removeTaskManager = new AblyRemoveTaskManager(getApplicationContext());
        removeTaskManager.start();

        // ✅ APP UPDATE CHECK
        UpdateManager.checkForUpdate(this);
    }

    @Override protected void onStart() {
        super.onStart();
        startAblyListener();
        registerHomeLbmReceiversIfNeeded();

        LocalBroadcastManager.getInstance(this)
                .registerReceiver(removeTaskReceiver,
                        new IntentFilter(AblyRemoveTaskManager.ACTION_TASK_REMOVED));
    }

    @Override protected void onStop() {
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(removeTaskReceiver); }
        catch (Exception ignored) {}

        unregisterHomeLbmReceivers();
        super.onStop();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();
        homeResumed = true;

        updateDateText();

        // ✅ Load cards from SharedPreferences immediately
        refreshHomeFromPrefs();

        // ✅ Register TX accepted receiver (system)
        if (!txReceiverRegistered) {
            IntentFilter f = new IntentFilter(TaskPopup.ACTION_TX_ACCEPTED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(txAcceptedReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(txAcceptedReceiver, f);
            txReceiverRegistered = true;
        }

        // ✅ Register phase receiver (system)
        IntentFilter p = new IntentFilter(ACTION_TASK_PHASE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(phaseReceiver, p, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(phaseReceiver, p);

        // ✅ restore duty switch state
        boolean on = getLocalOnDuty();
        if (switchDuty != null) {
            switchDuty.setOnCheckedChangeListener(null);
            if (switchDuty.isChecked() != on) switchDuty.setChecked(on);
            switchDuty.setOnCheckedChangeListener(dutyToggleListener);
        }

        // ✅ Refresh again after receivers are ready
        refreshHomeFromPrefs();
        updateUiLocks();

        // ✅ IMPORTANT: force timer ticking if there is any assigned offer
        if (!getAssignedTxIdsLocal().isEmpty()) {
            startOfferTicker();
            if (homeAdapter != null) homeAdapter.notifyDataSetChanged();
        }

// ✅ then show pending popup if saved
//        consumePendingOfferIfAny();
        // ✅ Small delayed refresh (sometimes API fills addresses after 500-600ms)
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> {
                    refreshHomeFromPrefs();

                    // ensure ticker still running
                    if (!getAssignedTxIdsLocal().isEmpty()) {
                        startOfferTicker();
                        if (homeAdapter != null) homeAdapter.notifyDataSetChanged();
                    }
                }, 600);
    }
    @Override protected void onPause() {
        homeResumed = false;
        super.onPause();

        if (txReceiverRegistered) {
            try { unregisterReceiver(txAcceptedReceiver); } catch (Exception ignored) {}
            txReceiverRegistered = false;
        }
        try { unregisterReceiver(phaseReceiver); } catch (Exception ignored) {}
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(fcmOfferReceiver); } catch (Exception ignored) {}
    }

    private final BroadcastReceiver removeTaskReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            long tx = i.getLongExtra(AblyRemoveTaskManager.EXTRA_TX_ID, 0L);
            if (tx <= 0) return;

            removeAssignedTxLocal(tx);
            removeAcceptedTxLocal(tx);
            removeTransactionEverywhere(tx);
            dismissPopupIfShowing(tx);
        }
    };

    private void dismissPopupIfShowing(long txId) {
        try {
            if (fcmPopupDialog != null && fcmPopupDialog.isShowing() && fcmCurrentTxId == txId) {
                fcmPopupDialog.dismiss();
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // ✅ read txId+secs from notification tap extras and save to prefs
        handleOfferIntent(intent);

        // ✅ refresh home cards immediately
        refreshHomeFromPrefs();

        // ✅ force timer ticking if any assigned offer exists
        if (!getAssignedTxIdsLocal().isEmpty()) {
            startOfferTicker();
            if (homeAdapter != null) homeAdapter.notifyDataSetChanged();
        }

        // ✅ optional: show popup if it was saved
        //        consumePendingOfferIfAny();
    }
    // =============================================================================================
    // ✅ FIXED: Bearer helper
    // =============================================================================================
    @Nullable
    private String bearerHeader() {
        String asIs = AuthPrefs.bearer(this);
        if (asIs != null && !asIs.trim().isEmpty()) return asIs;

        String raw = AuthPrefs.token(this);
        if (raw != null && !raw.trim().isEmpty()) return "Bearer " + raw;

        return null;
    }

    // =============================================================================================
    // Offer intent (notification tap)
    // =============================================================================================
    private static final String KEY_LAST_OFFER_URI = "last_offer_uri";

    private void handleOfferIntent(Intent intent) {
        if (intent == null) return;

        // ✅ 0) DEDUPE: don't re-handle the same notification intent on every onResume()
        String uri = intent.getDataString();   // from MyFcmService: verdi://open_offer/...
        if (uri != null && !uri.isEmpty()) {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            String last = sp.getString(KEY_LAST_OFFER_URI, null);
            if (uri.equals(last)) {
                Log.d(TAG, "handleOfferIntent skipped (already handled) uri=" + uri);
                return;
            }
            sp.edit().putString(KEY_LAST_OFFER_URI, uri).apply();
        }

        long txId = 0;
        int secs = 0;

        // 1) Best case: direct extras
        if (intent.hasExtra(EXTRA_TX_ID)) {
            txId = intent.getLongExtra(EXTRA_TX_ID, 0);
            secs = intent.getIntExtra(EXTRA_SECS, 0);
        }

        // 2) Fallback: transaction_id as String or long
        if (txId <= 0 && intent.hasExtra("transaction_id")) {
            try {
                Object v = intent.getExtras() != null ? intent.getExtras().get("transaction_id") : null;
                if (v instanceof String) txId = Long.parseLong((String) v);
                else if (v instanceof Long) txId = (Long) v;
                else if (v instanceof Integer) txId = ((Integer) v).longValue();
            } catch (Exception ignored) {}
        }

        // 3) Fallback: tx_payload or body (like "24,10" or "#24")
        if (txId <= 0) {
            String raw = intent.getStringExtra("tx_payload");
            if (raw == null) raw = intent.getStringExtra("body");
            if (raw == null) raw = intent.getStringExtra("raw_body");
            if (raw != null) {
                TxPayload p = parseTxPayload(raw);
                if (p != null) {
                    txId = p.txId;
                    if (secs <= 0) secs = p.secs;
                }
            }
        }

        // 4) Final safety
        if (txId <= 0) return;
        // If secs not provided, treat as "no expiry"
        if (secs <= 0) secs = 0;
        // ✅ Save to prefs so card shows
        persistOfferToPrefs(getApplicationContext(), txId, secs);

        addAssignedTxLocal(txId);
        if (secs > 0) {
            saveOfferTimer(txId, secs);
            savePendingOffer(txId, secs);
        }
        refreshHomeFromPrefs();
        startOfferTicker();
        if (homeAdapter != null) homeAdapter.notifyDataSetChanged();

        // ✅ IMPORTANT: clear current intent so onResume won't re-handle old extras again
        setIntent(new Intent(this, HomeActivity.class));
    }

    // =============================================================================================
    // Duty switch (LOCAL duty)
    // =============================================================================================
    private final android.widget.CompoundButton.OnCheckedChangeListener dutyToggleListener = (btn, on) -> {
        setLocalOnDuty(on);

        if (!on) {
            goOffDuty();
            return;
        }

        if (!hasForegroundPerms()) {
            requestForegroundPerms();
            btn.setChecked(false);
            setLocalOnDuty(false);
            return;
        }

        ensureLocationSettingsThenOnDuty();
    };

    private void becameOnDuty() {
        setLocalOnDuty(true);

        requestIgnoreBatteryOptimizationIfNeeded();
        sendDutyToBackend(true);

        ensureTrackingService();

        AssignmentWatcher.get().start(getApplicationContext());
        AssignmentWatcher.get().attach(this);

        Toast.makeText(this, "On Duty – ready", Toast.LENGTH_SHORT).show();

        refreshHomeFromPrefs();
        refreshPhasesFromServer();
        syncHavingTaskWithHome();
    }

    private void goOffDuty() {
        setLocalOnDuty(false);
        sendDutyToBackend(false);

        try {
            Intent stop = new Intent(this, LocationPingService.class);
            stop.setAction(LocationPingService.ACTION_STOP);
            startService(stop);
        } catch (Exception ignored) {}

        try { stopService(new Intent(this, LocationPingService.class)); } catch (Exception ignored) {}
        try { AssignmentWatcher.get().stop(); } catch (Exception ignored) {}

        setActiveTransaction(0L);

        updateHomeListVisibility();
        Toast.makeText(this, "Off Duty – stopped", Toast.LENGTH_SHORT).show();
    }

    // =============================================================================================
    // Location settings flow
    // =============================================================================================
    private boolean isDeviceLocationEnabled() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return false;
            boolean gps = false, net = false;
            try { gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
            try { net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
            return gps || net;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureLocationSettingsThenOnDuty() {
        if (isDeviceLocationEnabled()) {
            ensureAuthThenOnDuty();
            requestBackgroundPermissionIfNeeded();
            return;
        }

        LocationRequest req = new LocationRequest.Builder(5000L)
                .setMinUpdateIntervalMillis(5000L)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .build();

        LocationSettingsRequest settingsRequest = new LocationSettingsRequest.Builder()
                .addLocationRequest(req)
                .setAlwaysShow(true)
                .build();

        SettingsClient client = LocationServices.getSettingsClient(this);
        Task<LocationSettingsResponse> task = client.checkLocationSettings(settingsRequest);

        task.addOnSuccessListener(r -> {
            ensureAuthThenOnDuty();
            requestBackgroundPermissionIfNeeded();
        });

        task.addOnFailureListener(ex -> {
            if (ex instanceof ResolvableApiException) {
                try {
                    ((ResolvableApiException) ex).startResolutionForResult(this, REQ_RESOLVE_LOC);
                } catch (Exception sendEx) {
                    openLocationSettingsFallback();
                }
            } else {
                openLocationSettingsFallback();
            }
        });
    }

    private void openLocationSettingsFallback() {
        try {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            Toast.makeText(this, "Enable Location, then toggle On Duty again.", Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
            Toast.makeText(this, "Unable to open Location settings.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RESOLVE_LOC) {
            if (isDeviceLocationEnabled()) {
                ensureAuthThenOnDuty();
                requestBackgroundPermissionIfNeeded();
            } else {
                Toast.makeText(this, "Location is required to go On Duty.", Toast.LENGTH_SHORT).show();
                if (switchDuty != null) {
                    switchDuty.setOnCheckedChangeListener(null);
                    switchDuty.setChecked(false);
                    setLocalOnDuty(false);
                    switchDuty.setOnCheckedChangeListener(dutyToggleListener);
                }
            }
        }
    }

    private void ensureAuthThenOnDuty() {
        becameOnDuty();
    }

    private boolean hasForegroundPerms() {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean notifOk = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        return (fine || coarse) && notifOk;
    }

    private boolean hasLocationPerms() {
        boolean fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return fine || coarse;
    }

    private void requestForegroundPerms() {
        ArrayList<String> req = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            req.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            req.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            req.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!req.isEmpty()) ActivityCompat.requestPermissions(this, req.toArray(new String[0]), REQ_PERMS);
    }

    private void requestBackgroundPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 29 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 2002);
        }
    }

    @Override public void onRequestPermissionsResult(int c, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(c, p, r);
        if (c == REQ_PERMS && hasForegroundPerms()) {
            Toast.makeText(this, "Permission granted. Toggle On Duty to start.", Toast.LENGTH_SHORT).show();
        }
    }

    private void ensureTrackingService() {
        Log.e("PING", "ensureTrackingService: sessionValid=" + AuthPrefs.isSessionValid(this)
                + " driverId=" + AuthPrefs.driverId(this)
                + " hasLocationPerms=" + hasLocationPerms());

        if (!AuthPrefs.isSessionValid(this) || AuthPrefs.driverId(this) <= 0) return;

        if (!hasLocationPerms()) {
            requestLocationPerms();
            return;
        }

        Intent svc = new Intent(this, LocationPingService.class);
        svc.setAction(LocationPingService.ACTION_START_ON_DUTY);
        svc.putExtra("immediate", true);

        long intervalMs = SettingsPrefs.driverPollingMs(this);
        svc.putExtra("interval_ms", intervalMs);

        ContextCompat.startForegroundService(this, svc);
    }

    private void requestLocationPerms() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{ Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION },
                REQ_LOC
        );
    }

    // =============================================================================================
    // LOCAL task storage (ASSIGNED vs ACCEPTED)
    // =============================================================================================
    private void addAssignedTxLocal(long txId) {
        if (txId <= 0) return;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        HashSet<String> set = new HashSet<>(sp.getStringSet(KEY_ASSIGNED_TX_IDS, new HashSet<>()));
        set.add(String.valueOf(txId));
        sp.edit().putStringSet(KEY_ASSIGNED_TX_IDS, set).apply();
    }

    private void removeAssignedTxLocal(long txId) {
        if (txId <= 0) return;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        HashSet<String> set = new HashSet<>(sp.getStringSet(KEY_ASSIGNED_TX_IDS, new HashSet<>()));
        set.remove(String.valueOf(txId));
        sp.edit().putStringSet(KEY_ASSIGNED_TX_IDS, set).apply();
    }

    private Set<Long> getAssignedTxIdsLocal() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> raw = sp.getStringSet(KEY_ASSIGNED_TX_IDS, new HashSet<>());
        HashSet<Long> out = new HashSet<>();
        if (raw != null) {
            for (String s : raw) { try { out.add(Long.parseLong(s)); } catch (Exception ignored) {} }
        }
        return out;
    }

    private boolean isAssignedLocal(long txId) {
        return getAssignedTxIdsLocal().contains(txId);
    }

    private void addAcceptedTxLocal(long txId) {
        if (txId <= 0) return;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        HashSet<String> set = new HashSet<>(sp.getStringSet(KEY_ACCEPTED_TX_IDS, new HashSet<>()));
        set.add(String.valueOf(txId));
        sp.edit().putStringSet(KEY_ACCEPTED_TX_IDS, set).apply();
    }

    private void removeAcceptedTxLocal(long txId) {
        if (txId <= 0) return;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        HashSet<String> set = new HashSet<>(sp.getStringSet(KEY_ACCEPTED_TX_IDS, new HashSet<>()));
        set.remove(String.valueOf(txId));
        sp.edit().putStringSet(KEY_ACCEPTED_TX_IDS, set).apply();
    }

    private boolean isAcceptedLocal(long txId) {
        return getAcceptedTxIdsLocal().contains(txId);
    }

    private Set<Long> getAcceptedTxIdsLocal() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> raw = sp.getStringSet(KEY_ACCEPTED_TX_IDS, new HashSet<>());
        HashSet<Long> out = new HashSet<>();
        if (raw != null) {
            for (String s : raw) { try { out.add(Long.parseLong(s)); } catch (Exception ignored) {} }
        }
        return out;
    }

    private long getActiveTransaction() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_ACTIVE_TX, 0L);
    }

    private void setActiveTransaction(long tx) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_ACTIVE_TX, tx).apply();
    }

    private boolean getLocalOnDuty() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ON_DUTY, false);
    }

    private void setLocalOnDuty(boolean on) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ON_DUTY, on).apply();
    }

    // =============================================================================================
    // UI: list + adapter
    // =============================================================================================
    private void updateDateText() {
        String text;
        if (Build.VERSION.SDK_INT >= 26) {
            java.time.format.DateTimeFormatter fmt =
                    java.time.format.DateTimeFormatter.ofPattern("MMMM dd", Locale.getDefault());
            text = java.time.LocalDate.now().format(fmt);
        } else {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMMM dd", Locale.getDefault());
            text = sdf.format(new Date());
        }
        if (tvDate != null) tvDate.setText(text);
    }

    private void updateHomeListVisibility() {
        if (rvHomeTasks == null || emptyState == null || homeAdapter == null) return;
        boolean hasItems = homeAdapter.getItemCount() > 0;
        rvHomeTasks.setVisibility(hasItems ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(hasItems ? View.GONE : View.VISIBLE);
    }

    private void refreshList() {
        if (homeAdapter instanceof CombinedTasksAdapter) {
            ((CombinedTasksAdapter) homeAdapter).regroup();
        }
        if (homeAdapter != null) homeAdapter.notifyDataSetChanged();
        updateHomeListVisibility();
        syncHavingTaskWithHome();
        syncBusyWithHomeCards();
        updateUiLocks();
    }

    // ✅ Adapter needs this to show cards even before TaskItem rows arrive
    Set<Long> getAllTxIdsLocal() {
        HashSet<Long> ids = new HashSet<>();
        ids.addAll(getAssignedTxIdsLocal());
        ids.addAll(getAcceptedTxIdsLocal());
        return ids;
    }

    private void refreshHomeFromPrefs() {
        HashSet<Long> ids = new HashSet<>();
        ids.addAll(getAssignedTxIdsLocal());
        ids.addAll(getAcceptedTxIdsLocal());

        for (int i = acceptedTasks.size() - 1; i >= 0; i--) {
            if (!ids.contains(acceptedTasks.get(i).transactionId)) acceptedTasks.remove(i);
        }

        phaseMap.keySet().removeIf(tx -> !ids.contains(tx));

        if (ids.isEmpty()) {
            refreshList();
            syncHavingTaskWithHome();
            return;
        }

        for (long txId : ids) {
            fetchAndAddTaskRows(txId);
        }

        refreshList();
        syncHavingTaskWithHome();
    }

    // =============================================================================================
    // Adapter
    // =============================================================================================
    private void onStartCombinedClicked(long txId, TaskItem pickup, TaskItem deliveryOrNull) {
        startTaskIfNeeded(pickup != null ? pickup : deliveryOrNull);
    }

    static class CombinedTasksAdapter extends RecyclerView.Adapter<CombinedTasksAdapter.VH> {

        interface OnCombinedActionListener {
            void onStartClicked(long transactionId, @NonNull TaskItem pickup, TaskItem deliveryOrNull);
        }

        static class Combined {
            final long transactionId;
            TaskItem pickup;
            TaskItem delivery;
            Combined(long tx) { this.transactionId = tx; }
        }

        private final HomeActivity activity;
        private final List<TaskItem> source;
        private final List<Combined> grouped = new ArrayList<>();
        private final OnCombinedActionListener listener;

        CombinedTasksAdapter(HomeActivity activity, List<TaskItem> source, OnCombinedActionListener listener) {
            this.activity = activity;
            this.source = source;
            this.listener = listener;
            regroup();
        }

        void regroup() {
            grouped.clear();
            Map<Long, Combined> byTx = new java.util.LinkedHashMap<>();

            for (Long tx : activity.getAllTxIdsLocal()) {
                if (tx == null || tx <= 0) continue;

                Phase ph = activity.phaseMap.getOrDefault(tx, Phase.PICKUP_STARTED);
                if (ph == Phase.DELIVERY_COMPLETED) continue;

                byTx.put(tx, new Combined(tx));
            }

            for (TaskItem t : source) {
                Phase ph = activity.phaseMap.getOrDefault(t.transactionId, Phase.PICKUP_STARTED);
                if (ph == Phase.DELIVERY_COMPLETED) continue;

                Combined c = byTx.get(t.transactionId);
                if (c == null) {
                    c = new Combined(t.transactionId);
                    byTx.put(t.transactionId, c);
                }

                if (t.isPickup) c.pickup = t;
                else c.delivery = t;
            }

            List<Combined> tmp = new ArrayList<>(byTx.values());
            java.util.Collections.sort(tmp, (a, b) -> {
                boolean aActive = (activity.getActiveTransaction() == a.transactionId);
                boolean bActive = (activity.getActiveTransaction() == b.transactionId);
                if (aActive != bActive) return aActive ? -1 : 1;
                return Long.compare(b.transactionId, a.transactionId);
            });

            grouped.addAll(tmp);
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_task_card_combined, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            Combined c = grouped.get(i);

            boolean accepted = activity.isAcceptedLocal(c.transactionId);
            boolean active = accepted && (activity.getActiveTransaction() == c.transactionId);
            String status = active ? "Active" : "";

            String prefix = accepted ? "Assignment" : "Assigned";
            h.tvTxTitle.setText(
                    status.isEmpty()
                            ? (prefix + " #" + c.transactionId)
                            : (prefix + " #" + c.transactionId + " — " + status)
            );

            h.tvPickup.setText(c.pickup != null ? c.pickup.address : "Loading pickup…");
            h.tvDrop.setText(c.delivery != null ? c.delivery.address : "Loading drop…");

            // ✅ TIMER: show only for ASSIGNED (not accepted)
            if (!accepted) {
                // show badge
                int left = activity.getOfferRemainingSecs(c.transactionId);

                boolean hasTimer = left > 0;

                if (h.timerBadge != null) h.timerBadge.setVisibility(hasTimer ? View.VISIBLE : View.GONE);
                if (h.tvTimer != null && hasTimer) h.tvTimer.setText(left + "s");


                // ✅ start 1-sec ticker so it updates
                activity.startOfferTicker();

                h.btnPrimary.setText("Accept");
                int bg = ContextCompat.getColor(activity, R.color.verdi_green_bg);
                int fg = ContextCompat.getColor(activity, R.color.verdi_green_text);
                ViewCompat.setBackgroundTintList(h.btnPrimary, ColorStateList.valueOf(bg));
                h.btnPrimary.setTextColor(fg);

                h.btnPrimary.setOnClickListener(v -> activity.acceptOnly(c.transactionId));

                if (h.btnDismiss != null) h.btnDismiss.setVisibility(View.GONE);
                return;
            }

            // accepted -> hide timer
            if (h.timerBadge != null) h.timerBadge.setVisibility(View.GONE);

            if (h.btnDismiss != null) h.btnDismiss.setVisibility(View.GONE);

            Phase phase = activity.phaseMap.getOrDefault(c.transactionId, Phase.PICKUP_STARTED);

            String label;
            switch (phase) {
                case PICKUP_STARTED:     label = "Start";             break;
                case PICKUP_ARRIVED:     label = "Complete Pickup";   break;
                case PICKUP_COMPLETED:   label = "Delivery Started";    break;
                case DELIVERY_ARRIVED:   label = "Complete Delivery"; break;
                case DELIVERY_COMPLETED:
                default:                 label = "Done";              break;
            }
            h.btnPrimary.setText(label);

            boolean green = (phase == Phase.PICKUP_STARTED || phase == Phase.DELIVERY_COMPLETED);
            int bg = ContextCompat.getColor(activity, green ? R.color.verdi_green_bg : R.color.verdi_red_bg);
            int fg = ContextCompat.getColor(activity, green ? R.color.verdi_green_text : R.color.verdi_red_text);
            ViewCompat.setBackgroundTintList(h.btnPrimary, ColorStateList.valueOf(bg));
            h.btnPrimary.setTextColor(fg);

            h.btnPrimary.setOnClickListener(v -> {
                TaskItem forLaunch = (c.pickup != null) ? c.pickup : c.delivery;
                if (forLaunch != null && listener != null) listener.onStartClicked(c.transactionId, forLaunch, c.delivery);
                else Toast.makeText(activity, "Loading task…", Toast.LENGTH_SHORT).show();
            });
        }

        @Override public int getItemCount() { return grouped.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvTxTitle, tvPickup, tvDrop;
            final TextView tvTimer;
            final View timerBadge;

            final Button btnPrimary;
            final Button btnDismiss;

            VH(@NonNull View itemView) {
                super(itemView);
                tvTxTitle = itemView.findViewById(R.id.tvTxTitle);
                tvPickup  = itemView.findViewById(R.id.tvPickupAddress);
                tvDrop    = itemView.findViewById(R.id.tvDropAddress);

                btnPrimary = itemView.findViewById(R.id.btnPrimary);
                btnDismiss = itemView.findViewById(R.id.btnDismiss);

                // ✅ timer views from your xml
                timerBadge = itemView.findViewById(R.id.timerBadge);
                tvTimer    = itemView.findViewById(R.id.tvTimer);
            }
        }
    }

    private final android.os.Handler offerHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean offerTickerRunning = false;

    void startOfferTicker() {
        if (offerTickerRunning) return;
        offerTickerRunning = true;

        offerHandler.post(new Runnable() {
            @Override public void run() {

                // stop ticker if no assigned tasks
                if (getAssignedTxIdsLocal().isEmpty()) {
                    offerTickerRunning = false;
                    return;
                }

                if (homeAdapter != null) homeAdapter.notifyDataSetChanged();
                offerHandler.postDelayed(this, 1000);
            }
        });
    }


    // =============================================================================================
    // Task start flow (unchanged logic; uses bearerHeader)
    // =============================================================================================
    private void startTaskIfNeeded(@NonNull TaskItem item) {
        if (!getLocalOnDuty()) {
            Toast.makeText(this, "Go On Duty first", Toast.LENGTH_SHORT).show();
            return;
        }

        final String bearer = bearerHeader();
        if (bearer == null) {
            Toast.makeText(this, "Session expired. Please login again.", Toast.LENGTH_SHORT).show();
            return;
        }

        api.getTaskDetails(bearer, item.transactionId)
                .enqueue(new Callback<TaskDetailsResponse>() {
                    @Override
                    public void onResponse(Call<TaskDetailsResponse> call, Response<TaskDetailsResponse> res) {

                        if (!res.isSuccessful() || res.body() == null || !res.body().success || res.body().data == null) {
                            launchTaskDetailsWith(item);
                            return;
                        }

                        TaskDetailsResponse.Data d = res.body().data;

                        Phase serverPhase = derivePhase(d);
                        phaseMap.put(item.transactionId, serverPhase);
                        refreshList();

                        String pickupStatus = safe(d.pickup_task != null ? d.pickup_task.task_status : null).toLowerCase();

                        boolean notStartedYet =
                                pickupStatus.isEmpty()
                                        || pickupStatus.equals("pending")
                                        || pickupStatus.equals("accepted")
                                        || pickupStatus.equals("assigned")
                                        || pickupStatus.equals("created");

                        if (serverPhase == Phase.PICKUP_STARTED && notStartedYet) {

                            long pickupRowId = (d.pickup_task != null) ? d.pickup_task.id : 0;
                            if (pickupRowId > 0) {
                                updatePhase(
                                        pickupRowId,
                                        TaskPhase.PICKUP_STARTED,
                                        () -> {
                                            fetchAndAddTaskRows(item.transactionId);
                                            launchTaskDetailsWith(item);
                                        },
                                        () -> {
                                            Toast.makeText(HomeActivity.this,
                                                    "Start failed, opening details", Toast.LENGTH_SHORT).show();
                                            launchTaskDetailsWith(item);
                                        }
                                );
                            } else {
                                launchTaskDetailsWith(item);
                            }

                        } else {
                            launchTaskDetailsWith(item);
                        }
                    }

                    @Override
                    public void onFailure(Call<TaskDetailsResponse> call, Throwable t) {
                        launchTaskDetailsWith(item);
                    }
                });
    }

    private void launchTaskDetailsWith(TaskItem item) {
        Intent it = new Intent(HomeActivity.this, TaskDetailActivity.class);
        it.putExtra("transaction_id", item.transactionId);
        it.putExtra("pickup_id", item.pickupId);
        it.putExtra("delivery_id", item.deliveryId);

        it.putExtra("pickup_address", item.address);
        it.putExtra("pickup_phone", item.phoneE164);
        it.putExtra("pickup_lat", item.lat);
        it.putExtra("pickup_lng", item.lng);

        TaskItem d = null;
        for (TaskItem t : acceptedTasks)
            if (!t.isPickup && t.transactionId == item.transactionId) { d = t; break; }

        if (d != null) {
            it.putExtra("delivery_address", d.address);
            it.putExtra("delivery_phone", d.phoneE164);
            it.putExtra("delivery_lat", d.lat);
            it.putExtra("delivery_lng", d.lng);
        }

        it.putExtra("payment_type", item.paymentType);
        it.putExtra("order_amount", item.orderAmount);
        it.putExtra("order_id", item.orderId);

        Phase phase = phaseMap.getOrDefault(item.transactionId, Phase.PICKUP_STARTED);
        it.putExtra("current_phase", phase.name());
        it.putExtra("pickup_row_id", item.pickupId);

        startActivity(it);
    }

    private void updatePhase(long taskRowId, TaskPhase phase, Runnable onOk, Runnable onErr) {
        String status = TaskPhase.toApi(phase);
        String bearer = bearerHeader();
        if (bearer == null) { if (onErr != null) onErr.run(); return; }

        api.updateTaskStatus(bearer, taskRowId, status)
                .enqueue(new Callback<GenericResponse>() {
                    @Override public void onResponse(Call<GenericResponse> call, Response<GenericResponse> res) {
                        if (res.isSuccessful() && res.body() != null && res.body().success) {
                            if (onOk != null) onOk.run();
                        } else {
                            if (onErr != null) onErr.run();
                        }
                    }
                    @Override public void onFailure(Call<GenericResponse> call, Throwable t) {
                        if (onErr != null) onErr.run();
                    }
                });
    }

    // =============================================================================================
    // ✅ FIXED: fetchAndAddTaskRows (fallback for http 500 + bearerHeader)
    // =============================================================================================
    private void fetchAndAddTaskRows(long txId) {
        String bearer = bearerHeader();
        if (bearer == null) {
            Log.e(TAG, "fetchAndAddTaskRows: bearer NULL tx=" + txId);
            upsertFallbackRows(txId, "Session expired", "Session expired");
            refreshList();
            return;
        }

        api.getTaskDetails(bearer, txId).enqueue(new Callback<TaskDetailsResponse>() {
            @Override
            public void onResponse(Call<TaskDetailsResponse> call, Response<TaskDetailsResponse> res) {

                Log.d(TAG, "getTaskDetails tx=" + txId + " http=" + res.code());

                if (!res.isSuccessful()) {
                    // ✅ fallback so UI doesn't stay Loading...
                    upsertFallbackRows(txId,
                            "Address unavailable (server " + res.code() + ")",
                            "Address unavailable (server " + res.code() + ")");
                    refreshList();

                    try {
                        String err = (res.errorBody() != null) ? res.errorBody().string() : "";
                        Log.e(TAG, "getTaskDetails FAILED tx=" + txId + " http=" + res.code() + " err=" + err);
                    } catch (Exception ignored) {}
                    return;
                }

                TaskDetailsResponse body = res.body();
                if (body == null) {
                    upsertFallbackRows(txId, "No data", "No data");
                    refreshList();
                    return;
                }

                if (!body.success || body.data == null) {
                    upsertFallbackRows(txId, "No task data", "No task data");
                    refreshList();
                    return;
                }

                Log.d(TAG, "getTaskDetails OK tx=" + txId
                        + " pickup=" + (body.data.pickup_task != null ? body.data.pickup_task.address : "null")
                        + " drop=" + (body.data.delivery_task != null ? body.data.delivery_task.address : "null"));

                addAcceptedRowsFromApi(body.data);
                phaseMap.put(txId, derivePhase(body.data));
                refreshList();
            }

            @Override
            public void onFailure(Call<TaskDetailsResponse> call, Throwable t) {
                Log.e(TAG, "getTaskDetails onFailure tx=" + txId, t);
                upsertFallbackRows(txId, "Network error", "Network error");
                refreshList();
            }
        });
    }

    // ✅ fallback rows so adapter can show address text even if API fails
    private void upsertFallbackRows(long txId, String pickupAddr, String dropAddr) {
        for (int i = acceptedTasks.size() - 1; i >= 0; i--) {
            if (acceptedTasks.get(i).transactionId == txId) acceptedTasks.remove(i);
        }

        acceptedTasks.add(new TaskItem(
                txId * 10 + 1, txId,
                "Pickup",
                pickupAddr != null ? pickupAddr : "-",
                "",
                Double.NaN, Double.NaN,
                true,
                0, 0,
                "", "", ""
        ));

        acceptedTasks.add(new TaskItem(
                txId * 10 + 2, txId,
                "Dropoff",
                dropAddr != null ? dropAddr : "-",
                "",
                Double.NaN, Double.NaN,
                false,
                0, 0,
                "", "", ""
        ));
    }

    // =============================================================================================
    // ✅ FIXED: addAcceptedRowsFromApi (shows address even if pickupId/deliveryId = 0)
    // =============================================================================================
    private void addAcceptedRowsFromApi(TaskDetailsResponse.Data d) {
        if (d == null) return;

        long txId = d.id;

        long pickupId   = (d.pickup_task != null) ? d.pickup_task.id : 0;
        long deliveryId = (d.delivery_task != null) ? d.delivery_task.id : 0;

        String payType  = safe(d.vendor_payment_type);
        String orderAmt = safe(d.order_amount);
        String orderId  = safe(d.order_id);

        for (int i = acceptedTasks.size() - 1; i >= 0; i--) {
            if (acceptedTasks.get(i).transactionId == txId) acceptedTasks.remove(i);
        }

        if (d.pickup_task != null) {
            double plat = safeParse(d.pickup_task.lat);
            double plng = safeParse(d.pickup_task.lng);

            String pAddr = safe(d.pickup_task.address);
            if (pAddr.isEmpty()) pAddr = "-";
            String pPhone = safe(d.pickup_task.phone);

            long displayId = (pickupId > 0) ? pickupId : (txId * 10 + 1);

            acceptedTasks.add(new TaskItem(
                    displayId, txId,
                    "Pickup: " + pAddr,
                    pAddr,
                    pPhone,
                    plat, plng,
                    true,
                    pickupId, deliveryId,
                    payType, orderAmt, orderId
            ));
        }

        if (d.delivery_task != null) {
            double dlat = safeParse(d.delivery_task.lat);
            double dlng = safeParse(d.delivery_task.lng);

            String dAddr = safe(d.delivery_task.address);
            if (dAddr.isEmpty()) dAddr = "-";
            String dPhone = safe(d.delivery_task.phone);

            long displayId = (deliveryId > 0) ? deliveryId : (txId * 10 + 2);

            acceptedTasks.add(new TaskItem(
                    displayId, txId,
                    "Dropoff: " + dAddr,
                    dAddr,
                    dPhone,
                    dlat, dlng,
                    false,
                    pickupId, deliveryId,
                    payType, orderAmt, orderId
            ));
        }
    }

    private static double safeParse(String s) {
        try { return s == null ? Double.NaN : Double.parseDouble(s); }
        catch (Exception e) { return Double.NaN; }
    }

    private static String safe(String s){ return s == null ? "" : s.trim(); }

    private Phase derivePhase(TaskDetailsResponse.Data d) {
        String pStat = (d.pickup_task != null) ? safe(d.pickup_task.task_status) : "";
        String dStat = (d.delivery_task != null) ? safe(d.delivery_task.task_status) : "";

        boolean pickupDone = "success".equalsIgnoreCase(pStat);
        boolean pickupArr  = "arrived".equalsIgnoreCase(pStat);
        boolean deliveryDone = "success".equalsIgnoreCase(dStat);
        boolean deliveryArr  = "arrived".equalsIgnoreCase(dStat);

        if (deliveryDone) return Phase.DELIVERY_COMPLETED;
        if (deliveryArr)  return Phase.DELIVERY_ARRIVED;
        if (pickupDone)   return Phase.PICKUP_COMPLETED;
        if (pickupArr)    return Phase.PICKUP_ARRIVED;
        return Phase.PICKUP_STARTED;
    }

    // =============================================================================================
    // Accept flow (split) – ✅ FIXED bearer usage inside acceptTransaction
    // =============================================================================================
    public void acceptOnly(long txId) {
        if (!getLocalOnDuty()) {
            Toast.makeText(this, "Go On Duty first", Toast.LENGTH_SHORT).show();
            return;
        }

        acceptTransaction(txId,
                () -> {
                    removeAssignedTxLocal(txId);
                    addAcceptedTxLocal(txId);

                    setActiveTransaction(txId);
                    fetchAndAddTaskRows(txId);
                    refreshList();
                    refreshPhasesFromServer();

                    Toast.makeText(this, "Accepted", Toast.LENGTH_SHORT).show();
                },
                () -> Toast.makeText(this, "Accept failed. Try again.", Toast.LENGTH_SHORT).show()
        );
    }

    private void acceptTransaction(long txId,
                                   @NonNull Runnable onOk,
                                   @NonNull Runnable onErr) {

        final String bearer = bearerHeader(); // ✅ FIX (your previous code used AuthPrefs.bearer directly)
        if (bearer == null || bearer.isEmpty()) { onErr.run(); return; }

        api.getTaskDetails(bearer, txId).enqueue(new Callback<TaskDetailsResponse>() {
            @Override public void onResponse(Call<TaskDetailsResponse> call, Response<TaskDetailsResponse> res) {
                if (!res.isSuccessful() || res.body()==null || !res.body().success || res.body().data==null) {
                    onErr.run(); return;
                }
                TaskDetailsResponse.Data d = res.body().data;

                long driverId = AuthPrefs.driverId(HomeActivity.this);
                if (driverId <= 0) { onErr.run(); return; }

                api.assignDriver(bearer, d.id, driverId, "accepted").enqueue(new Callback<GenericResponse>() {
                    @Override public void onResponse(Call<GenericResponse> call, Response<GenericResponse> ar) {
                        if (!ar.isSuccessful() || ar.body()==null || !ar.body().success) {
                            onErr.run(); return;
                        }

                        ArrayList<Long> rowIds = new ArrayList<>();
                        if (d.pickup_task != null && d.pickup_task.id > 0) rowIds.add(d.pickup_task.id);
                        if (d.delivery_task != null && d.delivery_task.id > 0) rowIds.add(d.delivery_task.id);

                        // ✅ If backend returns 0 ids, still allow accept to succeed (we already show card + address)
                        if (rowIds.isEmpty()) {
                            onOk.run();
                            return;
                        }

                        acceptRowsSequentially(bearer, rowIds, 0, onOk, onErr);
                    }

                    @Override public void onFailure(Call<GenericResponse> call, Throwable t) { onErr.run(); }
                });
            }
            @Override public void onFailure(Call<TaskDetailsResponse> call, Throwable t) { onErr.run(); }
        });
    }

    private void acceptRowsSequentially(String bearer,
                                        List<Long> rowIds,
                                        int idx,
                                        @NonNull Runnable done,
                                        @NonNull Runnable err) {
        if (idx >= rowIds.size()) { done.run(); return; }

        long rowId = rowIds.get(idx);
        api.acceptTask(bearer, rowId).enqueue(new Callback<GenericResponse>() {
            @Override public void onResponse(Call<GenericResponse> call, Response<GenericResponse> res) {
                acceptRowsSequentially(bearer, rowIds, idx + 1, done, err);
            }
            @Override public void onFailure(Call<GenericResponse> call, Throwable t) {
                acceptRowsSequentially(bearer, rowIds, idx + 1, done, err);
            }
        });
    }

    // =============================================================================================
    // Popup + Receivers (kept from your logic)
    // =============================================================================================
    private void showPopupForTxId(long txId, int seconds) {

        // ✅ If seconds missing from intent/push, use stored remaining secs
        int secs = seconds;
        if (secs <= 0) secs = getOfferRemainingSecs(txId);
        if (secs <= 0) secs = 0;   // no expiry if missing
        final int finalSecs = secs;


        final String bearer = bearerHeader();
        if (bearer == null) {
            showTaskPopupWithTimer(txId, null, null, null, null, null, finalSecs);
            return;
        }

        ApiClient.get().create(ApiService.class)
                .getTaskDetails(bearer, txId)
                .enqueue(new Callback<TaskDetailsResponse>() {
                    @Override
                    public void onResponse(Call<TaskDetailsResponse> call, Response<TaskDetailsResponse> res) {

                        String pickup = null, drop = null, type = null, status = null, created = null;

                        if (res.isSuccessful() && res.body() != null && res.body().success && res.body().data != null) {
                            TaskDetailsResponse.Data d = res.body().data;

                            if (d.pickup_task != null) {
                                pickup = d.pickup_task.address;
                                status = d.pickup_task.task_status;
                            }
                            if (d.delivery_task != null) {
                                drop = d.delivery_task.address;
                            }

                            type = d.vendor_payment_type;
                            created = (d.created_at != null) ? prettyTime(d.created_at) : null;
                        }

                        showTaskPopupWithTimer(txId, pickup, drop, type, status, created, finalSecs);
                    }

                    @Override
                    public void onFailure(Call<TaskDetailsResponse> call, Throwable t) {
                        showTaskPopupWithTimer(txId, null, null, null, null, null, finalSecs);
                    }
                });
    }


    @Nullable private TextView fcmTvTimer = null;
    @Nullable private View fcmTimerBadge = null;


    public void showTaskPopupWithTimer(long txId,
                                       @Nullable String pickup,
                                       @Nullable String drop,
                                       @Nullable String type,
                                       @Nullable String status,
                                       @Nullable String created,
                                       int seconds) {

        if (isAcceptedLocal(txId)) {
            Log.d(TAG, "Popup skipped (already accepted) tx=" + txId);
            return;
        }

        // ✅ NEW: close old popup if still open
        try {
            if (fcmPopupDialog != null && fcmPopupDialog.isShowing()) {
                fcmPopupDialog.dismiss();
            }
        } catch (Exception ignored) {}

        final BottomSheetDialog dialog = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.fcm_task_popup_card, null, false);
        dialog.setContentView(v);

        TextView tvTxTitle = v.findViewById(R.id.tvTxTitle);
        TextView tvPickup  = v.findViewById(R.id.tvPickupAddress);
        TextView tvDrop    = v.findViewById(R.id.tvDropAddress);

        Button btnAccept   = v.findViewById(R.id.btnAccept);

        if (tvTxTitle != null) tvTxTitle.setText("Assigned #" + txId);

        if (tvPickup != null) tvPickup.setText(pickup != null && !pickup.trim().isEmpty() ? pickup : "—");
        if (tvDrop != null)   tvDrop.setText(drop != null && !drop.trim().isEmpty() ? drop : "—");

        // ✅ new timer views
        TextView tvTimer = v.findViewById(R.id.tvTimer);
        View timerBadge  = v.findViewById(R.id.timerBadge);

// ✅ store refs
        fcmBtnAccept = btnAccept;
        fcmTvTimer = tvTimer;
        fcmTimerBadge = timerBadge;

        fcmPopupDialog = dialog;
        fcmBtnAccept   = btnAccept;
        fcmCurrentTxId = txId;

        startOrRestartTimer(seconds);

        dialog.setOnDismissListener(d -> {
            try { if (fcmPopupTimer != null) fcmPopupTimer.cancel(); } catch (Exception ignored) {}
            fcmPopupTimer = null;
            fcmPopupDialog = null;
            fcmBtnDismiss = null;
            fcmBtnAccept = null;
            fcmCurrentTxId = 0L;
            fcmCurrentSecs = 0;

            fcmTvTimer = null;
            fcmTimerBadge = null;

        });

        if (btnAccept != null) {
            btnAccept.setOnClickListener(view -> {
                if (!getLocalOnDuty()) {
                    Toast.makeText(this, "Go On Duty first", Toast.LENGTH_SHORT).show();
                    return;
                }

                btnAccept.setEnabled(false);
                btnAccept.setText("Accepting…");

                try { if (fcmPopupTimer != null) fcmPopupTimer.cancel(); } catch (Exception ignored) {}
                fcmPopupTimer = null;

                acceptOnly(txId);
                dialog.dismiss();
            });
        }

        dialog.show();
    }

    private void startOrRestartTimer(int seconds) {

        // No expiry -> hide badge + stop timer
        if (seconds <= 0) {
            fcmCurrentSecs = 0;
            if (fcmPopupTimer != null) { fcmPopupTimer.cancel(); fcmPopupTimer = null; }

            if (fcmTimerBadge != null) fcmTimerBadge.setVisibility(View.GONE);
            if (fcmTvTimer != null) fcmTvTimer.setText("");
            return;
        }

        int secs = seconds;
        fcmCurrentSecs = secs;

        if (fcmPopupTimer != null) { fcmPopupTimer.cancel(); fcmPopupTimer = null; }

        if (fcmTimerBadge != null) fcmTimerBadge.setVisibility(View.VISIBLE);
        if (fcmTvTimer != null) fcmTvTimer.setText(secs + "s");

        fcmPopupTimer = new CountDownTimer(secs * 1000L, 1000L) {
            @Override public void onTick(long msLeft) {
                int s = (int) Math.ceil(msLeft / 1000.0);
                if (fcmTvTimer != null) fcmTvTimer.setText(s + "s");
            }
            @Override public void onFinish() {
                if (fcmPopupDialog != null && fcmPopupDialog.isShowing()) {
                    fcmPopupDialog.dismiss();
                }
            }
        }.start();
    }



    private String prettyTime(String iso) {
        try {
            String trimmed = iso;
            int dot = trimmed.indexOf('.');
            if (dot > 0) trimmed = trimmed.substring(0, dot) + "Z";
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            in.setLenient(true);
            Date d = in.parse(trimmed);
            java.text.SimpleDateFormat out = new java.text.SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault());
            return d != null ? out.format(d) : iso;
        } catch (Exception e) {
            return iso;
        }
    }

    private final BroadcastReceiver txAcceptedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) {
            if (i != null && i.hasExtra("accepted_tx")) {
                long tx = i.getLongExtra("accepted_tx", 0);
                if (tx > 0) {
                    removeAssignedTxLocal(tx);
                    addAcceptedTxLocal(tx);
                    setActiveTransaction(tx);
                    fetchAndAddTaskRows(tx);
                    refreshList();
                    syncHavingTaskWithHome();
                    refreshPhasesFromServer();
                }
            }
        }
    };

    private final BroadcastReceiver phaseReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) {
            if (i == null) return;
            long tx = i.getLongExtra("transaction_id", 0);
            String p = i.getStringExtra("phase");
            if (tx > 0 && p != null) {
                try {
                    Phase phase = Phase.valueOf(p);
                    phaseMap.put(tx, phase);

                    if (phase == Phase.DELIVERY_COMPLETED) {
                        removeAssignedTxLocal(tx);
                        removeAcceptedTxLocal(tx);
                        if (getActiveTransaction() == tx) setActiveTransaction(0L);
                        removeTransactionEverywhere(tx);
                        syncHavingTaskWithHome();
                    } else {
                        refreshList();
                    }
                } catch (IllegalArgumentException ignore) { }
            }
        }
    };

    private final BroadcastReceiver fcmOfferReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            long tx = intent.getLongExtra(EXTRA_TX_ID, 0L);
            int secs = intent.getIntExtra(EXTRA_SECS, 0);
            if (tx <= 0) return;

            if (!getLocalOnDuty()) {
                Log.w(TAG, "FCM offer ignored (off duty) tx=" + tx);
                return;
            }


            addAssignedTxLocal(tx);

            if (secs > 0) {
                saveOfferTimer(tx, secs);
                savePendingOffer(tx, secs);

                // only auto-expire when secs exists
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (!isAcceptedLocal(tx) && isAssignedLocal(tx)) {
                        removeAssignedTxLocal(tx);
                        clearOfferTimer(tx);
                        removeTransactionEverywhere(tx);
                    }
                }, secs * 1000L);
            } else {
                // no expiry timer at all
                clearOfferTimer(tx); // optional: ensures badge doesn't show old timer
            }

            refreshHomeFromPrefs();
            fetchAndAddTaskRows(tx);
            startOfferTicker();
            if (homeAdapter != null) homeAdapter.notifyDataSetChanged();

        }
    };


    // =============================================================================================
    // Ably listener (same behavior; calls fetchAndAddTaskRows)
    // =============================================================================================
    private void startAblyListener() {
        if (ablyPushManager != null) return;

        ablyPushManager = new AblyPushManager(getApplicationContext());

        ablyPushManager.start(
                event -> {
                    if (event == null) return;

                    final long txId = event.txId;
                    final int  secs = event.secs;          // ✅ comes from AblyPushManager now
                    final String type = (event.type == null) ? "" : event.type.trim().toLowerCase();
                    if (txId <= 0) return;

                    runOnUiThread(() -> {

                        if (!getLocalOnDuty()) {
                            Log.w("ABLY", "offer ignored (off duty) tx=" + txId);
                            return;
                        }

                        switch (type) {
                            case "assigned":
                            case "active":
                            case "auto-allocation": {

                                if (!isAcceptedLocal(txId)) addAssignedTxLocal(txId);

                                // ✅ expiry ONLY when backend sends secs
                                if (secs > 0) {
                                    saveOfferTimer(txId, secs);
                                    savePendingOffer(txId, secs);

                                    long token = bumpExpiryToken(txId); // ✅ unique id for this offer

                                    offerHandler.postDelayed(() -> {

                                        // ✅ if token changed, means task came again, so ignore old expiry
                                        if (getExpiryToken(txId) != token) {
                                            Log.w("OFFER", "ignore old expiry tx=" + txId);
                                            return;
                                        }

                                        if (!isAcceptedLocal(txId) && isAssignedLocal(txId)) {
                                            removeAssignedTxLocal(txId);
                                            clearOfferTimer(txId);
                                            removeTransactionEverywhere(txId);
                                        }
                                    }, secs * 1000L);
                                } else {
                                    clearOfferTimer(txId);
                                    bumpExpiryToken(txId); // ✅ cancel any old expiry waiting
                                }


                                refreshHomeFromPrefs();
                                fetchAndAddTaskRows(txId);

                                startOfferTicker();
                                if (homeAdapter != null) homeAdapter.notifyDataSetChanged();

                                if (homeResumed && !isAcceptedLocal(txId)) vibrateOffer();

                                break;
                            }
                            case "unassigned":
                            case "cancelled": {

                                if (shouldSuppressAdmin(txId)) break;
                                markSuppressAdmin(txId);

                                removeAssignedTxLocal(txId);
                                removeAcceptedTxLocal(txId);
                                clearOfferTimer(txId);

                                if (getActiveTransaction() == txId) setActiveTransaction(0L);
                                removeTransactionEverywhere(txId);

                                String msg = ("cancelled".equals(type))
                                        ? "Task #" + txId + " was cancelled by admin."
                                        : "Task #" + txId + " was unassigned by admin.";

                                // ✅ ALWAYS: sound notification (like TaskDetail)
                                showAdminNotification(txId, msg);

                                // ✅ ALSO: popup if app is open (keep this if you want)
                                if (homeResumed) {
                                    new AlertDialog.Builder(this)
                                            .setTitle("Task Update")
                                            .setMessage(msg)
                                            .setCancelable(false)
                                            .setPositiveButton("OK", (d, w) -> d.dismiss())
                                            .show();
                                }

                                break;
                            }

                            default:
                                refreshHomeFromPrefs();
                                break;
                        }
                    });
                },
                on -> runOnUiThread(() -> {
                    if (switchDuty == null) return;

                    switchDuty.setOnCheckedChangeListener(null);
                    switchDuty.setChecked(on);
                    switchDuty.setOnCheckedChangeListener(dutyToggleListener);

                    setLocalOnDuty(on);

                    if (on) {
                        if (hasForegroundPerms()) ensureTrackingService();
                        refreshHomeFromPrefs();
                    } else {
                        try { stopService(new Intent(this, LocationPingService.class)); } catch (Exception ignored) {}
                        try { AssignmentWatcher.get().stop(); } catch (Exception ignored) {}
                        updateHomeListVisibility();
                    }
                })
        );
    }

    private void stopAblyListener() {
        if (ablyPushManager != null) {
            try { ablyPushManager.stop(); } catch (Exception ignored) {}
            ablyPushManager = null;
        }
    }

    private void showAdminUnassignAlert(long txId, @NonNull String reason) {
        if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;

        String msg = "Task #" + txId + " " + reason;

        if (homeResumed) {
            // Foreground -> popup only
            new AlertDialog.Builder(this)
                    .setTitle("Task Update")
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton("OK", (d, w) -> d.dismiss())
                    .show();
        } else {
            // Background -> notification + sound
            showAdminNotification(txId, msg);
        }
    }

    // =============================================================================================
    // Backend duty + logged_in
    // =============================================================================================
    private void sendDutyToBackend(boolean on) {
        long driverId = AuthPrefs.driverId(this);
        if (driverId <= 0) return;

        String bearer = bearerHeader();
        if (bearer == null) return;

        int isOnline = on ? 1 : 0;

        ApiClient.get().create(ApiService.class)
                .updateOnDuty(bearer, driverId, isOnline)
                .enqueue(new Callback<GenericResponse>() {
                    @Override public void onResponse(Call<GenericResponse> call, Response<GenericResponse> res) {
                        Log.d(TAG, "Duty update http=" + res.code());
                    }
                    @Override public void onFailure(Call<GenericResponse> call, Throwable t) {
                        Log.e(TAG, "Duty update failed", t);
                    }
                });
    }

    private void sendLoggedInToBackend(int isLoggedIn) {
        long driverId = AuthPrefs.driverId(this);
        if (driverId <= 0) return;

        String bearer = bearerHeader();
        if (bearer == null) return;

        ApiClient.get().create(ApiService.class)
                .updateLoggedIn(bearer, driverId, isLoggedIn)
                .enqueue(new Callback<GenericResponse>() {
                    @Override public void onResponse(Call<GenericResponse> call, Response<GenericResponse> res) {
                        Log.d("Logout", "update_logged_in=" + isLoggedIn + " http=" + res.code());
                    }
                    @Override public void onFailure(Call<GenericResponse> call, Throwable t) {
                        Log.e("Logout", "update_logged_in failed", t);
                    }
                });
    }

    // =============================================================================================
    // Deep-link / push intent parse (your original logic)
    // =============================================================================================
    private void handlePushIntent(@Nullable Intent intent) {
        if (intent == null) return;

        long tx = intent.getLongExtra("push_tx_id", 0L);
        int secs = intent.getIntExtra("push_tx_secs", 0);

        if (tx <= 0) {
            String txPayload = intent.getStringExtra("tx_payload");
            if (txPayload != null) {
                TxPayload p = parseTxPayload(txPayload);
                if (p != null) {
                    tx = p.txId;
                    if (secs <= 0) secs = p.secs;
                }
            }
        }

        if (tx == 0 && intent.getData() != null) {
            String path = intent.getData().toString();
            tx = extractTxIdFromText(path);
        }

        if (tx == 0) {
            String[] candidates = {"tx_payload", "gcm.notification.body", "body", "raw_body", "transaction_id"};
            for (String k : candidates) {
                String t = intent.getStringExtra(k);
                if (t == null) continue;

                TxPayload p = parseTxPayload(t);
                if (p != null && p.txId > 0) {
                    tx = p.txId;
                    if (secs <= 0) secs = p.secs;
                    break;
                }

                tx = extractTxIdFromText(t);
                if (tx > 0) break;
            }
        }

        if (tx > 0) {
            final long finalTx = tx;
            final int finalSecs = (secs > 0 ? secs : 5);

            intent.removeExtra("push_tx_id");
            intent.removeExtra("push_tx_secs");
            intent.removeExtra("tx_payload");
            intent.removeExtra("raw_body");
            intent.removeExtra("body");

        }
    }

    private static final class TxPayload {
        final long txId;
        final int secs;
        TxPayload(long txId, int secs) { this.txId = txId; this.secs = secs; }
    }

    @Nullable
    private static TxPayload parseTxPayload(@Nullable String raw) {
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.isEmpty()) return null;

        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(\\d+)(?:\\s*,\\s*(\\d+))?$")
                    .matcher(raw);
            if (m.find()) {
                long id = Long.parseLong(m.group(1));
                int secs = (m.group(2) != null) ? Integer.parseInt(m.group(2)) : 0;
                return new TxPayload(id, secs);
            }

            m = java.util.regex.Pattern.compile("(\\d{1,})").matcher(raw);
            if (m.find()) return new TxPayload(Long.parseLong(m.group(1)), 0);
        } catch (Exception ignore) {}
        return null;
    }

    static long extractTxIdFromText(@Nullable String text) {
        if (text == null) return 0;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:#|\\btx\\b|transaction(?:\\s*id)?\\s*[:#]?)\\s*(\\d+)",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(text);
            if (m.find()) return Long.parseLong(m.group(1));

            m = java.util.regex.Pattern.compile("(\\d{3,})").matcher(text);
            if (m.find()) return Long.parseLong(m.group(1));
        } catch (Exception ignore) {}
        return 0;
    }

    // =============================================================================================
    // LBM receiver for TASK_DETAILS_UPDATED
    // =============================================================================================
    private final BroadcastReceiver homeTaskDetailsReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!Actions.TASK_DETAILS_UPDATED.equals(intent.getAction())) return;

            long txId = intent.getLongExtra(Actions.EXTRA_TX_ID, 0L);
            if (txId <= 0) return;

            long pickupId   = intent.getLongExtra(Actions.EXTRA_PICKUP_ID, 0L);
            long deliveryId = intent.getLongExtra(Actions.EXTRA_DELIVERY_ID, 0L);

            String pickupSt   = intent.getStringExtra(Actions.EXTRA_PICKUP_TASK_STATUS);
            String deliverySt = intent.getStringExtra(Actions.EXTRA_DELIVERY_TASK_STATUS);

            String pickupAddr = intent.getStringExtra(Actions.EXTRA_PICKUP_ADDRESS);
            String pickupPhone= intent.getStringExtra(Actions.EXTRA_PICKUP_PHONE);
            double pickupLat  = intent.getDoubleExtra(Actions.EXTRA_PICKUP_LAT, Double.NaN);
            double pickupLng  = intent.getDoubleExtra(Actions.EXTRA_PICKUP_LNG, Double.NaN);

            String deliveryAddr = intent.getStringExtra(Actions.EXTRA_DELIVERY_ADDRESS);
            String deliveryPhone= intent.getStringExtra(Actions.EXTRA_DELIVERY_PHONE);
            double deliveryLat  = intent.getDoubleExtra(Actions.EXTRA_DELIVERY_LAT, Double.NaN);
            double deliveryLng  = intent.getDoubleExtra(Actions.EXTRA_DELIVERY_LNG, Double.NaN);

            String payType = intent.getStringExtra(Actions.EXTRA_PAYMENT_TYPE);
            String orderAmt= intent.getStringExtra(Actions.EXTRA_ORDER_AMOUNT);
            String orderId = intent.getStringExtra(Actions.EXTRA_ORDER_ID);

            Phase ph = derivePhaseFromStatuses(pickupSt, deliverySt);
            phaseMap.put(txId, ph);

            upsertAcceptedRowsFromBroadcast(
                    txId,
                    pickupId, pickupAddr, pickupPhone, pickupLat, pickupLng,
                    deliveryId, deliveryAddr, deliveryPhone, deliveryLat, deliveryLng,
                    payType, orderAmt, orderId
            );

            runOnUiThread(HomeActivity.this::refreshList);
        }
    };

    private void registerHomeLbmReceiversIfNeeded() {
        if (lbmReceiversRegistered) return;
        lbmReceiversRegistered = true;

        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(dutyChangedReceiver,
                new IntentFilter(AblyPushManager.ACTION_DUTY_CHANGED));

        Log.d(TAG, "Home LBM receivers REGISTERED");
    }

    private void unregisterHomeLbmReceivers() {
        if (!lbmReceiversRegistered) return;
        lbmReceiversRegistered = false;

        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        try { lbm.unregisterReceiver(dutyChangedReceiver); } catch (Exception ignored) {}

        Log.d(TAG, "Home LBM receivers UNREGISTERED");
    }

    private Phase derivePhaseFromStatuses(@Nullable String pickupTaskStatus, @Nullable String deliveryTaskStatus) {
        String p = pickupTaskStatus   != null ? pickupTaskStatus.trim().toLowerCase()   : "";
        String d = deliveryTaskStatus != null ? deliveryTaskStatus.trim().toLowerCase() : "";

        if (!d.isEmpty()) {
            if (d.contains("success") || d.contains("completed")) return Phase.DELIVERY_COMPLETED;
            if (d.contains("arrived")) return Phase.DELIVERY_ARRIVED;
            if (d.contains("started")) return Phase.PICKUP_COMPLETED;
        }

        if (!p.isEmpty()) {
            if (p.contains("success") || p.contains("completed")) return Phase.PICKUP_COMPLETED;
            if (p.contains("arrived")) return Phase.PICKUP_ARRIVED;
            if (p.contains("started") || p.contains("pending") || p.contains("accepted") || p.contains("assigned"))
                return Phase.PICKUP_STARTED;
        }

        return Phase.PICKUP_STARTED;
    }

    private void upsertAcceptedRowsFromBroadcast(
            long txId,
            long pickupId, @Nullable String pickupAddr, @Nullable String pickupPhone, double pickupLat, double pickupLng,
            long deliveryId, @Nullable String deliveryAddr, @Nullable String deliveryPhone, double deliveryLat, double deliveryLng,
            @Nullable String payType, @Nullable String orderAmt, @Nullable String orderId
    ) {
        for (int i = acceptedTasks.size() - 1; i >= 0; i--) {
            TaskItem t = acceptedTasks.get(i);
            if (t.transactionId != txId) continue;

            if (t.isPickup && pickupId > 0 && t.serverId == pickupId) acceptedTasks.remove(i);
            else if (!t.isPickup && deliveryId > 0 && t.serverId == deliveryId) acceptedTasks.remove(i);
        }

        long pickupDisplay = (pickupId > 0) ? pickupId : (txId * 10 + 1);
        long dropDisplay   = (deliveryId > 0) ? deliveryId : (txId * 10 + 2);

        acceptedTasks.add(new TaskItem(
                pickupDisplay, txId,
                "Pickup: " + (pickupAddr != null ? pickupAddr : "-"),
                pickupAddr != null ? pickupAddr : "-",
                pickupPhone != null ? pickupPhone : "",
                pickupLat, pickupLng,
                true, pickupId, deliveryId,
                payType, orderAmt, orderId
        ));

        acceptedTasks.add(new TaskItem(
                dropDisplay, txId,
                "Dropoff: " + (deliveryAddr != null ? deliveryAddr : "-"),
                deliveryAddr != null ? deliveryAddr : "-",
                deliveryPhone != null ? deliveryPhone : "",
                deliveryLat, deliveryLng,
                false, pickupId, deliveryId,
                payType, orderAmt, orderId
        ));
    }

    // =============================================================================================
    // refresh phases from server
    // =============================================================================================
    private void refreshPhasesFromServer() {
        String bearer = bearerHeader();
        if (bearer == null) return;

        HashSet<Long> txIds = new HashSet<>();
        txIds.addAll(getAssignedTxIdsLocal());
        txIds.addAll(getAcceptedTxIdsLocal());

        for (long txId : txIds) {
            api.getTaskDetails(bearer, txId).enqueue(new Callback<TaskDetailsResponse>() {
                @Override public void onResponse(Call<TaskDetailsResponse> call, Response<TaskDetailsResponse> res) {
                    if (res.isSuccessful() && res.body()!=null && res.body().success && res.body().data!=null) {
                        Phase ph = derivePhase(res.body().data);

                        if (ph == Phase.DELIVERY_COMPLETED) {
                            removeAssignedTxLocal(txId);
                            removeAcceptedTxLocal(txId);
                            if (getActiveTransaction() == txId) setActiveTransaction(0L);
                            clearOfferTimer(txId);
                            removeTransactionEverywhere(txId);
                        } else {
                            phaseMap.put(txId, ph);
                            refreshList();
                        }
                    } else if (res.code() == 404) {
                        removeAssignedTxLocal(txId);
                        removeAcceptedTxLocal(txId);
                        if (getActiveTransaction() == txId) setActiveTransaction(0L);
                        clearOfferTimer(txId);
                        removeTransactionEverywhere(txId);
                    }
                }
                @Override public void onFailure(Call<TaskDetailsResponse> call, Throwable t) { }
            });
        }
    }

    private void removeTransactionEverywhere(long txId) {
        for (int i = acceptedTasks.size() - 1; i >= 0; i--) {
            if (acceptedTasks.get(i).transactionId == txId) acceptedTasks.remove(i);
        }
        phaseMap.remove(txId);
        refreshList();
        syncHavingTaskWithHome();
    }

    // =============================================================================================
    // Logout + nav (your same logic)
    // =============================================================================================
    private void doLogout() {
        if (logoutInProgress) {
            Log.w("Logout", "doLogout ignored: already in progress");
            return;
        }
        logoutInProgress = true;

        if (isBusyNow()) {
            Toast.makeText(this, "Finish current task before logging out.", Toast.LENGTH_SHORT).show();
            logoutInProgress = false;
            return;
        }

        try {
            try { stopAblyListener(); } catch (Exception ignored) {}

            setLocalOnDuty(false);
            sendDutyToBackend(false);

            sendLoggedInToBackend(0);

            try {
                Intent stop = new Intent(this, LocationPingService.class);
                stop.setAction(LocationPingService.ACTION_STOP);
                startService(stop);
            } catch (Exception ignored) {}
            try { stopService(new Intent(this, LocationPingService.class)); } catch (Exception ignored) {}
            try { AssignmentWatcher.get().stop(); } catch (Exception ignored) {}

            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .remove(KEY_ACTIVE_TX)
                    .remove(KEY_ACCEPTED_TX_IDS)
                    .remove(KEY_ASSIGNED_TX_IDS)
                    .remove("last_sent_duty")
                    .apply();

            AuthPrefs.clearLogin(this);

            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();

        } catch (Throwable t) {
            Log.e("Logout", "doLogout failed", t);
            Toast.makeText(this, "Logout failed: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            logoutInProgress = false;
        }
    }

    private boolean onNavItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.nav_history) {
            startActivity(new Intent(this, TaskHistoryActivity.class));
            if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        }

        // ✅ Logout (your same logic)
        if (id == R.id.nav_logout) {
            if (isBusyNow()) {
                Toast.makeText(this, "Finish current task before logging out.", Toast.LENGTH_SHORT).show();
                if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
                return true;
            }
            doLogout();
            if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        }

        if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
        return false;
    }


    @Override public void onBackPressed() {
        super.onBackPressed();
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        moveTaskToBack(true);
    }

    // =============================================================================================
    // Busy sync (debounced)
    // =============================================================================================
    private Integer lastBusySent = null;
    private long lastBusySentAt = 0L;
    private static final long BUSY_DEBOUNCE_MS = 1500L;

    private void syncBusyWithHomeCards() {
        long driverId = AuthPrefs.driverId(this);
        if (driverId <= 0) return;

        // ✅ BUSY should be TRUE only when ACCEPTED/ACTIVE tasks exist
        boolean hasAccepted = !getAcceptedTxIdsLocal().isEmpty();
        boolean hasActiveTx = (getActiveTransaction() > 0);

        int isBusy = (hasAccepted || hasActiveTx) ? 1 : 0;

        long now = System.currentTimeMillis();

        if (lastBusySent != null && lastBusySent == isBusy && (now - lastBusySentAt) < BUSY_DEBOUNCE_MS) return;
        if (lastBusySent != null && lastBusySent == isBusy) return;

        lastBusySent = isBusy;
        lastBusySentAt = now;

        String bearer = bearerHeader();
        if (bearer == null) return;

        ApiClient.get().create(ApiService.class)
                .updateDriverBusy(bearer, driverId, isBusy)
                .enqueue(new Callback<GenericResponse>() {
                    @Override public void onResponse(Call<GenericResponse> c, Response<GenericResponse> r) {
                        Log.d("BUSY", "sent isBusy=" + isBusy + " http=" + r.code());
                    }
                    @Override public void onFailure(Call<GenericResponse> c, Throwable t) {
                        Log.e("BUSY", "busy sync failed isBusy=" + isBusy, t);
                    }
                });
    }


    // =============================================================================================
    // BUSY LOCK (toggle + logout)
    // =============================================================================================
    private boolean isBusyNow() {
        boolean hasCards = (homeAdapter != null && homeAdapter.getItemCount() > 0);
        boolean hasActive = (getActiveTransaction() > 0);
        return hasTaskLock || hasCards || hasActive;
    }

    private void syncHavingTaskWithHome() {
        boolean has = homeAdapter != null && homeAdapter.getItemCount() > 0;
        hasTaskLock = has;
        updateUiLocks();
    }

    private void updateUiLocks() {
        boolean busy = isBusyNow();

        if (switchDuty != null) {
            switchDuty.setEnabled(!busy);
            switchDuty.setClickable(!busy);
            switchDuty.setAlpha(busy ? 0.5f : 1f);
        }

        if (navView != null && navView.getMenu() != null) {
            MenuItem logout = navView.getMenu().findItem(R.id.nav_logout);
            if (logout != null) logout.setEnabled(!busy);
        }
    }

    private void requestIgnoreBatteryOptimizationIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);

                    Toast.makeText(this,
                            "Allow battery optimization exemption for lock-screen tracking",
                            Toast.LENGTH_LONG).show();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Battery optimization request failed: " + t.getMessage());
        }
    }

    // =============================================================================================
    // Pending offer consume
    // =============================================================================================
    private void consumePendingOfferIfAny() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

        long tx = sp.getLong(KEY_PENDING_TX, 0L);
        int secs = sp.getInt(KEY_PENDING_SECS, 0);
        long at = sp.getLong(KEY_PENDING_AT, 0L);

        if (tx <= 0) return;

        long now = System.currentTimeMillis();
        boolean expired = (at > 0 && (now - at) > PENDING_TTL_MS);

        // clear pending saved values
        sp.edit()
                .remove(KEY_PENDING_TX)
                .remove(KEY_PENDING_SECS)
                .remove(KEY_PENDING_AT)
                .apply();

        if (expired) return;
        if (!getLocalOnDuty()) return;
        if (isAcceptedLocal(tx)) return;

        // ✅ THIS IS IMPORTANT: show popup
        showPopupForTxId(tx, secs);
    }


    // =============================================================================================
    // Offer timer prefs (per tx)
    // =============================================================================================
    private static final String KEY_OFFER_AT_PREFIX   = "offer_at_";
    private static final String KEY_OFFER_SECS_PREFIX = "offer_secs_";
    private static final long   OFFER_TTL_GUARD_MS    = 5 * 60 * 1000L;

    private void saveOfferTimer(long txId, int secs) {
        if (txId <= 0 || secs <= 0) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_OFFER_AT_PREFIX + txId, System.currentTimeMillis())
                .putInt(KEY_OFFER_SECS_PREFIX + txId, secs)
                .apply();
    }

    private int getOfferRemainingSecs(long txId) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        long at = sp.getLong(KEY_OFFER_AT_PREFIX + txId, 0L);
        int secs = sp.getInt(KEY_OFFER_SECS_PREFIX + txId, 0);
        if (at <= 0 || secs <= 0) return 0;

        long now = System.currentTimeMillis();
        long elapsedMs = now - at;

        if (elapsedMs > OFFER_TTL_GUARD_MS) return 0;

        int left = (int) Math.ceil((secs * 1000L - elapsedMs) / 1000.0);
        return Math.max(left, 0);
    }

    private void clearOfferTimer(long txId) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit()
                .remove(KEY_OFFER_AT_PREFIX + txId)
                .remove(KEY_OFFER_SECS_PREFIX + txId)
                .apply();
    }

    public static void persistOfferToPrefs(Context ctx, long txId, int secs) {
        if (ctx == null || txId <= 0) return;

        Context app = ctx.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREFS, MODE_PRIVATE);

        HashSet<String> set = new HashSet<>(sp.getStringSet(KEY_ASSIGNED_TX_IDS, new HashSet<>()));
        set.add(String.valueOf(txId));

        int s = (secs > 0) ? secs : 0;


        long now = System.currentTimeMillis();

        sp.edit()
                .putStringSet(KEY_ASSIGNED_TX_IDS, set)
                .putLong(KEY_OFFER_AT_PREFIX + txId, now)
                .putInt(KEY_OFFER_SECS_PREFIX + txId, s)
                .apply();
    }

    private void savePendingOffer(long txId, int secs) {
        if (txId <= 0 || secs <= 0) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_PENDING_TX, txId)
                .putInt(KEY_PENDING_SECS, secs)
                .putLong(KEY_PENDING_AT, System.currentTimeMillis())
                .apply();
    }

    private void playOfferSound() {
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);

            if (am == null) return;

            // Respect Silent / DND
            if (am.getRingerMode() != android.media.AudioManager.RINGER_MODE_NORMAL) return;

            android.media.MediaPlayer mp =
                    android.media.MediaPlayer.create(this, R.raw.notify_common);

            if (mp == null) return;

            mp.setOnCompletionListener(player -> {
                try { player.release(); } catch (Exception ignored) {}
            });

            mp.start();
        } catch (Exception e) {
            Log.e("SOUND", "playOfferSound failed", e);
        }
    }
    private void vibrateOffer() {
        try {
            android.os.Vibrator v =
                    (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null) return;

            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(
                        300,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE
                ));
            } else {
                v.vibrate(300);
            }
        } catch (Exception ignored) {}
    }
    // HomeActivity.java (class level)
    private final BroadcastReceiver dutyChangedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            boolean on = i.getBooleanExtra(AblyPushManager.EXTRA_ON, false);

            setLocalOnDuty(on); // keep prefs in sync

            if (switchDuty != null) {
                switchDuty.setOnCheckedChangeListener(null);
                switchDuty.setChecked(on);
                switchDuty.setOnCheckedChangeListener(dutyToggleListener);
            }

            // optional: start/stop tracking based on server duty change
            if (on) {
                if (hasForegroundPerms()) ensureTrackingService();
            } else {
                try { stopService(new Intent(HomeActivity.this, LocationPingService.class)); } catch (Exception ignored) {}
            }
        }
    };
    private void playUnassignSound() {
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            // Respect Silent / DND (same logic as your offer sound)
            if (am.getRingerMode() != android.media.AudioManager.RINGER_MODE_NORMAL) return;

            android.media.MediaPlayer mp =
                    android.media.MediaPlayer.create(this, R.raw.un_rem);
            if (mp == null) return;

            mp.setOnCompletionListener(player -> {
                try { player.release(); } catch (Exception ignored) {}
            });

            mp.start();
        } catch (Exception e) {
            Log.e("SOUND", "playUnassignSound failed", e);
        }
    }
    private void ensureAdminChannel() {
        if (Build.VERSION.SDK_INT < 26) return;

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // ✅ Force recreate (good for testing)
        NotificationChannel existing = nm.getNotificationChannel(CH_ADMIN);
        if (existing != null) {
            nm.deleteNotificationChannel(CH_ADMIN);
        }

        Uri soundUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.un_rem);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel ch = new NotificationChannel(
                CH_ADMIN,
                "Admin Updates",
                NotificationManager.IMPORTANCE_HIGH
        );
        ch.enableVibration(true);
        ch.setSound(soundUri, attrs);

        nm.createNotificationChannel(ch);
    }

    private void showAdminNotification(long txId, @NonNull String message) {
        ensureAdminChannel();

        if (Build.VERSION.SDK_INT >= 33 &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted - cannot show admin notification");
            return;
        }

        // Tap notification opens Home (or TaskDetail if you prefer)
        Intent tap = new Intent(this, HomeActivity.class);
        tap.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = (int) (System.currentTimeMillis() & 0x7fffffff);

        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                this,
                reqCode,
                tap,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0)
        );

        android.net.Uri soundUri = android.net.Uri.parse(
                "android.resource://" + getPackageName() + "/" + R.raw.un_rem
        );

        androidx.core.app.NotificationCompat.Builder b =
                new androidx.core.app.NotificationCompat.Builder(this, CH_ADMIN)
                        .setSmallIcon(R.drawable.ic_notifications_24) // ✅ your notif icon
                        .setContentTitle("VERDI")
                        .setContentText(message)
                        .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(message))
                        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE) // SMS style
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pi)
                        .setSound(soundUri);

        androidx.core.app.NotificationManagerCompat.from(this)
                .notify(NOTIF_ADMIN_ID + (int)(txId % 1000), b.build()); // unique per tx
    }
    private boolean shouldSuppressAdmin(long txId) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        long lastTx = sp.getLong(KEY_SUPPRESS_ADMIN_TX, 0L);
        long at = sp.getLong(KEY_SUPPRESS_ADMIN_AT, 0L);
        long now = System.currentTimeMillis();
        return (lastTx == txId && at > 0 && (now - at) < SUPPRESS_ADMIN_TTL_MS);
    }

    private void markSuppressAdmin(long txId) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_SUPPRESS_ADMIN_TX, txId)
                .putLong(KEY_SUPPRESS_ADMIN_AT, System.currentTimeMillis())
                .apply();
    }
    private static final String KEY_EXPIRY_TOKEN_PREFIX = "expiry_token_";

    private long bumpExpiryToken(long txId) {
        long token = System.currentTimeMillis();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_EXPIRY_TOKEN_PREFIX + txId, token)
                .apply();
        return token;
    }

    private long getExpiryToken(long txId) {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(KEY_EXPIRY_TOKEN_PREFIX + txId, 0L);
    }



}

package com.example.loginapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.loginapp.net.ApiClient;
import com.example.loginapp.net.ApiService;
import com.example.loginapp.net.model.GenericResponse;
import com.example.loginapp.net.model.TaskDetailsResponse;
import com.example.loginapp.net.model.TaskPhase;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.navigation.NavigationView;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.Priority;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

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

import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;

public class HomeActivity extends AppCompatActivity {

    // Tracks which txIds we've already counted to avoid double writes
    private final java.util.Set<Long> alreadyCountedCompleted = new java.util.HashSet<>();
    // Skip the "diff" logic on the very first snapshot to avoid false-positives
    private boolean taskArraysFirstLoad = true;


    private volatile boolean hasTaskLock = false;

    private com.google.firebase.firestore.ListenerRegistration fcmTxReg;
    private Long lastProcessedFcmTxId = null;
    private boolean fcmPopupShowing = false;
    private android.os.CountDownTimer fcmPopupTimer;
    private com.google.firebase.firestore.ListenerRegistration txIdMirrorReg;
    private com.google.firebase.firestore.ListenerRegistration taskArraysReg;
    private final java.util.Set<Long> lastActiveIds = new java.util.HashSet<>();
    private final java.util.Set<Long> lastAssignedIds = new java.util.HashSet<>();
    private com.google.firebase.firestore.ListenerRegistration isOnlineReg;
    private volatile boolean onlineAllowed = true;
    private Boolean lastOnlineAllowed = null;

    private void setIsOnlineImmediate(boolean val) {
        DocumentReference d = driverDoc();
        DocumentReference p = presenceDoc();
        if (d == null || p == null) return;
        long driverId = currentDriverId();
        Map<String, Object> m = new HashMap<>();
        m.put("isOnline", val);
        m.put("online_updated_at", FieldValue.serverTimestamp());
        m.put("driver_id", driverId);
        d.set(m, SetOptions.merge());
        p.set(m, SetOptions.merge());
    }

    private final android.widget.CompoundButton.OnCheckedChangeListener dutyToggleListener = (btn, on) -> {
        setLocalOnDuty(on);
        if (!on) {
            setIsOnlineImmediate(false);
            goOffDuty();
            return;
        }
        if (!hasForegroundPerms()) {
            requestForegroundPerms();
            btn.setChecked(false);
            setLocalOnDuty(false);
            return;
        }

        if (!onlineAllowed) {
            setIsOnlineImmediate(true); // flip Firestore gate immediately
        }
        ensureLocationSettingsThenOnDuty();
    };

    private boolean coerceOnline(Object v, boolean defaultVal) {
        if (v == null) return defaultVal;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(java.util.Locale.US);
            return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on");
        }
        return defaultVal;
    }

    private void applyIsOnlineGate(boolean allowed) {
        boolean risingEdge = (lastOnlineAllowed != null && !lastOnlineAllowed && allowed);
        lastOnlineAllowed = allowed;
        onlineAllowed = allowed;

        runOnUiThread(() -> {
            if (switchDuty == null) return;
            switchDuty.setOnCheckedChangeListener(null);
            switchDuty.setEnabled(true);
            switchDuty.setClickable(true);
            switchDuty.setAlpha(1f);

            if (!allowed) {
                if (switchDuty.isChecked()) {
                    switchDuty.setChecked(false);
                }
                if (getLocalOnDuty()) {
                    setLocalOnDuty(false);
                    goOffDuty();
                }
            } else {
                boolean localOn = getLocalOnDuty();
                if (switchDuty.isChecked() != localOn) {
                    switchDuty.setChecked(localOn);
                }

                // 🚀 Auto-promote on rising edge (isOnline: false -> true)
                if (risingEdge && !localOn) {
                    setLocalOnDuty(true);
                    switchDuty.setChecked(true);
                    if (hasForegroundPerms()) {
                        ensureLocationSettingsThenOnDuty(); // -> becameOnDuty() -> updateFirestoreDutyState(true)
                    } else {
                        Toast.makeText(this, "Location permission needed to go On Duty.", Toast.LENGTH_SHORT).show();
                    }
                }
            }

            switchDuty.setOnCheckedChangeListener(dutyToggleListener);
            switchDuty.jumpDrawablesToCurrentState();
            switchDuty.invalidate();
        });
    }

    private static final String TAG = "OnDuty";
    private static final int REQ_PERMS = 2001; // location + notifications
    private static final int REQ_RESOLVE_LOC = 7001; // turn-on-location dialog

    private static final String PREFS = "verdi_prefs";
    private static final String KEY_ACTIVE_TX = "active_txid";
    private static final String KEY_ACCEPTED_TX_IDS = "accepted_tx_ids_set";
    private static final String KEY_ON_DUTY = "on_duty_toggle_state";

    private static final String ACTION_TASK_PHASE = "com.example.loginapp.ACTION_TASK_PHASE";

    private DrawerLayout drawerLayout;
    private NavigationView navView;
    private Switch switchDuty;

    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private ApiService api;

    private RecyclerView rvHomeTasks;
    private View emptyState;
    private RecyclerView.Adapter<?> homeAdapter;

    private TextView tvDate;

    private void updateDateText() {
        String text;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("MMMM dd", java.util.Locale.getDefault());
            text = java.time.LocalDate.now().format(fmt); // e.g. “September 24”
        } else {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMMM dd", java.util.Locale.getDefault());
            text = sdf.format(new java.util.Date());
        }
        tvDate.setText(text);
    }

    private final List<TaskItem> acceptedTasks = new ArrayList<>();
    private final Set<Long> completedTransactions = new HashSet<>();
    private boolean txReceiverRegistered = false;

    public enum Phase {
        PICKUP_STARTED, PICKUP_ARRIVED, PICKUP_COMPLETED, DELIVERY_ARRIVED, DELIVERY_COMPLETED
    }

    final Map<Long, Phase> phaseMap = new HashMap<>();

    private DocumentReference driverDoc() {
        long id = AuthPrefs.driverId(this);
        if (id <= 0) return null;
        return db.collection("drivers").document(String.valueOf(id));
    }

    private DocumentReference presenceDoc() {
        DocumentReference d = driverDoc();
        return d == null ? null : d.collection("presence").document("current");
    }

    private long currentDriverId() {
        return AuthPrefs.driverId(this);
    }

    private final android.content.BroadcastReceiver txAcceptedReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context ctx, Intent i) {
            if (i != null && i.hasExtra("accepted_tx")) {
                long tx = i.getLongExtra("accepted_tx", 0);
                if (tx > 0) {
                    setActiveTransaction(tx);

                    SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                    HashSet<String> set = new HashSet<>(sp.getStringSet(KEY_ACCEPTED_TX_IDS, new HashSet<>()));
                    set.add(String.valueOf(tx));
                    sp.edit().putStringSet(KEY_ACCEPTED_TX_IDS, set).apply();

                    addActiveTxFirestore(tx); // will also mark route start
                    seedDriverRouteArrayIfMissing(tx); // optional safeguard
                    fetchAndAddTaskRows(tx);

                    refreshList();
                    syncHavingTaskWithHome();
                    refreshPhasesFromServer();
                }
            }
        }
    };

    private final android.content.BroadcastReceiver phaseReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context ctx, Intent i) {
            if (i == null) return;
            long tx = i.getLongExtra("transaction_id", 0);
            String p = i.getStringExtra("phase");
            if (tx > 0 && p != null) {
                try {
                    Phase phase = Phase.valueOf(p);
                    phaseMap.put(tx, phase);
                    if (phase == Phase.DELIVERY_COMPLETED) {
                        // mark completed + remove
                        markRouteComplete(tx);
                        removeTransactionEverywhere(tx);
                        removeActiveTxFirestore(tx);
                        cleanupCompletedTx(tx);  // NEW

                    } else {
                        refreshList();
                    }
                } catch (IllegalArgumentException ignore) { }
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        drawerLayout = findViewById(R.id.drawerLayout);
        navView = findViewById(R.id.navigationView);

        tvDate = findViewById(R.id.tvDate);

        TextView tvDrawerTitle = findViewById(R.id.tvDrawerTitle);

        String name = AuthPrefs.name(this);        // e.g., "Ali Rahman"
        String username = AuthPrefs.username(this); // e.g., "driver1"
        long driverId = AuthPrefs.driverId(this);   // e.g., 1

        String who;
        if (name != null && !name.trim().isEmpty()) {
            who = name.trim();
        } else if (username != null && !username.trim().isEmpty()) {
            who = username.trim();
        } else if (driverId > 0) {
            who = "Driver " + driverId;            // fallback: "Driver 1"
        } else {
            who = "Driver";                         // ultimate fallback
        }

        tvDrawerTitle.setText("VERDI - " + who);

        updateDateText(); // set once

        handlePushIntent(getIntent());

        ImageView ivMenu = findViewById(R.id.ivMenu);
        if (ivMenu != null) ivMenu.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        ImageView ivClose = findViewById(R.id.ivClose);
        if (ivClose != null) ivClose.setOnClickListener(v -> drawerLayout.closeDrawer(GravityCompat.START));

        if (navView != null) {
            navView.setNavigationItemSelectedListener(this::onNavItemSelected);
        }

        // findViewById(R.id.btnAddTask).setOnClickListener(v -> openTasksBottomSheet());

        rvHomeTasks = findViewById(R.id.rvHomeTasks);
        emptyState = findViewById(R.id.emptyState);
        rvHomeTasks.setLayoutManager(new LinearLayoutManager(this));

        homeAdapter = new CombinedTasksAdapter(this, acceptedTasks, this::onStartCombinedClicked);
        rvHomeTasks.setAdapter(homeAdapter);

        switchDuty = findViewById(R.id.switchDuty);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        api = ApiClient.get().create(ApiService.class);

        ensureAssignedTransactionsArray();
        normalizeAssignedTransactionsTypes();
        logProject();

        boolean wasOn = getLocalOnDuty();
        switchDuty.setOnCheckedChangeListener(null);
        switchDuty.setChecked(wasOn);
        switchDuty.setOnCheckedChangeListener((btn, on) -> {
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
        });

        if (wasOn) restoreOnDutyIfNeeded(); // will also reload txs
        updateHomeListVisibility();

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != getPackageManager().PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    1234
            );
        }
    }

    @Override protected void onStart() {
        super.onStart();
        AssignmentWatcher.get().attach(this);
    }

    @Override protected void onStop() {
        super.onStop();
        AssignmentWatcher.get().detach(this);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onResume() {
        super.onResume();

        updateDateText();

        startMirrorTransactionsIdToAssigned();
        startWatchingTaskArrays();
        scrubAssignedAgainstActive();

        startWatchingFcmTransactionId();

        if (!txReceiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                        txAcceptedReceiver,
                        new android.content.IntentFilter(TaskPopup.ACTION_TX_ACCEPTED),
                        android.content.Context.RECEIVER_NOT_EXPORTED
                );
            } else {
                registerReceiver(
                        txAcceptedReceiver,
                        new android.content.IntentFilter(TaskPopup.ACTION_TX_ACCEPTED)
                );
            }
            txReceiverRegistered = true;
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                    phaseReceiver,
                    new android.content.IntentFilter(ACTION_TASK_PHASE),
                    android.content.Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            registerReceiver(
                    phaseReceiver,
                    new android.content.IntentFilter(ACTION_TASK_PHASE)
            );
        }

        boolean on = getLocalOnDuty();
        switchDuty.setOnCheckedChangeListener(null);
        if (switchDuty.isChecked() != on) {
            switchDuty.setChecked(on);
        }
        switchDuty.setOnCheckedChangeListener(dutyToggleListener);

        if (on) restoreOnDutyIfNeeded();
        if (getLocalOnDuty()) {
            loadActiveTransactionsFromFirestore(() -> {
                refreshHomeFromPrefs();
                refreshPhasesFromServer();
                updateHomeListVisibility();
                ensureTrackingService();
            });
        } else {
            refreshHomeFromPrefs();
            refreshPhasesFromServer();
            updateHomeListVisibility();
        }

        if (isOnlineReg != null) {
            try { isOnlineReg.remove(); } catch (Exception ignored) {}
            isOnlineReg = null;
        }

        com.google.firebase.firestore.DocumentReference driverRef = driverDoc();
        if (driverRef != null) {
            isOnlineReg = driverRef.addSnapshotListener((snap, err) -> {
                if (err != null || snap == null || !snap.exists()) return;
                boolean allowed = coerceOnline(snap.get("isOnline"), true);
                applyIsOnlineGate(allowed);
            });
            driverRef.get().addOnSuccessListener(snap -> {
                boolean allowed = (snap != null && snap.exists()) ? coerceOnline(snap.get("isOnline"), true) : true;
                applyIsOnlineGate(allowed);
            });
        }
    }

    @Override protected void onPause() {
        super.onPause();

        stopWatchingTaskArrays();

        stopWatchingFcmTransactionId();

        if (txReceiverRegistered) {
            try { unregisterReceiver(txAcceptedReceiver); } catch (Exception ignored) {}
            txReceiverRegistered = false;
        }
        try { unregisterReceiver(phaseReceiver); } catch (Exception ignored) {}

        if (isOnlineReg != null) {
            try { isOnlineReg.remove(); } catch (Exception ignored) {}
            isOnlineReg = null;
        }
        if (txIdMirrorReg != null) {
            try { txIdMirrorReg.remove(); } catch (Exception ignored) {}
            txIdMirrorReg = null;
        }
    }


    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePushIntent(intent);
        if (intent != null) {
            long tx = intent.getLongExtra("push_tx_id", 0L);
            int secs = intent.getIntExtra("push_tx_secs", 0); // 0 => default

            if (tx > 0) {
                if (fcmPopupShowing && fcmPopupDialog != null && fcmPopupDialog.isShowing()
                        && fcmCurrentTxId == tx) {
                    if (secs > 0 && secs != fcmCurrentSecs) startOrRestartFcmTimer(secs);
                } else {
                    lastProcessedFcmTxId = tx;
                    showPopupForTxId(tx, (secs > 0) ? secs : 5);
                }
            }
        }

        if (intent != null && intent.hasExtra("completed_tx")) {
            long tx = intent.getLongExtra("completed_tx", 0);
            if (tx > 0) {
                completedTransactions.add(tx);
                markRouteComplete(tx);
                removeTransactionEverywhere(tx);
                Toast.makeText(this, "Transaction #" + tx + " completed", Toast.LENGTH_SHORT).show();
            }
        }
    }

    static class TaskItem {
        final long serverId;
        final long transactionId;
        final String title, address, phoneE164;
        final double lat, lng;
        final boolean isPickup;
        final long pickupId, deliveryId;
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

    private void onStartCombinedClicked(long txId, TaskItem pickup, TaskItem deliveryOrNull) {
        startTaskIfNeeded(pickup != null ? pickup : deliveryOrNull);
    }

    private void openTasksBottomSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheetView = LayoutInflater.from(this)
                .inflate(R.layout.layout_bottom_tasks, null, false);
        dialog.setContentView(sheetView);

        View bs = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bs != null) {
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bs);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
        }

        long activeTx = getActiveTransaction();
        List<TaskItem> visible = new ArrayList<>();

        if (activeTx != 0) {
            TaskItem pickup = null, delivery = null;
            for (TaskItem t : acceptedTasks) {
                if (t.transactionId == activeTx) {
                    if (t.isPickup) pickup = t; else delivery = t;
                }
            }
            TaskItem show = (pickup != null) ? pickup : delivery;
            if (show != null) visible.add(show);
        }

        TextView tvTitle = sheetView.findViewById(R.id.tvTitle);
        if (tvTitle != null) tvTitle.setText("Current Task (" + visible.size() + ")");

        RecyclerView rv = sheetView.findViewById(R.id.recyclerTasks);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new SimpleRowAdapter(this, visible, dialog, this::onStartClicked));

        dialog.show();
    }

    static class SimpleRowAdapter extends RecyclerView.Adapter<SimpleRowAdapter.VH> {
        private final HomeActivity activity;
        private final List<TaskItem> data;
        private final BottomSheetDialog dialog;
        private final OnTaskAction listener;

        interface OnTaskAction {
            void onStartClicked(TaskItem item);
        }

        SimpleRowAdapter(HomeActivity a, List<TaskItem> d, BottomSheetDialog dialog, OnTaskAction l) {
            this.activity = a;
            this.data = d;
            this.dialog = dialog;
            this.listener = l;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_task_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            TaskItem item = data.get(i);

            h.title.setText(item.title);
            h.sub.setText(item.address);

            if (!item.isPickup) {
                h.btnStarted.setVisibility(View.GONE);
            } else {
                h.btnStarted.setVisibility(View.VISIBLE);

                Phase p = activity.phaseMap.getOrDefault(item.transactionId, Phase.PICKUP_STARTED);
                String label;
                switch (p) {
                    case PICKUP_STARTED: label = "Start"; break;
                    case PICKUP_ARRIVED: label = "Complete Pickup"; break;
                    case PICKUP_COMPLETED: label = "Start Delivery"; break;
                    case DELIVERY_ARRIVED: label = "Complete Delivery"; break;
                    case DELIVERY_COMPLETED:
                    default: label = "Done"; break;
                }
                h.btnStarted.setText(label);

                boolean green = (p == Phase.PICKUP_STARTED || p == Phase.DELIVERY_COMPLETED);
                int bg = ContextCompat.getColor(activity, green ? R.color.verdi_green_bg : R.color.verdi_red_bg);
                int fg = ContextCompat.getColor(activity, green ? R.color.verdi_green_text : R.color.verdi_red_text);
                ViewCompat.setBackgroundTintList(h.btnStarted, ColorStateList.valueOf(bg));
                h.btnStarted.setTextColor(fg);

                h.btnStarted.setOnClickListener(v -> {
                    if (dialog != null && dialog.isShowing()) dialog.dismiss();
                    if (listener != null) listener.onStartClicked(item);
                });
            }
        }

        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView title, sub;
            final android.widget.Button btnStarted;
            VH(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.tvTaskTitle);
                sub = itemView.findViewById(R.id.tvTaskSub);
                btnStarted = itemView.findViewById(R.id.btnStarted);
            }
        }
    }

    private void onStartClicked(TaskItem item) {
        startTaskIfNeeded(item);
    }

    private void startTaskIfNeeded(@NonNull TaskItem item) {
        if (!getLocalOnDuty()) {
            Toast.makeText(this, "Go On Duty first", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d(TAG, "ensureStartTracking -> tx=" + item.transactionId);
        ensureStartTracking(item.transactionId);
        final String bearer = AuthPrefs.bearer(this);
        api.getTaskDetails(bearer, item.transactionId)
                .enqueue(new Callback<TaskDetailsResponse>() {
                    @Override public void onResponse(Call<TaskDetailsResponse> call, Response<TaskDetailsResponse> res) {
                        if (!res.isSuccessful() || res.body() == null || !res.body().success || res.body().data == null) {
                            // Couldn’t confirm from server — proceed to details so driver can continue
                            launchTaskDetailsWith(item);
                            return;
                        }
                        TaskDetailsResponse.Data d = res.body().data;

                        Phase serverPhase = derivePhase(d);
                        phaseMap.put(item.transactionId, serverPhase);
                        refreshList();

                        String pickupStatus = safe(d.pickup_task != null ? d.pickup_task.task_status : null);
                        boolean notStartedYet = pickupStatus.isEmpty()
                                || "accepted".equalsIgnoreCase(pickupStatus)
                                || "assigned".equalsIgnoreCase(pickupStatus);

                        if (serverPhase == Phase.PICKUP_STARTED && notStartedYet) {
                            updatePhase(item.serverId, TaskPhase.PICKUP_STARTED,
                                    () -> launchTaskDetailsWith(item),
                                    () -> {
                                        Toast.makeText(HomeActivity.this, "Start failed, opening details", Toast.LENGTH_SHORT).show();
                                        launchTaskDetailsWith(item);
                                    });
                        } else {
                            launchTaskDetailsWith(item);
                        }
                    }
                    @Override public void onFailure(Call<TaskDetailsResponse> call, Throwable t) {
                        // Network error — open details so the flow can continue
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
        String bearer = AuthPrefs.bearer(this);
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

    private void acceptNext(TaskDetailsResponse.Data d, List<Long> ids, int idx, String bearer,
                            BottomSheetDialog dialog, boolean[] acceptedAny) {
        if (idx >= ids.size()) {
            if (acceptedAny[0]) {
                setActiveTransaction(d.id);

                setActiveTransaction(d.id);
                writeDriverIdToFirestore();
                sendDriverIdToApi();
                addActiveTxFirestore(d.id);
                markRouteStart(d.id);
                seedDriverRouteArrayIfMissing(d.id);

                refreshList();
                syncHavingTaskWithHome();
                refreshPhasesFromServer();
                Toast.makeText(this, "Task accepted", Toast.LENGTH_SHORT).show();
                openTasksBottomSheet();
            } else {
                Toast.makeText(this, "Accept failed", Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
            return;
        }

        long rowId = ids.get(idx);
        api.updateTaskStatus(bearer, rowId, "accepted")
                .enqueue(new Callback<GenericResponse>() {
                    @Override public void onResponse(Call<GenericResponse> call, Response<GenericResponse> res) {
                        if (res.isSuccessful() && res.body() != null && res.body().success) {
                            acceptedAny[0] = true;
                        }
                        acceptNext(d, ids, idx + 1, bearer, dialog, acceptedAny);
                    }
                    @Override public void onFailure(Call<GenericResponse> call, Throwable t) {
                        acceptNext(d, ids, idx + 1, bearer, dialog, acceptedAny);
                    }
                });
    }

    private void addAcceptedRowsFromApi(TaskDetailsResponse.Data d) {
        long pickupId = (d.pickup_task != null) ? d.pickup_task.id : 0;
        long deliveryId = (d.delivery_task != null) ? d.delivery_task.id : 0;
        String payType = d.vendor_payment_type;
        String orderAmt = d.order_amount;
        String orderId = d.order_id;

        java.util.function.Predicate<Long> notPresent = id -> {
            for (TaskItem t : acceptedTasks) if (t.serverId == id) return false;
            return true;
        };

        if (d.pickup_task != null && notPresent.test(d.pickup_task.id)) {
            double plat = safeParse(d.pickup_task.lat);
            double plng = safeParse(d.pickup_task.lng);
            acceptedTasks.add(new TaskItem(
                    d.pickup_task.id, d.id,
                    "Pickup: " + d.pickup_task.address, d.pickup_task.address, d.pickup_task.phone,
                    plat, plng, true, pickupId, deliveryId, payType, orderAmt, orderId
            ));
        }

        if (d.delivery_task != null && notPresent.test(d.delivery_task.id)) {
            double dlat = safeParse(d.delivery_task.lat);
            double dlng = safeParse(d.delivery_task.lng);
            acceptedTasks.add(new TaskItem(
                    d.delivery_task.id, d.id,
                    "Dropoff: " + d.delivery_task.address, d.delivery_task.address, d.delivery_task.phone,
                    dlat, dlng, false, pickupId, deliveryId, payType, orderAmt, orderId
            ));
        }
    }

    private static double safeParse(String s) {
        try { return s == null ? Double.NaN : Double.parseDouble(s); }
        catch (Exception e) { return Double.NaN; }
    }

    private void ensureAuthThenOnDuty() {
        Runnable go = () -> {
            setIsOnlineImmediate(true); // <-- write to Firestore right away
            becameOnDuty(); // your existing flow (updates duty state, starts ping, etc.)
        };
        if (auth.getCurrentUser() == null) {
            auth.signInAnonymously()
                    .addOnSuccessListener(r -> go.run())
                    .addOnFailureListener(e -> {
                        Toast.makeText(this, "Sign-in failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        switchDuty.setOnCheckedChangeListener(null);
                        switchDuty.setChecked(false);
                        setLocalOnDuty(false);
                        switchDuty.setOnCheckedChangeListener((btn, on) -> {
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
                        });
                    });
        } else {
            go.run();
        }
    }

    private void becameOnDuty() {
        setLogoutFlag(false);
        ensureHavingTaskInitializedFalse();
        upsertDriverProfile();

        setLocalOnDuty(true);                      // <- persist local
        updateFirestoreDutyState(true);            // writes isOnline=true, onDuty=true, duty_state="ON_DUTY"
        sendDutyToBackend(true);

        ensureTrackingService();                   // <- always (re)start the FGS

        AssignmentWatcher.get().start(getApplicationContext());
        AssignmentWatcher.get().attach(this);
        Toast.makeText(this, "On Duty – tracking started", Toast.LENGTH_SHORT).show();

        loadActiveTransactionsFromFirestore(() -> {
            refreshHomeFromPrefs();
            refreshList();
            refreshPhasesFromServer();
            syncHavingTaskWithHome();
        });
    }

    private void goOffDuty() {
        setLocalOnDuty(false);                     // <- persist local
        updateFirestoreDutyState(false);
        sendDutyToBackend(false);
        stopService(new Intent(this, LocationPingService.class));
        AssignmentWatcher.get().stop();
        updateHavingTaskStatus(false);
        updateHomeListVisibility();
        Toast.makeText(this, "Off Duty – tracking stopped", Toast.LENGTH_SHORT).show();
    }

    private void ensureTrackingService() {
        Intent svc = new Intent(this, LocationPingService.class);
//        svc.putExtra("driver_name",     AuthPrefs.name(this));
//        svc.putExtra("driver_username", AuthPrefs.username(this));
//        svc.putExtra("driver_phone",    AuthPrefs.phone(this));
        androidx.core.content.ContextCompat.startForegroundService(this, svc);
    }

    private void restoreOnDutyIfNeeded() {
        if (!hasForegroundPerms()) return;
        ensureLocationSettingsThenOnDuty();
    }

    private void sendDutyToBackend(boolean on) {
        long driverId = AuthPrefs.driverId(this);
        if (driverId <= 0) {
            Log.w(TAG, "No driverId; skipping update_on_duty");
            return;
        }
        String bearer = AuthPrefs.bearer(this);
        int isOnline = on ? 1 : 0;
        ApiClient.get().create(ApiService.class)
                .updateOnDuty(bearer, driverId, isOnline)
                .enqueue(new Callback<GenericResponse>() {
                    @Override public void onResponse(Call<GenericResponse> call, Response<GenericResponse> res) { }
                    @Override public void onFailure(Call<GenericResponse> call, Throwable t) { }
                });
    }

    private void updateFirestoreDutyState(boolean on) {
        DocumentReference d = driverDoc();
        DocumentReference p = presenceDoc();
        if (d == null || p == null) return;

        long driverId = currentDriverId();

        Map<String, Object> data = new HashMap<>();
        data.put("duty_state", on ? "ON_DUTY" : "OFF_DUTY");
        data.put("onDuty", on);
        data.put("isOnline", on); // explicit gate flag
        data.put("online_updated_at", FieldValue.serverTimestamp());
        data.put("duty_updated_at", FieldValue.serverTimestamp());
        data.put("driver_id", driverId);

        Map<String, Object> presence = new HashMap<>();
        presence.put("duty_state", on ? "ON_DUTY" : "OFF_DUTY");
        presence.put("onDuty", on);
        presence.put("isOnline", on);
        presence.put("online_updated_at", FieldValue.serverTimestamp());
        presence.put("duty_updated_at", FieldValue.serverTimestamp());
        presence.put("driver_id", driverId);

        d.set(data, SetOptions.merge());
        p.set(presence, SetOptions.merge());
    }

    private void upsertDriverProfile() {
        DocumentReference d = driverDoc();
        if (d == null) return;

        String name     = AuthPrefs.name(this);
        String username = AuthPrefs.username(this);
        String phone    = AuthPrefs.phone(this);       // <- from your prefs
        long driverId   = AuthPrefs.driverId(this);

        d.get().addOnSuccessListener(snap -> {
            Map<String, Object> profile = new HashMap<>();
            if (name != null && !name.isEmpty())     profile.put("name", name);
            if (username != null && !username.isEmpty()) profile.put("username", username);
            if (driverId > 0)                        profile.put("driver_id", driverId);

            String oldPhone = (snap != null && snap.exists()) ? (String) snap.get("phone") : null;
            if (phone != null && !phone.isEmpty() && !phone.equals(oldPhone)) {
                profile.put("phone", phone);
            }

            profile.put("updated_at", FieldValue.serverTimestamp());
            d.set(profile, SetOptions.merge());
        });
    }

    private String resolveBearerHeader() {
        String hdr = AuthPrefs.bearer(this); // may already be "Bearer <jwt>"
        if (hdr != null && hdr.startsWith("Bearer ")) return hdr;
        String raw = AuthPrefs.token(this);  // raw JWT
        return (raw == null || raw.isEmpty()) ? "" : ("Bearer " + raw);
    }

    private void writeFcmToFirestore(long driverId, @NonNull String token) {
        if (driverId <= 0 || token.isEmpty()) return;
        DocumentReference root = FirebaseFirestore.getInstance()
                .collection("drivers").document(String.valueOf(driverId));

        Map<String, Object> priv = new java.util.HashMap<>();
        priv.put("fcm_token", token);
        priv.put("fcm_updated_at", FieldValue.serverTimestamp());
        root.collection("private").document("device")
                .set(priv, SetOptions.merge());

        Map<String, Object> shadow = new java.util.HashMap<>();
        shadow.put("has_fcm", true);
        shadow.put("fcm_updated_at", FieldValue.serverTimestamp());
        root.set(shadow, SetOptions.merge());
    }

    private void sendDriverIdToApi() {
        if (auth.getCurrentUser() == null) return;

        long driverId = AuthPrefs.driverId(this);
        if (driverId <= 0) return;

        String firebaseUid = auth.getCurrentUser().getUid();
        String bearerHeader = resolveBearerHeader();
        String fcmToken = getSharedPreferences("verdi_prefs", MODE_PRIVATE)
                .getString("fcm_token", "");
        if (fcmToken != null && !fcmToken.isEmpty()) {
            writeFcmToFirestore(driverId, fcmToken);

            api.attachFirebase(bearerHeader, driverId, firebaseUid, fcmToken)
                    .enqueue(new Callback<GenericResponse>() {
                        @Override public void onResponse(Call<GenericResponse> call, Response<GenericResponse> res) {
                            // optionally mark as uploaded, e.g. prefs flag
                        }
                        @Override public void onFailure(Call<GenericResponse> call, Throwable t) { }
                    });
        } else {
        }
    }

    private void writeDriverIdToFirestore() {
        DocumentReference d = driverDoc();
        if (d == null) return;
        long driverId = AuthPrefs.driverId(this);
        Map<String, Object> m = new HashMap<>();
        m.put("driver_id", driverId);
        m.put("driver_id_set_at", FieldValue.serverTimestamp());
        d.set(m, SetOptions.merge());
    }

    private void syncHavingTaskWithHome() {
        boolean has = !acceptedTasks.isEmpty();
        if (!has) {
            Set<String> set = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getStringSet(KEY_ACCEPTED_TX_IDS, new HashSet<>());
            has = set != null && !set.isEmpty();
        }
        updateHavingTaskStatus(has);
    }

    private void updateHavingTaskStatus(boolean hasTask) {
        DocumentReference d = driverDoc();
        DocumentReference p = presenceDoc();
        if (d == null || p == null) return;

        long driverId = currentDriverId();

        Map<String, Object> root = new HashMap<>();
        root.put("havingtask", hasTask);
        root.put("task_updated_at", FieldValue.serverTimestamp());
        root.put("driver_id", driverId);
        d.set(root, SetOptions.merge());

        Map<String, Object> presence = new HashMap<>();
        presence.put("havingtask", hasTask);
        presence.put("task_updated_at", FieldValue.serverTimestamp());
        presence.put("driver_id", driverId);
        p.set(presence, SetOptions.merge());
    }

    private void ensureHavingTaskInitializedFalse() {
        DocumentReference d = driverDoc();
        DocumentReference p = presenceDoc();
        if (d == null || p == null) return;

        long driverId = currentDriverId();

        d.get().addOnSuccessListener(snap -> {
            Map<String, Object> rootSeed = new HashMap<>();
            boolean needsRootWrite = false;

            Boolean hv = snap.exists() ? snap.getBoolean("havingtask") : null;
            if (!snap.exists() || !snap.contains("havingtask")) {
                rootSeed.put("havingtask", hv != null ? hv : false);
                needsRootWrite = true;
            }
            if (!snap.exists() || !snap.contains("driver_id")) {
                rootSeed.put("driver_id", driverId);
                needsRootWrite = true;
            }
            if (!snap.exists() || !snap.contains("isBlocked")) {
                rootSeed.put("isBlocked", false);
                needsRootWrite = true;
            }
            if (!snap.exists() || !snap.contains("fcmtransactionsid")) {
                rootSeed.put("fcmtransactionsid", null);  // <— required line
                needsRootWrite = true;
            }
            if (!snap.exists() || !snap.contains("isOnline")) {
                rootSeed.put("isOnline", false);
                rootSeed.put("online_updated_at", FieldValue.serverTimestamp());
                needsRootWrite = true;
            }
            if (needsRootWrite) d.set(rootSeed, SetOptions.merge());

            p.get().addOnSuccessListener(ps -> {
                Map<String, Object> presSeed = new HashMap<>();
                boolean needsPresWrite = false;

                Boolean phv = ps.exists() ? ps.getBoolean("havingtask") : null;
                if (!ps.exists() || !ps.contains("havingtask")) {
                    presSeed.put("havingtask", phv != null ? phv : (hv != null ? hv : false));
                    needsPresWrite = true;
                }
                if (!ps.exists() || !ps.contains("driver_id")) {
                    presSeed.put("driver_id", driverId);
                    needsPresWrite = true;
                }
                if (!ps.exists() || !ps.contains("isBlocked")) {
                    presSeed.put("isBlocked", false);
                    needsPresWrite = true;
                }
                if (!ps.exists() || !ps.contains("isOnline")) {
                    presSeed.put("isOnline", false);
                    presSeed.put("online_updated_at", FieldValue.serverTimestamp());
                    needsPresWrite = true;
                }
                if (!ps.exists() || !ps.contains("fcmtransactionsid")) {
                    presSeed.put("fcmtransactionsid", null); // <— required line
                    needsPresWrite = true;
                }
                if (needsPresWrite) p.set(presSeed, SetOptions.merge());
            });
        }).addOnFailureListener(e -> Log.w(TAG, "ensureHavingTaskInitializedFalse: " + e.getMessage()));
    }

    private void updateLastTransactionId(long txId) {
        DocumentReference d = driverDoc();
        DocumentReference p = presenceDoc();
        if (d == null || p == null || txId <= 0) return;

        long driverId = currentDriverId();

        Map<String, Object> root = new HashMap<>();
//        root.put("transactionsId", txId); // write as NUMBER
//        root.put("last_transaction_at", FieldValue.serverTimestamp());
        root.put("last_transaction_id", txId); // keep metadata only
        root.put("transactionsId", FieldValue.delete()); // ensure the trigger is gone
        root.put("driver_id", driverId);
        d.set(root, SetOptions.merge());

        Map<String, Object> presence = new HashMap<>();
        presence.put("last_transaction_id", txId); // write as NUMBER
        presence.put("last_transaction_at", FieldValue.serverTimestamp());
        presence.put("driver_id", driverId);
        p.set(presence, SetOptions.merge());
    }

    private static String safe(String s){ return s == null ? "" : s.trim(); }

    private Phase derivePhase(TaskDetailsResponse.Data d) {
        String pStat = (d.pickup_task != null) ? safe(d.pickup_task.task_status) : "";
        String dStat = (d.delivery_task != null) ? safe(d.delivery_task.task_status) : "";

        boolean pickupDone = "success".equalsIgnoreCase(pStat);
        boolean pickupArr = "arrived".equalsIgnoreCase(pStat);
        boolean deliveryDone = "success".equalsIgnoreCase(dStat);
        boolean deliveryArr = "arrived".equalsIgnoreCase(dStat);

        if (deliveryDone) return Phase.DELIVERY_COMPLETED;
        if (deliveryArr) return Phase.DELIVERY_ARRIVED;
        if (pickupDone) return Phase.PICKUP_COMPLETED;
        if (pickupArr) return Phase.PICKUP_ARRIVED;
        return Phase.PICKUP_STARTED;
    }

    private void refreshPhasesFromServer() {
        String bearer = AuthPrefs.bearer(this);
        HashSet<Long> txIds = new HashSet<>();
        for (TaskItem t : acceptedTasks) txIds.add(t.transactionId);

        for (long txId : txIds) {
            ApiClient.get().create(ApiService.class)
                    .getTaskDetails(bearer, txId)
                    .enqueue(new Callback<TaskDetailsResponse>() {
                        @Override public void onResponse(Call<TaskDetailsResponse> call, Response<TaskDetailsResponse> res) {
                            if (res.isSuccessful() && res.body()!=null && res.body().success && res.body().data!=null) {
                                Phase ph = derivePhase(res.body().data);
                                if (ph == Phase.DELIVERY_COMPLETED) {
                                    markRouteComplete(txId);
                                    removeTransactionEverywhere(txId);
                                    removeActiveTxFirestore(txId);
                                    cleanupCompletedTx(txId); // NEW
                                } else {
                                    phaseMap.put(txId, ph);
                                    refreshList();
                                }
                            }
                        }
                        @Override public void onFailure(Call<TaskDetailsResponse> call, Throwable t) { }
                    });
        }
    }

    private void setLogoutFlag(boolean loggedOut) {
        DocumentReference d = driverDoc();
        DocumentReference p = presenceDoc();
        if (d == null || p == null) return;

        long driverId = currentDriverId();

        Map<String, Object> root = new HashMap<>();
        root.put("isLoggedout", loggedOut);
        root.put("logout_updated_at", FieldValue.serverTimestamp());
        root.put("driver_id", driverId);
        d.set(root, SetOptions.merge());

        Map<String, Object> presence = new HashMap<>();
        presence.put("isLoggedout", loggedOut);
        presence.put("logout_updated_at", FieldValue.serverTimestamp());
        presence.put("driver_id", driverId);
        p.set(presence, SetOptions.merge());
    }

    private void removeTransactionEverywhere(long txId) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        HashSet<String> set = new HashSet<>(sp.getStringSet(KEY_ACCEPTED_TX_IDS, new HashSet<>()));
        set.remove(String.valueOf(txId));
        sp.edit().putStringSet(KEY_ACCEPTED_TX_IDS, set).apply();

        if (getActiveTransaction() == txId) {
            setActiveTransaction(0L);
        }
        for (int i = acceptedTasks.size() - 1; i >= 0; i--) {
            if (acceptedTasks.get(i).transactionId == txId) {
                acceptedTasks.remove(i);
            }
        }
        phaseMap.remove(txId);
        refreshList();
        syncHavingTaskWithHome();
    }

    private void removeActiveTxFirestore(long txId) {
        DocumentReference d = driverDoc();
        if (d == null) return;
        d.update(
                        "active_transactions", FieldValue.arrayRemove(txId),
                        "transactionsId", FieldValue.delete(),
                        "task_updated_at", FieldValue.serverTimestamp(),
                        "driver_id", currentDriverId()
                ).addOnSuccessListener(v -> syncHavingTaskWithHome())
                .addOnFailureListener(e -> Log.w(TAG, "removeActiveTxFirestore: " + e.getMessage()));
    }

    private long getActiveTransaction() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_ACTIVE_TX, 0L);
    }

    private void setActiveTransaction(long tx) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_ACTIVE_TX, tx).apply();
    }

    private boolean hasForegroundPerms() {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean notifOk = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        return (fine || coarse) && notifOk;
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
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{ Manifest.permission.ACCESS_BACKGROUND_LOCATION },
                    2002
            );
        }
    }

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
            Toast.makeText(this, "Please enable Location, then return and toggle On Duty again.", Toast.LENGTH_LONG).show();
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
                    switchDuty.setOnCheckedChangeListener((btn, on) -> {
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
                    });
                }
            }
        }
    }

    @Override public void onRequestPermissionsResult(int c, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(c, p, r);
        if (c == REQ_PERMS && hasForegroundPerms()) {
            Toast.makeText(this, "Permission granted. Toggle On Duty to start.", Toast.LENGTH_SHORT).show();
        }
    }

    private void logProject() {
        FirebaseOptions o = FirebaseApp.getInstance().getOptions();
        Log.d(TAG, "ProjectId=" + o.getProjectId() + ", AppId=" + o.getApplicationId());
    }

    private void refreshHomeFromPrefs() {
        Set<String> set = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getStringSet(KEY_ACCEPTED_TX_IDS, new HashSet<>());
        acceptedTasks.clear();
        if (set == null || set.isEmpty()) {
            refreshList();
            syncHavingTaskWithHome();
            return;
        }
        for (String s : set) {
            long tx;
            try { tx = Long.parseLong(s); } catch (Exception e) { continue; }
            fetchAndAddTaskRows(tx);
        }
    }

    private void fetchAndAddTaskRows(long txId) {
        String bearer = AuthPrefs.bearer(this);
        ApiClient.get().create(ApiService.class)
                .getTaskDetails(bearer, txId)
                .enqueue(new Callback<TaskDetailsResponse>() {
                    @Override public void onResponse(Call<TaskDetailsResponse> call, Response<TaskDetailsResponse> res) {
                        if (res.isSuccessful() && res.body() != null && res.body().success && res.body().data != null) {
                            addAcceptedRowsFromApi(res.body().data);
                            refreshList();
                            syncHavingTaskWithHome();
                            phaseMap.put(txId, derivePhase(res.body().data));
                            refreshList();
                        }
                    }
                    @Override public void onFailure(Call<TaskDetailsResponse> call, Throwable t) { }
                });
    }

    private void updateHomeListVisibility() {
        if (rvHomeTasks == null || emptyState == null || homeAdapter == null) return;
        boolean onDuty = getLocalOnDuty();
        boolean hasItems = homeAdapter.getItemCount() > 0;
        boolean showList = onDuty && hasItems;
        rvHomeTasks.setVisibility(showList ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(showList ? View.GONE : View.VISIBLE);
    }

    private boolean getLocalOnDuty() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ON_DUTY, false);
    }

    private void setLocalOnDuty(boolean on) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ON_DUTY, on).apply();
    }

    private void doLogout() {
        if (hasTaskLock) { // or check a cached 'havingtask' boolean
            Toast.makeText(this, "Finish current task before logging out.", Toast.LENGTH_SHORT).show();
            return;
        }

        setLogoutFlag(true);

        updateFirestoreDutyState(false);
        sendDutyToBackend(false);
        stopService(new Intent(this, LocationPingService.class));
        AssignmentWatcher.get().stop();
        setLocalOnDuty(false);

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .remove(KEY_ACTIVE_TX)
                .remove(KEY_ACCEPTED_TX_IDS)
                .apply();

        AuthPrefs.clear(this);
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    private void addActiveTxFirestore(long txId) {
        DocumentReference d = driverDoc();
        if (d == null) return;

        d.set(new HashMap<String,Object>() {{
            put("driver_id", currentDriverId());
        }}, SetOptions.merge());

        d.update(
                "active_transactions", FieldValue.arrayUnion(txId),
                "assigned_transactions", FieldValue.arrayRemove(txId, String.valueOf(txId)),
                "havingtask", true,
                "transactionsId", FieldValue.delete(),
                "task_updated_at", FieldValue.serverTimestamp(),
                "driver_id", currentDriverId()
        );
    }

    private void scrubAssignedAgainstActive() {
        DocumentReference d = driverDoc();
        if (d == null) return;

        d.get().addOnSuccessListener(snap -> {
            if (snap == null || !snap.exists()) return;
            HashSet<Long> active = new HashSet<>();
            Object act = snap.get("active_transactions");
            if (act instanceof List<?>) {
                for (Object o : (List<?>) act) {
                    try { active.add(Long.parseLong(String.valueOf(o).trim())); } catch (Exception ignored) {}
                }
            }
            ArrayList<Object> toRemove = new ArrayList<>();
            Object asg = snap.get("assigned_transactions");
            if (asg instanceof List<?>) {
                for (Object o : (List<?>) asg) {
                    Long v = null;
                    try { v = Long.parseLong(String.valueOf(o).trim()); } catch (Exception ignored) {}
                    if (v != null && active.contains(v)) {
                        toRemove.add(v);                 // numeric
                        toRemove.add(String.valueOf(v)); // string
                    }
                }
            }
            if (!toRemove.isEmpty()) {
                d.update("assigned_transactions", FieldValue.arrayRemove(toRemove.toArray()))
                        .addOnFailureListener(e -> Log.w(TAG, "scrubAssignedAgainstActive: " + e));
            }
        });
    }

    private void loadActiveTransactionsFromFirestore(@NonNull Runnable onDone) {
        DocumentReference d = driverDoc();
        if (d == null) {
            onDone.run();
            return;
        }
        d.get().addOnSuccessListener(snap -> {
            List<Long> list = null;
            if (snap != null && snap.exists()) {
                Object v = snap.get("active_transactions");
                if (v instanceof List) {
                    list = (List<Long>) v;
                }
            }

            HashSet<String> set = new HashSet<>();
            if (list != null) {
                for (Long tx : list) if (tx != null && tx > 0) set.add(String.valueOf(tx));
            }

            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putStringSet(KEY_ACCEPTED_TX_IDS, set)
                    .apply();

            acceptedTasks.clear();
            if (list != null) {
                for (Long tx : list) if (tx != null && tx > 0) fetchAndAddTaskRows(tx);
            }
            onDone.run();
        }).addOnFailureListener(e -> onDone.run());
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

    private void refreshList() {
        if (homeAdapter instanceof CombinedTasksAdapter) {
            ((CombinedTasksAdapter) homeAdapter).regroup();
        }
        homeAdapter.notifyDataSetChanged();
        updateHomeListVisibility();
    }

    static class CombinedTasksAdapter extends RecyclerView.Adapter<CombinedTasksAdapter.VH> {
        interface OnCombinedActionListener {
            void onStartClicked(long transactionId, @NonNull TaskItem pickup, TaskItem deliveryOrNull);
        }

        static class Combined {
            final long transactionId;
            TaskItem pickup;   // may be null
            TaskItem delivery; // may be null
            Combined(long tx) { this.transactionId = tx; }
        }

        private final HomeActivity activity;
        private final List<TaskItem> source; // original list (two rows per tx)
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
            for (TaskItem t : source) {
                Phase ph = activity.phaseMap.getOrDefault(t.transactionId, Phase.PICKUP_STARTED);
                if (ph == Phase.DELIVERY_COMPLETED) continue;

                Combined c = byTx.get(t.transactionId);
                if (c == null) {
                    c = new Combined(t.transactionId);
                    byTx.put(t.transactionId, c);
                }
                if (t.isPickup) c.pickup = t; else c.delivery = t;
            }

            java.util.List<Combined> tmp = new java.util.ArrayList<>(byTx.values());
            java.util.Collections.sort(tmp, (a, b) -> {
                boolean aActive = activity.isActiveTx(a.transactionId);
                boolean bActive = activity.isActiveTx(b.transactionId);
                if (aActive != bActive) return aActive ? -1 : 1; // active first

                boolean aAssigned = activity.isAssignedTx(a.transactionId);
                boolean bAssigned = activity.isAssignedTx(b.transactionId);
                if (aAssigned != bAssigned) return aAssigned ? -1 : 1; // (shouldn't tie, but keep stable)

                return Long.compare(b.transactionId, a.transactionId); // newest id first
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

            boolean active = activity.isActiveTx(c.transactionId);
            boolean assigned = activity.isAssignedTx(c.transactionId);

            String status = active ? "Active" : (assigned ? "Assigned" : "");
            h.tvTxTitle.setText(
                    status.isEmpty()
                            ? ("Assignment #" + c.transactionId)
                            : ("Assignment #" + c.transactionId + " — " + status)
            );

            h.tvPickup.setText(c.pickup != null ? c.pickup.address : "-");
            h.tvDrop.setText(
                    c.delivery != null ? c.delivery.address : "-"
            );

            if (assigned && !active) {
                h.btnPrimary.setText("Accept & Start"); // or just "Accept"
                int bg = ContextCompat.getColor(activity, R.color.verdi_green_bg);
                int fg = ContextCompat.getColor(activity, R.color.verdi_green_text);
                ViewCompat.setBackgroundTintList(h.btnPrimary, ColorStateList.valueOf(bg));
                h.btnPrimary.setTextColor(fg);
                h.btnPrimary.setOnClickListener(v -> activity.acceptAndStart(
                        c.transactionId,
                        c.pickup,   // may be null if not loaded yet
                        c.delivery  // may be null
                ));
                return; // important: skip phase-based setup below for assigned items
            }

            Phase phase = activity.phaseMap.getOrDefault(c.transactionId, Phase.PICKUP_STARTED);

            String label;
            switch (phase) {
                case PICKUP_STARTED:     label = "Start";               break;
                case PICKUP_ARRIVED:     label = "Complete Pickup";     break;
                case PICKUP_COMPLETED:   label = "Start Delivery";      break;
                case DELIVERY_ARRIVED:   label = "Complete Delivery";   break;
                case DELIVERY_COMPLETED:
                default:                 label = "Done";                break;
            }
            h.btnPrimary.setText(label);

            boolean green = (phase == Phase.PICKUP_STARTED || phase == Phase.DELIVERY_COMPLETED);
            int bg = ContextCompat.getColor(activity, green ? R.color.verdi_green_bg : R.color.verdi_red_bg);
            int fg = ContextCompat.getColor(activity, green ? R.color.verdi_green_text : R.color.verdi_red_text);
            ViewCompat.setBackgroundTintList(h.btnPrimary, ColorStateList.valueOf(bg));
            h.btnPrimary.setTextColor(fg);

            h.btnPrimary.setOnClickListener(v -> {
                TaskItem forLaunch = c.pickup != null ? c.pickup : c.delivery;
                if (forLaunch != null && listener != null) {
                    listener.onStartClicked(c.transactionId, forLaunch, c.delivery);
                }
            });
        }

        @Override public int getItemCount() { return grouped.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvTxTitle, tvPickup, tvDrop;
            final android.widget.Button btnPrimary;
            VH(@NonNull View itemView) {
                super(itemView);
                tvTxTitle = itemView.findViewById(R.id.tvTxTitle);
                tvPickup  = itemView.findViewById(R.id.tvPickupAddress);
                tvDrop    = itemView.findViewById(R.id.tvDropAddress);
                btnPrimary= itemView.findViewById(R.id.btnPrimary);
            }
        }
    }

    @Override public void onBackPressed() {
        super.onBackPressed();
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        moveTaskToBack(true);
    }

    private boolean onNavItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.nav_logout) {
            if (hasTaskLock) {
                Toast.makeText(this, "Finish current task before logging out.", Toast.LENGTH_SHORT).show();
                drawerLayout.closeDrawer(GravityCompat.START);
                return true; // handled
            }
            doLogout();
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        }
        drawerLayout.closeDrawer(GravityCompat.START);
        return false;
    }

    private void markRouteStart(long txId) {
        DocumentReference d = driverDoc();
        if (d == null || txId <= 0) return;
        long driverId = currentDriverId();
        Map<String, Object> updates = new HashMap<>();
        updates.put("driver_route_ts." + txId + ".start", FieldValue.serverTimestamp());
        updates.put("driver_id", driverId);
        d.update(updates).addOnFailureListener(e -> Log.w(TAG, "markRouteStart: " + e.getMessage()));
    }

    private void markRouteComplete(long txId) {
        DocumentReference d = driverDoc();
        if (d == null || txId <= 0) return;
        long driverId = currentDriverId();
        Map<String, Object> updates = new HashMap<>();
        updates.put("driver_route_ts." + txId + ".complete", FieldValue.serverTimestamp());
        updates.put("driver_id", driverId);
        d.update(updates).addOnFailureListener(e -> Log.w(TAG, "markRouteComplete: " + e.getMessage()));
    }
    private void seedDriverRouteArrayIfMissing(long txId) {
        DocumentReference d = driverDoc();
        if (d == null || txId <= 0) return;
        Map<String, Object> seed = new HashMap<>();
        seed.put("driver_route." + txId, new ArrayList<>()); // << flatted path
        d.set(seed, SetOptions.merge())
                .addOnFailureListener(e -> Log.w(TAG, "seedDriverRouteArrayIfMissing: " + e.getMessage()));
    }
    private void ensureStartTracking(long txId) {
        addActiveTxFirestore(txId);        // puts txId into active_transactions
        markRouteStart(txId);              // driver_route_ts.<txId>.start = serverTimestamp()
        seedDriverRouteArrayIfMissing(txId); // driver_route.<txId> = [] (ensures array key exists)
    }

    private void ensureAssignedTransactionsArray() {
        DocumentReference d = driverDoc();
        if (d == null) return;

        d.get().addOnSuccessListener(snap -> {
            Object v = (snap != null && snap.exists()) ? snap.get("assigned_transactions") : null;

            if (v == null) {
                Map<String, Object> seed = new HashMap<>();
                seed.put("assigned_transactions", new ArrayList<Long>());
                seed.put("driver_id", currentDriverId());
                d.set(seed, SetOptions.merge());
            } else if (!(v instanceof java.util.List)) {
                Map<String, Object> fix = new HashMap<>();
                fix.put("assigned_transactions", new ArrayList<Long>());
                fix.put("driver_id", currentDriverId());
                d.set(fix, SetOptions.merge());
            }
        });
    }

    private void startMirrorTransactionsIdToAssigned() {
        if (txIdMirrorReg != null) {
            try { txIdMirrorReg.remove(); } catch (Exception ignored) {}
            txIdMirrorReg = null;
        }

        DocumentReference d = driverDoc();
        if (d == null) return;

        txIdMirrorReg = d.addSnapshotListener((snap, err) -> {
            if (err != null || snap == null || !snap.exists()) return;

            Long[] parsed = parseTxNumbers(snap.get("transactionsId"));
            if (parsed.length == 0) return;

            HashSet<Long> assigned = new HashSet<>();
            Object a = snap.get("assigned_transactions");
            if (a instanceof List<?>) {
                for (Object o : (List<?>) a) {
                    try { assigned.add(Long.parseLong(String.valueOf(o).trim())); } catch (Exception ignored) {}
                }
            }

            HashSet<Long> active = new HashSet<>();
            Object act = snap.get("active_transactions");
            if (act instanceof List<?>) {
                for (Object o : (List<?>) act) {
                    try { active.add(Long.parseLong(String.valueOf(o).trim())); } catch (Exception ignored) {}
                }
            }

            ArrayList<Long> toAdd = new ArrayList<>();
            for (Long n : parsed) {
                if (n != null && !assigned.contains(n) && !active.contains(n)) {
                    toAdd.add(n);
                }
            }

            if (!assigned.isEmpty()) {
                long max = -1;
                for (Long e : assigned) if (e != null && e > max) max = e;
                for (Long n : parsed) if (n != null && max >= 0 && n > max) {
                    for (long k = max + 1; k <= n; k++) {
                        if (!assigned.contains(k) && !active.contains(k)) toAdd.add(k);
                    }
                }
            }
            if (toAdd.isEmpty()) return;
            d.update(
                    "assigned_transactions", FieldValue.arrayUnion(toAdd.toArray(new Long[0])),
                    "transactionsId", FieldValue.delete(),
                    "assigned_updated_at", FieldValue.serverTimestamp(),
                    "driver_id", currentDriverId()
            ).addOnFailureListener(e -> Log.w(TAG, "mirror assigned failed: " + e));

        });
    }

    private static Long[] parseTxNumbers(Object v) {
        java.util.ArrayList<Long> out = new java.util.ArrayList<>();
        if (v instanceof Number) {
            out.add(((Number) v).longValue());
        } else if (v instanceof String) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher((String) v);
            while (m.find()) {
                try { out.add(Long.parseLong(m.group())); } catch (Exception ignored) {}
            }
        } else if (v instanceof java.util.List<?>) {
            for (Object o : (java.util.List<?>) v) {
                if (o instanceof Number) out.add(((Number) o).longValue());
                else if (o instanceof String) {
                    try { out.add(Long.parseLong(((String) o).trim())); } catch (Exception ignored) {}
                }
            }
        }
        return out.toArray(new Long[0]);
    }

    private void startWatchingTaskArrays() {
        stopWatchingTaskArrays();

        DocumentReference d = driverDoc();
        if (d == null) return;

        taskArraysReg = d.addSnapshotListener((snap, err) -> {
            if (err != null || snap == null || !snap.exists()) return;

            // Parse current arrays from Firestore
            java.util.Set<Long> activeNow   = parseIdsToLongs(snap.get("active_transactions"));
            java.util.Set<Long> assignedNow = parseIdsToLongs(snap.get("assigned_transactions"));

            // --- NEW: compute which txIds were removed from active[] ---
            java.util.Set<Long> prevActive;
            synchronized (lastActiveIds) {
                prevActive = new java.util.HashSet<>(lastActiveIds); // copy BEFORE we overwrite it
            }

            // Only run "removed" detection after first load
            if (!taskArraysFirstLoad) {
                java.util.Set<Long> removedFromActive = new java.util.HashSet<>(prevActive);
                removedFromActive.removeAll(activeNow); // only those that existed before but not now

                // Filter out any ids we've already written (extra dedupe safety)
                java.util.Iterator<Long> it = removedFromActive.iterator();
                while (it.hasNext()) {
                    Long id = it.next();
                    if (id == null || id <= 0 || alreadyCountedCompleted.contains(id)) it.remove();
                }

                if (!removedFromActive.isEmpty()) {
                    // Persist "completed" ONLY at the moment it leaves active[]
//                    addCompletedCountArray(removedFromActive);
                    alreadyCountedCompleted.addAll(removedFromActive);
                }
            } else {
                taskArraysFirstLoad = false; // next snapshots can do diffs
            }
            // --- END NEW ---

            // Mirror "now" into the caches for future diffs and UI helpers
            synchronized (lastActiveIds) {
                lastActiveIds.clear();
                lastActiveIds.addAll(activeNow);
            }
            synchronized (lastAssignedIds) {
                lastAssignedIds.clear();
                lastAssignedIds.addAll(assignedNow);
            }

            // Existing logic that reconciles UI/prefs with server
            java.util.Set<Long> desired = new java.util.HashSet<>();
            desired.addAll(activeNow);
            desired.addAll(assignedNow);

            Boolean serverHasTask = snap.getBoolean("havingtask");
            reconcileTasksWith(desired, serverHasTask); // fetch rows & refreshList()

            boolean lock = (serverHasTask != null ? serverHasTask : !desired.isEmpty());
            setDutyLocked(lock);
            setLogoutLocked(lock);
        });
    }

    boolean isActiveTx(long txId) {
        synchronized (lastActiveIds) {
            return lastActiveIds.contains(txId);
        }
    }
    boolean isAssignedTx(long txId) {
        synchronized (lastAssignedIds) {
            return lastAssignedIds.contains(txId);
        }
    }
    private void stopWatchingTaskArrays() {
        if (taskArraysReg != null) {
            try { taskArraysReg.remove(); } catch (Exception ignore) {}
            taskArraysReg = null;
        }
    }
    private static java.util.Set<Long> parseIdsToLongs(Object v) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        if (v instanceof java.util.List<?>) {
            for (Object o : (java.util.List<?>) v) {
                if (o == null) continue;
                try { out.add(Long.parseLong(String.valueOf(o).trim())); } catch (Exception ignore) {}
            }
        }
        return out;
    }

    private void reconcileTasksWith(java.util.Set<Long> desired, @Nullable Boolean serverHasTask) {
        // prefs mirror
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        java.util.Set<String> cur = new java.util.HashSet<>(sp.getStringSet(KEY_ACCEPTED_TX_IDS, new java.util.HashSet<>()));
        boolean changed = false;

        for (String s : new java.util.HashSet<>(cur)) {
            long id;
            try { id = Long.parseLong(s); }
            catch (Exception e) { cur.remove(s); changed = true; continue; }
            if (!desired.contains(id)) { cur.remove(s); changed = true; }
        }

        for (Long id : desired) {
            String ss = String.valueOf(id);
            if (!cur.contains(ss)) { cur.add(ss); changed = true; }
        }

        if (changed) sp.edit().putStringSet(KEY_ACCEPTED_TX_IDS, cur).apply();

        for (int i = acceptedTasks.size() - 1; i >= 0; i--) {
            if (!desired.contains(acceptedTasks.get(i).transactionId)) acceptedTasks.remove(i);
        }
        java.util.Iterator<java.util.Map.Entry<Long,Phase>> it = phaseMap.entrySet().iterator();
        while (it.hasNext()) if (!desired.contains(it.next().getKey())) it.remove();

        long active = getActiveTransaction();
        if (active != 0 && !desired.contains(active)) setActiveTransaction(0L);

        for (Long id : desired) {
            boolean have = false;
            for (TaskItem t : acceptedTasks) {
                if (t.transactionId == id) { have = true; break; }
            }
            if (!have) fetchAndAddTaskRows(id);
        }
        // 🔒 only write havingtask if it actually changed
        boolean shouldHaveTask = !desired.isEmpty();
        if (serverHasTask == null || serverHasTask.booleanValue() != shouldHaveTask) {
            DocumentReference d = driverDoc();
            DocumentReference p = presenceDoc();
            if (d != null && p != null) {
                long driverId = currentDriverId();

                java.util.Map<String,Object> root = new java.util.HashMap<>();
                root.put("havingtask", shouldHaveTask);
                root.put("task_updated_at", com.google.firebase.firestore.FieldValue.serverTimestamp());
                root.put("driver_id", driverId);
                d.set(root, com.google.firebase.firestore.SetOptions.merge());

                java.util.Map<String,Object> pres = new java.util.HashMap<>();
                pres.put("havingtask", shouldHaveTask);
                pres.put("task_updated_at", com.google.firebase.firestore.FieldValue.serverTimestamp());
                pres.put("driver_id", driverId);
                p.set(pres, com.google.firebase.firestore.SetOptions.merge());
            }
        }
        refreshList(); // regroup + visibility
    }

    private void acceptTransaction(long txId, boolean writeTxnMirror,
                                   @NonNull Runnable onOk,
                                   @NonNull Runnable onErr) {
        final String bearer = AuthPrefs.bearer(this);
        if (bearer == null || bearer.isEmpty()) {
            onErr.run();
            return;
        }

        api.getTaskDetails(bearer, txId).enqueue(new Callback<TaskDetailsResponse>() {
            @Override public void onResponse(Call<TaskDetailsResponse> call, Response<TaskDetailsResponse> res) {
                if (!res.isSuccessful() || res.body()==null || !res.body().success || res.body().data==null) {
                    onErr.run();
                    return;
                }
                TaskDetailsResponse.Data d = res.body().data;

                long driverId = AuthPrefs.driverId(HomeActivity.this);
                if (driverId <= 0) {
                    onErr.run();
                    return;
                }

                api.assignDriver(bearer, d.id, driverId).enqueue(new Callback<GenericResponse>() {
                    @Override public void onResponse(Call<GenericResponse> call, Response<GenericResponse> ar) {
                        if (!ar.isSuccessful() || ar.body()==null || !ar.body().success) {
                            onErr.run();
                            return;
                        }

                        java.util.ArrayList<Long> rowIds = new java.util.ArrayList<>();
                        if (d.pickup_task != null) rowIds.add(d.pickup_task.id);
                        if (d.delivery_task != null) rowIds.add(d.delivery_task.id);
                        if (rowIds.isEmpty()) {
                            onErr.run();
                            return;
                        }

                        acceptRowsSequentially(bearer, rowIds, 0, () -> {
                            setActiveTransaction(d.id);

                            writeDriverIdToFirestore();
                            sendDriverIdToApi();
                            addActiveTxFirestore(d.id);          // also removes from assigned
                            markRouteStart(d.id);
                            seedDriverRouteArrayIfMissing(d.id);

                            refreshList();
                            syncHavingTaskWithHome();
                            refreshPhasesFromServer();
                            onOk.run();
                        }, onErr);
                    }

                    @Override public void onFailure(Call<GenericResponse> call, Throwable t) {
                        onErr.run();
                    }
                });
            }
            @Override public void onFailure(Call<TaskDetailsResponse> call, Throwable t) {
                onErr.run();
            }
        });
    }

    private void acceptRowsSequentially(String bearer, java.util.List<Long> rowIds, int idx,
                                        @NonNull Runnable done, @NonNull Runnable err) {
        if (idx >= rowIds.size()) {
            done.run();
            return;
        }

        long rowId = rowIds.get(idx);
        api.updateTaskStatus(bearer, rowId, "accepted").enqueue(new Callback<GenericResponse>() {
            @Override public void onResponse(Call<GenericResponse> call, Response<GenericResponse> res) {
                // Continue regardless of per-row failure to keep UX smooth
                acceptRowsSequentially(bearer, rowIds, idx + 1, done, err);
            }
            @Override public void onFailure(Call<GenericResponse> call, Throwable t) {
                acceptRowsSequentially(bearer, rowIds, idx + 1, done, err);
            }
        });
    }

    private void handlePushIntent(@Nullable Intent intent) {
        if (intent == null) return;

        long tx = intent.getLongExtra("push_tx_id", 0L); // from our own notification (best case)

        if (tx == 0 && intent.getData() != null) {
            String path = intent.getData().toString();
            tx = extractTxIdFromText(path);
        }

        if (tx == 0) {
            String[] candidates = {
                    "gcm.notification.body", // typical
                    "body",                  // if you added custom "body" in data
                    "google.c.a.c_l",        // sometimes contains label text
                    "raw_body"               // from our service (debug)
            };
            for (String k : candidates) {
                String t = intent.getStringExtra(k);
                tx = extractTxIdFromText(t);
                if (tx > 0) break;
            }
        }

        if (tx == 0) {
            String raw = intent.getStringExtra("transaction_id");
            if (raw != null) try { tx = Long.parseLong(raw.trim()); } catch (Exception ignore) {}
        }


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

    private void setFcmTransactionsId(@Nullable Long txId) {
        DocumentReference d = driverDoc();
        DocumentReference p = presenceDoc();
        if (d == null || p == null) return;

        long driverId = currentDriverId();

        Map<String, Object> root = new HashMap<>();
        root.put("fcmtransactionsid", txId);          // can be null
        root.put("driver_id", driverId);
        d.set(root, SetOptions.merge());

        Map<String, Object> pres = new HashMap<>();
        pres.put("fcmtransactionsid", txId);          // can be null
        pres.put("driver_id", driverId);
        p.set(pres, SetOptions.merge());
    }

    public void showFcmTaskPopupWithTimer(long txId, @Nullable String pickup, @Nullable String drop,
                                          @Nullable String type, @Nullable String status, @Nullable String created,
                                          int seconds)
    {
        final BottomSheetDialog dialog = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.fcm_task_popup, null, false);
        dialog.setContentView(v);

        TextView tvPickup  = v.findViewById(R.id.tvPickup);
        TextView tvDrop    = v.findViewById(R.id.tvDrop);
        TextView tvType    = v.findViewById(R.id.tvType);
        TextView tvStatus  = v.findViewById(R.id.tvStatus);
        TextView tvCreated = v.findViewById(R.id.tvCreated);
        Button btnDismiss  = v.findViewById(R.id.btnDismiss);
        Button btnAccept   = v.findViewById(R.id.btnAccept);

        if (pickup  != null) tvPickup.setText("Pickup: " + pickup);
        if (drop    != null) tvDrop.setText("Drop: "   + drop);
        if (type    != null) tvType.setText("Task Type: " + type);
        if (status  != null) tvStatus.setText("Task Status: " + status);
        if (created != null) tvCreated.setText("Created: " + created);
        // Save for live updates
        fcmPopupDialog = dialog;
        fcmBtnDismiss  = btnDismiss;
        fcmBtnAccept   = btnAccept;
        fcmCurrentTxId = txId;
        startOrRestartFcmTimer(seconds);
        playFcmPopupSound();
        dialog.setOnDismissListener(d -> {
            if (fcmPopupTimer != null) { fcmPopupTimer.cancel(); fcmPopupTimer = null; }
            setFcmTransactionsId(null);  // clear trigger so it won't re-fire
            fcmPopupShowing = false;
            // 🔇 Stop and release sound
            stopFcmPopupSound();
            fcmPopupDialog = null;
            fcmBtnDismiss = null;
            fcmBtnAccept = null;
            fcmCurrentTxId = 0L;
            fcmCurrentSecs = 0;
        });
        btnDismiss.setOnClickListener(view -> {
            if (fcmPopupTimer != null) { fcmPopupTimer.cancel(); fcmPopupTimer = null; }
            dialog.dismiss();
        });
        btnAccept.setOnClickListener(view -> {
            if (!getLocalOnDuty()) { Toast.makeText(this, "Go On Duty first", Toast.LENGTH_SHORT).show(); return; }
            btnAccept.setEnabled(false); btnAccept.setText("Accepting…");
            if (fcmPopupTimer != null) { fcmPopupTimer.cancel(); fcmPopupTimer = null; }
            acceptAndStartFromFcm(txId);
            dialog.dismiss(); // onDismiss clears trigger
        });
        dialog.show();
    }

    private void startWatchingFcmTransactionId() {
        stopWatchingFcmTransactionId();
        DocumentReference d = driverDoc();
        if (d == null) return;

        // Include metadata so we can ignore our own local writes
        fcmTxReg = d.addSnapshotListener(
                com.google.firebase.firestore.MetadataChanges.INCLUDE,
                (snap, err) -> {
                    if (err != null || snap == null || !snap.exists()) return;

                    // 1) Ignore events caused by our own local updates (like completedcount writes)
                    if (snap.getMetadata().hasPendingWrites()) return;

                    Object raw = snap.get("fcmtransactionsid");
                    if (raw == null) return;

                    TxPayload payload = parseTxPayload(String.valueOf(raw));
                    if (payload == null || payload.txId <= 0) return;

                    // 2) Strong early guard: if we've already handled this id, bail out immediately
                    if (java.util.Objects.equals(lastProcessedFcmTxId, payload.txId)) return;

                    // Mark as handled ASAP to prevent re-entry from any other updates
                    lastProcessedFcmTxId = payload.txId;

                    // 3) (Optional) if a popup is already visible for same tx, we just refresh timer
                    int secs = (payload.secs > 0) ? payload.secs : 5;
                    if (fcmPopupShowing && fcmPopupDialog != null && fcmPopupDialog.isShowing()
                            && payload.txId == fcmCurrentTxId) {
                        if (secs != fcmCurrentSecs) startOrRestartFcmTimer(secs);
                        return;
                    }
                    // 5) Show the popup
                    showPopupForTxId(payload.txId, secs);
                });
    }


    private void stopWatchingFcmTransactionId() {
        if (fcmTxReg != null) {
            try { fcmTxReg.remove(); } catch (Exception ignored) {}
            fcmTxReg = null;
        }
    }

    private void showPopupForTxId(long txId, int seconds) {
        final String bearer = AuthPrefs.bearer(this);
        fcmPopupShowing = true; // prevent double-open while we fetch
        ApiClient.get().create(ApiService.class)
                .getTaskDetails(bearer, txId)
                .enqueue(new retrofit2.Callback<TaskDetailsResponse>() {
                    @Override public void onResponse(retrofit2.Call<TaskDetailsResponse> call,
                                                     retrofit2.Response<TaskDetailsResponse> res) {
                        String pickup=null, drop=null, type=null, status=null, created=null;
                        if (res.isSuccessful() && res.body()!=null && res.body().success && res.body().data!=null) {
                            TaskDetailsResponse.Data d = res.body().data;
                            if (d.pickup_task   != null) { pickup = d.pickup_task.address;  status = d.pickup_task.task_status; }
                            if (d.delivery_task != null) { drop   = d.delivery_task.address; }
                            type    = d.vendor_payment_type;
                            created = d.created_at != null ? prettyTime(d.created_at) : null;
                        }
                        showFcmTaskPopupWithTimer(txId, pickup, drop, type, status, created, seconds);
                    }
                    @Override public void onFailure(retrofit2.Call<TaskDetailsResponse> call, Throwable t) {
                        showFcmTaskPopupWithTimer(txId, null, null, null, null, null, seconds);
                    }
                });
    }

    public void acceptAndStart(long txId, @Nullable TaskItem pickup, @Nullable TaskItem deliveryMaybe) {
        acceptAndStartInternal(txId, pickup, deliveryMaybe, /*fromFcm=*/false); // ← old flow unchanged
    }

    public void acceptAndStartFromFcm(long txId) {
        acceptAndStartInternal(txId, null, null, /*fromFcm=*/true);             // ← FCM-only path
    }

    private void acceptAndStartInternal(long txId,
                                        @Nullable TaskItem pickup,
                                        @Nullable TaskItem deliveryMaybe,
                                        boolean fromFcm) {
        if (!getLocalOnDuty()) { Toast.makeText(this, "Go On Duty first", Toast.LENGTH_SHORT).show(); return; }
        acceptTransaction(txId, /*writeTxnMirror=*/fromFcm,  // only write on FCM path (or set false to never)
                () -> {
                    addActiveTxFirestore(txId);
                    markRouteStart(txId);
                    seedDriverRouteArrayIfMissing(txId);
                    setActiveTransaction(txId);
                    if (!fromFcm) updateLastTransactionId(txId);
                    refreshList();
                    syncHavingTaskWithHome();
                    refreshPhasesFromServer();
                    TaskItem launch = (pickup != null) ? pickup : deliveryMaybe;
                    if (launch != null) startTaskIfNeeded(launch); else fetchAndAddTaskRows(txId);
                },
                () -> Toast.makeText(this, "Accept failed. Try again.", Toast.LENGTH_SHORT).show()
        );
    }

    private void normalizeAssignedTransactionsTypes() {
        DocumentReference d = driverDoc();
        if (d == null) return;
        d.get().addOnSuccessListener(snap -> {
            if (snap == null || !snap.exists()) return;
            // read, normalize to Long set
            HashSet<Long> assigned = new HashSet<>();
            Object a = snap.get("assigned_transactions");
            if (a instanceof List<?>) {
                for (Object o : (List<?>) a) {
                    try { assigned.add(Long.parseLong(String.valueOf(o).trim())); } catch (Exception ignored) {}
                }
            }
            Map<String,Object> fix = new HashMap<>();
            fix.put("assigned_transactions", new ArrayList<>(assigned));
            fix.put("driver_id", currentDriverId());
            d.set(fix, SetOptions.merge());
        });
    }

    // Remove a completed tx from assigned[] and clear the mirror trigger.
    private void cleanupCompletedTx(long txId) {
        DocumentReference d = driverDoc();
        if (d == null || txId <= 0) return;
        Map<String, Object> u = new HashMap<>();
        u.put("assigned_transactions", FieldValue.arrayRemove(txId, String.valueOf(txId)));
        // clear mirror source so it can't re-add
        u.put("transactionsId", FieldValue.delete()); //have a eye-clear transaction once all the tasks are completed
        u.put("assigned_updated_at", FieldValue.serverTimestamp());
        u.put("task_updated_at", FieldValue.serverTimestamp());
        u.put("driver_id", currentDriverId());
        d.update(u).addOnFailureListener(e ->
                Log.w(TAG, "cleanupCompletedTx: " + e.getMessage()));
    }

    // FCM popup live-update state
    @Nullable private BottomSheetDialog fcmPopupDialog = null;
    @Nullable private Button fcmBtnDismiss = null;
    @Nullable private Button fcmBtnAccept = null;
    private long fcmCurrentTxId = 0L;
    private int  fcmCurrentSecs = 0; // last applied seconds
    private void startOrRestartFcmTimer(int seconds) {
        int secs = (seconds > 0) ? seconds : 5; // fallback
        fcmCurrentSecs = secs;
        if (fcmPopupTimer != null) {
            fcmPopupTimer.cancel();
            fcmPopupTimer = null;
        }
        if (fcmBtnDismiss != null) {
            fcmBtnDismiss.setText("Dismiss (" + secs + ")");
        }
        fcmPopupTimer = new CountDownTimer(secs * 1000L, 1000L) {
            @Override public void onTick(long msLeft) {
                int s = (int) Math.ceil(msLeft / 1000.0);
                if (fcmBtnDismiss != null) fcmBtnDismiss.setText("Dismiss (" + s + ")");
            }
            @Override public void onFinish() {
                if (fcmPopupDialog != null && fcmPopupDialog.isShowing()) {
                    fcmPopupDialog.dismiss();
                }
            }
        }.start();
    }
    //FCM - Auto Allocation timer
    private static final class TxPayload {
        final long txId;
        final int secs; // 0 if absent
        TxPayload(long txId, int secs) { this.txId = txId; this.secs = secs; }
    }
    @Nullable
    private static TxPayload parseTxPayload(@Nullable Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number) {
            return new TxPayload(((Number) raw).longValue(), 0);
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(\\d+)(?:\\s*,\\s*(\\d+))?$")
                    .matcher(s);
            if (m.find()) {
                long id   = Long.parseLong(m.group(1));
                int secs  = (m.group(2) != null) ? Integer.parseInt(m.group(2)) : 0;
                return new TxPayload(id, secs);
            }
            m = java.util.regex.Pattern.compile("(\\d{3,})").matcher(s);
            if (m.find()) {
                long id = Long.parseLong(m.group(1));
                return new TxPayload(id, 0);
            }
        } catch (Exception ignore) {}

        return null;
    }

    //OnDuty - Having task true - Locked
    private void setDutyLocked(boolean locked) {
        if (switchDuty == null) return;
        switchDuty.setOnCheckedChangeListener(null);
        if (locked) {
            if (!switchDuty.isChecked()) switchDuty.setChecked(true);
            switchDuty.setEnabled(false);        // disables state & ripple
            switchDuty.setClickable(false);      // no clicks
            switchDuty.setFocusable(false);      // no focus change
            switchDuty.setAlpha(0.5f);           // greyed out
            switchDuty.setOnTouchListener((v, e) -> true);
            try {
                int bg = ContextCompat.getColor(this, R.color.verdi_red_bg);
                int fg = ContextCompat.getColor(this, R.color.verdi_red_text);
                switchDuty.getTrackDrawable().setTint(bg);
                switchDuty.getThumbDrawable().setTint(fg);
            } catch (Throwable ignored) {}
        } else {
            // Unlock and restore normal behavior
            switchDuty.setEnabled(true);
            switchDuty.setClickable(true);
            switchDuty.setFocusable(true);
            switchDuty.setAlpha(1f);
            switchDuty.setOnTouchListener(null);             // stop eating touches
            try {
                switchDuty.getTrackDrawable().setTintList(null);
                switchDuty.getThumbDrawable().setTintList(null);
            } catch (Throwable ignored) {}
            switchDuty.setOnCheckedChangeListener(dutyToggleListener);
        }
        switchDuty.jumpDrawablesToCurrentState();
    }
    private void setLogoutLocked(boolean locked) {
        if (navView == null) return;
        runOnUiThread(() -> {
            MenuItem item = navView.getMenu().findItem(R.id.nav_logout);
            if (item != null) {
                item.setEnabled(!locked);
                // optional: visual dim
                android.view.View v = navView.findViewById(R.id.nav_logout);
                if (v != null) v.setAlpha(locked ? 0.4f : 1f);
            }
        });
    }

    //FCM - Notification Sound
    @Nullable private android.media.MediaPlayer fcmSoundPlayer = null;
    private void playFcmPopupSound() {
        try {
            if (fcmSoundPlayer != null) {
                fcmSoundPlayer.seekTo(0);
                fcmSoundPlayer.start();
                return;
            }
            fcmSoundPlayer = android.media.MediaPlayer.create(this, R.raw.notify_common);
            if (fcmSoundPlayer != null) {
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    fcmSoundPlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                }
                fcmSoundPlayer.setOnCompletionListener(mp -> {
                    try { mp.seekTo(0); } catch (Exception ignored) {}
                });
                fcmSoundPlayer.start();
            }
        } catch (Throwable ignored) {}
    }
    private void stopFcmPopupSound() {
        try {
            if (fcmSoundPlayer != null) {
                fcmSoundPlayer.stop();
                fcmSoundPlayer.release();
                fcmSoundPlayer = null;
            }
        } catch (Throwable ignored) {}
    }
//
//    /** Append txIds to drivers/{id}.completedcount[] and presence/current.completedcount[] */
//    private void addCompletedCountArray(@androidx.annotation.NonNull java.util.Collection<Long> ids) {
//        java.util.ArrayList<Long> list = new java.util.ArrayList<>();
//        for (Long v : ids) if (v != null && v > 0) list.add(v);
//        if (list.isEmpty()) return;
//
//        DocumentReference d = driverDoc();
//        DocumentReference p = presenceDoc();
//        if (d == null || p == null) return;
//
//        long driverId = currentDriverId();
//
//        d.update("completed_transactions", com.google.firebase.firestore.FieldValue.arrayUnion(list.toArray()))
//                .addOnSuccessListener(v -> d.set(new java.util.HashMap<String,Object>() {{
//                    put("driver_id", driverId);
//                    put("completedcount_updated_at", com.google.firebase.firestore.FieldValue.serverTimestamp());
//                }}, com.google.firebase.firestore.SetOptions.merge()))
//                .addOnFailureListener(e -> {
//                    java.util.HashMap<String,Object> seed = new java.util.HashMap<>();
//                    seed.put("completed_transactions", list);
//                    seed.put("driver_id", driverId);
//                    seed.put("completedcount_updated_at", com.google.firebase.firestore.FieldValue.serverTimestamp());
//                    d.set(seed, com.google.firebase.firestore.SetOptions.merge());
//                });
//
//        p.update("completed_transactions", com.google.firebase.firestore.FieldValue.arrayUnion(list.toArray()))
//                .addOnSuccessListener(v -> p.set(new java.util.HashMap<String,Object>() {{
//                    put("driver_id", driverId);
//                    put("completedcount_updated_at", com.google.firebase.firestore.FieldValue.serverTimestamp());
//                }}, com.google.firebase.firestore.SetOptions.merge()))
//                .addOnFailureListener(e -> {
//                    java.util.HashMap<String,Object> seed = new java.util.HashMap<>();
//                    seed.put("completed_transactions", list);
//                    seed.put("driver_id", driverId);
//                    seed.put("completedcount_updated_at", com.google.firebase.firestore.FieldValue.serverTimestamp());
//                    p.set(seed, com.google.firebase.firestore.SetOptions.merge());
//                });
//    }

}

// app/src/main/java/com/example/loginapp/TaskDetailActivity.java
package com.example.loginapp;

import static android.content.Context.MODE_PRIVATE;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.esri.arcgisruntime.geometry.Point;
import com.esri.arcgisruntime.geometry.SpatialReferences;
import com.esri.arcgisruntime.layers.ArcGISVectorTiledLayer;
import com.esri.arcgisruntime.mapping.ArcGISMap;
import com.esri.arcgisruntime.mapping.Basemap;
import com.esri.arcgisruntime.mapping.Viewpoint;
import com.esri.arcgisruntime.mapping.view.MapView;
import com.example.loginapp.net.ApiClient;
import com.example.loginapp.net.ApiService;
import com.example.loginapp.net.model.GenericResponse;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;
import com.ncorti.slidetoact.SlideToActView;

import java.util.HashMap;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class TaskDetailActivity extends AppCompatActivity {

    private static final String TAG = "TaskDetail";

    private static final String PREFS = "verdi_prefs";
    private static final String KEY_ACCEPTED_TX_IDS = "accepted_tx_ids_set";
    private static final String KEY_ACTIVE_TX = "active_txid";

    private static final String KEY_PHASE_PREFIX = "tx_phase_";
    private static final String KEY_STATUS_PREFIX = "tx_status_";

    private static final String ACTION_TASK_PHASE = "com.example.loginapp.ACTION_TASK_PHASE";

    // ✅ CHANGED: 100m -> 500m
    private static final float RADIUS_500M = 500f;

    private FusedLocationProviderClient fusedClient;
    private LocationCallback autoCb;

    @Nullable private volatile Location lastWatcherLoc = null;

    private boolean pickupAutoArrivedSent = false;
    private boolean deliveryAutoArrivedSent = false;
    private boolean pickupAutoInFlight = false;
    private boolean deliveryAutoInFlight = false;

    private static final String CH_REVOKE = "verdi_revoke_channel";
    private static final int NOTIF_REVOKE_ID = 9101;

    private MapView paciMapView;
    private BottomSheetBehavior<LinearLayout> bottomSheetBehavior;

    private ImageView ivBack, ivCall, ivWhatsApp, ivDirection;
    private TextView tvTaskId, tvTaskTimeType, tvTaskStatus, tvTaskAddress, tvTaskDetails, tvOrderId;
    private TextView tvPickupAddress, tvDeliveryAddress;

    private SlideToActView swipePickup;
    private SlideToActView swipeDelivery;

    private ProgressBar progressUpdate;

    private ApiService api;

    private long transactionId, pickupId, deliveryId;

    private String pickupAddress, pickupPhone;
    private double pickupLat, pickupLng;

    private String deliveryAddress, deliveryPhone;
    private double deliveryLat, deliveryLng;

    private String paymentType, orderAmount, orderId;

    private int pickupStep = 0;
    private int deliveryStep = 0;

    private String currentPhaseStr;
    private long pickupRowIdForBroadcast;

    private ListenerRegistration ownershipReg;

    private boolean receiversRegistered = false;
    private boolean completedFlowStarted = false;

    private enum Phase {
        PICKUP_STARTABLE,
        PICKUP_STARTED,
        PICKUP_ARRIVED,
        PICKUP_COMPLETED,
        DELIVERY_STARTED,
        DELIVERY_ARRIVED,
        DELIVERY_COMPLETED
    }

    private Phase currentPhase = Phase.PICKUP_STARTABLE;

    // =========================================================
    // ✅ STATUS PUSH RECEIVER (popup + UI update)
    // =========================================================
    private final BroadcastReceiver statusPushReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Actions.TASK_STATUS_CHANGED.equals(intent.getAction())) return;

            Log.e(TAG, "PUSH_RX action=" + intent.getAction()
                    + " myTx=" + transactionId
                    + " extraTx=" + intent.getLongExtra(Actions.EXTRA_TX_ID, -1)
                    + " extraTaskTx=" + intent.getLongExtra(Actions.EXTRA_TASK_TX_ID, -1)
                    + " status=" + intent.getStringExtra(Actions.EXTRA_TASK_STATUS)
                    + " msg=" + intent.getStringExtra(Actions.EXTRA_STATUS_MESSAGE));

            long txId = intent.getLongExtra(Actions.EXTRA_TX_ID, 0L);
            if (txId <= 0) {
                Log.e(TAG, "PUSH IGNORED: missing EXTRA_TX_ID");
                return;
            }
            if (txId != transactionId) {
                Log.e(TAG, "PUSH IGNORED: tx mismatch extraTx=" + txId + " myTx=" + transactionId);
                return;
            }

            long pushedTaskTxId = intent.getLongExtra(Actions.EXTRA_TASK_TX_ID, 0L);
            String status = intent.getStringExtra(Actions.EXTRA_TASK_STATUS);
            String msg = intent.getStringExtra(Actions.EXTRA_STATUS_MESSAGE);

            final String showStatus = (status != null ? status : "-");
            final String showMsg = (msg != null && !msg.trim().isEmpty())
                    ? msg
                    : "Status changed by the admin";

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                new AlertDialog.Builder(TaskDetailActivity.this)
                        .setTitle("Status Updated")
                        .setMessage("Transaction: " + txId + "\nStatus: " + showStatus + "\n\n" + showMsg)
                        .setPositiveButton("OK", (d, w) -> d.dismiss())
                        .show();

                applyStatusFromAdminPush(status, pushedTaskTxId);
            });
        }
    };

    // =========================================================
    // ✅ TASK DETAILS REFRESH RECEIVER (API truth -> apply)
    // =========================================================
    private final BroadcastReceiver taskDetailsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Actions.TASK_DETAILS_UPDATED.equals(intent.getAction())) return;

            long txId = intent.getLongExtra(Actions.EXTRA_TX_ID, 0L);
            if (txId <= 0 || txId != transactionId) return;

            pickupId = intent.getLongExtra(Actions.EXTRA_PICKUP_ID, pickupId);
            deliveryId = intent.getLongExtra(Actions.EXTRA_DELIVERY_ID, deliveryId);

            pickupAddress = intent.getStringExtra(Actions.EXTRA_PICKUP_ADDRESS);
            pickupPhone = intent.getStringExtra(Actions.EXTRA_PICKUP_PHONE);
            pickupLat = intent.getDoubleExtra(Actions.EXTRA_PICKUP_LAT, pickupLat);
            pickupLng = intent.getDoubleExtra(Actions.EXTRA_PICKUP_LNG, pickupLng);

            deliveryAddress = intent.getStringExtra(Actions.EXTRA_DELIVERY_ADDRESS);
            deliveryPhone = intent.getStringExtra(Actions.EXTRA_DELIVERY_PHONE);
            deliveryLat = intent.getDoubleExtra(Actions.EXTRA_DELIVERY_LAT, deliveryLat);
            deliveryLng = intent.getDoubleExtra(Actions.EXTRA_DELIVERY_LNG, deliveryLng);

            paymentType = intent.getStringExtra(Actions.EXTRA_PAYMENT_TYPE);
            orderAmount = intent.getStringExtra(Actions.EXTRA_ORDER_AMOUNT);
            orderId = intent.getStringExtra(Actions.EXTRA_ORDER_ID);

            String pickupTaskSt = intent.getStringExtra(Actions.EXTRA_PICKUP_TASK_STATUS);
            String deliveryTaskSt = intent.getStringExtra(Actions.EXTRA_DELIVERY_TASK_STATUS);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                tvPickupAddress.setText((pickupAddress != null ? pickupAddress : "-") + " (Pickup)");
                tvDeliveryAddress.setText((deliveryAddress != null ? deliveryAddress : "-") + " (Delivery)");
                tvOrderId.setText(orderId != null ? "ORDER ID " + orderId : "");

                String details = "Task from Verdi";
                if (paymentType != null && !paymentType.isEmpty())
                    details += ", Payment: " + paymentType;
                if (orderAmount != null && !orderAmount.isEmpty())
                    details += ", Order Amount: " + orderAmount + " KD";
                tvTaskDetails.setText(details);

                applyFromTaskDetailsStatuses(pickupTaskSt, deliveryTaskSt);
            });
        }
    };

    private final BroadcastReceiver revokedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!Actions.TASK_REVOKED.equals(intent.getAction())) return;

            long txId = intent.getLongExtra(Actions.EXTRA_TX_ID, 0L);
            if (txId <= 0 || txId != transactionId) return;

            String reason = intent.getStringExtra(Actions.EXTRA_REASON);
            final boolean cancelled = "cancelled".equalsIgnoreCase(reason);

            final String msg = cancelled
                    ? ("Task #" + transactionId + " was cancelled by Admin.")
                    : ("Task #" + transactionId + " was unassigned by Admin.");

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                showRevokeNotification("VERDI", msg + "  (Tx #" + transactionId + ")");

                new AlertDialog.Builder(TaskDetailActivity.this)
                        .setTitle("Assignment Removed")
                        .setMessage(msg + "\n\nTransaction #" + transactionId)
                        .setCancelable(false)
                        .setPositiveButton("OK", (d, w) -> {
                            SharedPreferences sp = getSharedPreferences("verdi_prefs", MODE_PRIVATE);
                            sp.edit()
                                    .putLong("suppress_revoke_tx", transactionId)
                                    .putLong("suppress_revoke_at", System.currentTimeMillis())
                                    .apply();

                            clearLocalPhase(transactionId);
                            removeTxFromLocal(transactionId);

                            Intent home = new Intent(TaskDetailActivity.this, HomeActivity.class);
                            home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(home);
                            finish();
                        })
                        .show();
            });

        }
    };

    private void registerLbmReceiversIfNeeded() {
        if (receiversRegistered) return;
        receiversRegistered = true;

        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(statusPushReceiver, new IntentFilter(Actions.TASK_STATUS_CHANGED));
        lbm.registerReceiver(taskDetailsReceiver, new IntentFilter(Actions.TASK_DETAILS_UPDATED));
        lbm.registerReceiver(revokedReceiver, new IntentFilter(Actions.TASK_REVOKED));
        Log.e(TAG, "LBM receivers registered");
    }

    private void unregisterLbmReceivers() {
        if (!receiversRegistered) return;
        receiversRegistered = false;

        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        try { lbm.unregisterReceiver(statusPushReceiver); } catch (Exception ignored) {}
        try { lbm.unregisterReceiver(taskDetailsReceiver); } catch (Exception ignored) {}
        try { lbm.unregisterReceiver(revokedReceiver); } catch (Exception ignored) {}
        Log.e(TAG, "LBM receivers unregistered");
    }

    private void saveLocalPhase(@NonNull Phase phase) {
        if (transactionId <= 0) return;

        String st = (tvTaskStatus.getText() != null) ? tvTaskStatus.getText().toString() : "";
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit()
                .putString(KEY_PHASE_PREFIX + transactionId, phase.name())
                .putString(KEY_STATUS_PREFIX + transactionId, st)
                .apply();
    }

    @Nullable
    private Phase loadLocalPhase() {
        if (transactionId <= 0) return null;

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String p = sp.getString(KEY_PHASE_PREFIX + transactionId, null);
        if (p == null || p.trim().isEmpty()) return null;

        try { return Phase.valueOf(p); }
        catch (Exception ignored) {}
        return null;
    }

    @Nullable
    private String loadLocalStatusText() {
        if (transactionId <= 0) return null;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String s = sp.getString(KEY_STATUS_PREFIX + transactionId, null);
        return (s != null && !s.trim().isEmpty()) ? s : null;
    }

    private void clearLocalPhase(long txId) {
        if (txId <= 0) return;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit()
                .remove(KEY_PHASE_PREFIX + txId)
                .remove(KEY_STATUS_PREFIX + txId)
                .apply();
    }

    private void finishTaskAndGoHome(@NonNull String reason) {
        if (completedFlowStarted) return;
        completedFlowStarted = true;

        clearLocalPhase(transactionId);
        removeTxFromLocal(transactionId);
        markTransactionCompleted(transactionId);
        clearFcmAssignmentTrigger();

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            Toast.makeText(this, "Task Completed", Toast.LENGTH_SHORT).show();

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Intent home = new Intent(TaskDetailActivity.this, HomeActivity.class);
                home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(home);
                finish();
            }, 350);
        });
    }

    private boolean hasLocationPerm() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasPickupCoords() {
        return !Double.isNaN(pickupLat) && !Double.isNaN(pickupLng);
    }

    private boolean hasDeliveryCoords() {
        return !Double.isNaN(deliveryLat) && !Double.isNaN(deliveryLng);
    }

    private interface FloatCallback { void onValue(float meters); }

    private void distanceToPickupOnce(@NonNull FloatCallback cb) {
        if (!hasPickupCoords() || !hasLocationPerm()) { cb.onValue(Float.NaN); return; }
        if (fusedClient == null) fusedClient = LocationServices.getFusedLocationProviderClient(this);

        Location wl = lastWatcherLoc;
        if (wl != null) {
            float[] out = new float[1];
            Location.distanceBetween(wl.getLatitude(), wl.getLongitude(), pickupLat, pickupLng, out);
            cb.onValue(out[0]);
            return;
        }

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
        fusedClient.getLastLocation()
                .addOnSuccessListener(last -> {
                    if (last != null) {
                        float[] out = new float[1];
                        Location.distanceBetween(last.getLatitude(), last.getLongitude(), pickupLat, pickupLng, out);
                        cb.onValue(out[0]);
                        return;
                    }

                    fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                            .addOnSuccessListener(loc -> {
                                if (loc == null) { cb.onValue(Float.NaN); return; }
                                float[] out2 = new float[1];
                                Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), pickupLat, pickupLng, out2);
                                cb.onValue(out2[0]);
                            })
                            .addOnFailureListener(e -> cb.onValue(Float.NaN));
                })
                .addOnFailureListener(e -> cb.onValue(Float.NaN));
    }

    private void distanceToDeliveryOnce(@NonNull FloatCallback cb) {
        if (!hasDeliveryCoords() || !hasLocationPerm()) { cb.onValue(Float.NaN); return; }
        if (fusedClient == null) fusedClient = LocationServices.getFusedLocationProviderClient(this);

        Location wl = lastWatcherLoc;
        if (wl != null) {
            float[] out = new float[1];
            Location.distanceBetween(wl.getLatitude(), wl.getLongitude(), deliveryLat, deliveryLng, out);
            cb.onValue(out[0]);
            return;
        }

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
        fusedClient.getLastLocation()
                .addOnSuccessListener(last -> {
                    if (last != null) {
                        float[] out = new float[1];
                        Location.distanceBetween(last.getLatitude(), last.getLongitude(), deliveryLat, deliveryLng, out);
                        cb.onValue(out[0]);
                        return;
                    }

                    fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                            .addOnSuccessListener(loc -> {
                                if (loc == null) { cb.onValue(Float.NaN); return; }
                                float[] out2 = new float[1];
                                Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), deliveryLat, deliveryLng, out2);
                                cb.onValue(out2[0]);
                            })
                            .addOnFailureListener(e -> cb.onValue(Float.NaN));
                })
                .addOnFailureListener(e -> cb.onValue(Float.NaN));
    }

    private void showGateDialog(@NonNull String title, @NonNull String msg) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("OK", (d, w) -> d.dismiss())
                .show();
    }

    private void gatePickupOrAlert(@NonNull String actionName, @NonNull Runnable allowed, @NonNull Runnable reset) {
        if (!hasPickupCoords()) { showGateDialog("Pickup", "Pickup location missing."); reset.run(); return; }
        if (!hasLocationPerm()) { showGateDialog("Location Required", "Enable location permission to " + actionName + "."); reset.run(); return; }

        distanceToPickupOnce(m -> runOnUiThread(() -> {
            if (Float.isNaN(m)) { showGateDialog("Location Error", "Unable to get your location. Turn on GPS and try again."); reset.run(); return; }
            if (m > RADIUS_500M) { showGateDialog("Too Far", "You are " + Math.round(m) + "m away.\n\nMove within 500m to " + actionName + "."); reset.run(); return; }
            allowed.run();
        }));
    }

    private void gateDeliveryOrAlert(@NonNull String actionName, @NonNull Runnable allowed, @NonNull Runnable reset) {
        if (!hasDeliveryCoords()) { showGateDialog("Delivery", "Delivery location missing."); reset.run(); return; }
        if (!hasLocationPerm()) { showGateDialog("Location Required", "Enable location permission to " + actionName + "."); reset.run(); return; }

        distanceToDeliveryOnce(m -> runOnUiThread(() -> {
            if (Float.isNaN(m)) { showGateDialog("Location Error", "Unable to get your location. Turn on GPS and try again."); reset.run(); return; }
            if (m > RADIUS_500M) { showGateDialog("Too Far", "You are " + Math.round(m) + "m away.\n\nMove within 500m to " + actionName + "."); reset.run(); return; }
            allowed.run();
        }));
    }

    private float metersToPickup(@NonNull Location loc) {
        float[] out = new float[1];
        Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), pickupLat, pickupLng, out);
        return out[0];
    }

    private float metersToDelivery(@NonNull Location loc) {
        float[] out = new float[1];
        Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), deliveryLat, deliveryLng, out);
        return out[0];
    }

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    private void startAutoArriveWatcher() {
        if (fusedClient == null) fusedClient = LocationServices.getFusedLocationProviderClient(this);
        if (!hasLocationPerm()) return;
        if (autoCb != null) return;

        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
                .setMinUpdateIntervalMillis(2000L)
                .setMinUpdateDistanceMeters(10f)
                .build();

        autoCb = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null) return;

                lastWatcherLoc = loc;

                if (!pickupAutoArrivedSent
                        && !pickupAutoInFlight
                        && currentPhase == Phase.PICKUP_STARTED
                        && pickupStep == 1
                        && pickupId > 0
                        && hasPickupCoords()) {

                    float m = metersToPickup(loc);
                    // ✅ CHANGED: 100m
                    if (m <= RADIUS_500M) {
                        pickupAutoInFlight = true;
                        Log.d(TAG, "AUTO pickup arrived triggered at " + m + "m");
                        callStatusRaw(pickupId, "arrived",
                                () -> {
                                    pickupAutoArrivedSent = true;
                                    pickupAutoInFlight = false;
                                    tvTaskStatus.setText("Pickup Arrived");
                                    applyPhaseUI(Phase.PICKUP_ARRIVED);
                                    broadcastPhase(Phase.PICKUP_ARRIVED);
                                },
                                () -> pickupAutoInFlight = false
                        );
                    }
                }

                if (!deliveryAutoArrivedSent
                        && !deliveryAutoInFlight
                        && currentPhase == Phase.DELIVERY_STARTED
                        && deliveryStep == 1
                        && deliveryId > 0
                        && hasDeliveryCoords()) {

                    float m = metersToDelivery(loc);
                    // ✅ CHANGED: 100m
                    if (m <= RADIUS_500M) {
                        deliveryAutoInFlight = true;
                        Log.d(TAG, "AUTO delivery arrived triggered at " + m + "m");
                        callStatusRaw(deliveryId, "arrived",
                                () -> {
                                    deliveryAutoArrivedSent = true;
                                    deliveryAutoInFlight = false;
                                    tvTaskStatus.setText("Delivery Arrived");
                                    applyPhaseUI(Phase.DELIVERY_ARRIVED);
                                    broadcastPhase(Phase.DELIVERY_ARRIVED);
                                },
                                () -> deliveryAutoInFlight = false
                        );
                    }
                }
            }
        };

        fusedClient.requestLocationUpdates(req, autoCb, Looper.getMainLooper());
    }

    private void stopAutoArriveWatcher() {
        if (fusedClient != null && autoCb != null) {
            try { fusedClient.removeLocationUpdates(autoCb); } catch (Exception ignored) {}
        }
        autoCb = null;
        lastWatcherLoc = null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task_details);

        api = ApiClient.get().create(ApiService.class);
        fusedClient = LocationServices.getFusedLocationProviderClient(this);

        paciMapView = findViewById(R.id.paciMapView);
        ArcGISVectorTiledLayer layer = new ArcGISVectorTiledLayer(
                "https://kuwaitportal.paci.gov.kw/arcgisportal/rest/services/Hosted/PACIKFBasemap/VectorTileServer");
        ArcGISMap map = new ArcGISMap(new Basemap(layer));
        paciMapView.setMap(map);
        paciMapView.setViewpointAsync(
                new Viewpoint(new Point(47.9783, 29.3759, SpatialReferences.getWgs84()), 10000)
        );

        LinearLayout bottomSheet = findViewById(R.id.bottomSheet);
        if (bottomSheet != null) {
            bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);
            bottomSheetBehavior.setPeekHeight(0);
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }

        ivBack = findViewById(R.id.ivBack);
        ivCall = findViewById(R.id.ivCall);
        ivWhatsApp = findViewById(R.id.ivWhatsApp);
        ivDirection = findViewById(R.id.ivDirection);

        tvTaskId = findViewById(R.id.tvTaskId);
        tvTaskTimeType = findViewById(R.id.tvTaskTimeType);
        tvTaskStatus = findViewById(R.id.tvTaskStatus);
        tvTaskAddress = findViewById(R.id.tvTaskAddress);
        tvTaskDetails = findViewById(R.id.tvTaskDetails);
        tvOrderId = findViewById(R.id.tvOrderId);
        tvPickupAddress = findViewById(R.id.tvPickupAddress);
        tvDeliveryAddress = findViewById(R.id.tvDeliveryAddress);

        swipePickup = findViewById(R.id.swipePickupArrive);
        swipeDelivery = findViewById(R.id.swipeDeliveryArrive);

        progressUpdate = findViewById(R.id.progressUpdate);
        setUpdating(false);

        ivBack.setOnClickListener(v -> finish());

        Intent it = getIntent();
        transactionId = it.getLongExtra("transaction_id", 0);
        pickupId      = it.getLongExtra("pickup_id", 0);
        deliveryId    = it.getLongExtra("delivery_id", 0);

        pickupAddress = it.getStringExtra("pickup_address");
        pickupPhone   = it.getStringExtra("pickup_phone");
        pickupLat     = it.getDoubleExtra("pickup_lat", Double.NaN);
        pickupLng     = it.getDoubleExtra("pickup_lng", Double.NaN);

        deliveryAddress = it.getStringExtra("delivery_address");
        deliveryPhone   = it.getStringExtra("delivery_phone");
        deliveryLat     = it.getDoubleExtra("delivery_lat", Double.NaN);
        deliveryLng     = it.getDoubleExtra("delivery_lng", Double.NaN);

        paymentType = it.getStringExtra("payment_type");
        orderAmount = it.getStringExtra("order_amount");
        orderId     = it.getStringExtra("order_id");

        currentPhaseStr = it.getStringExtra("current_phase");
        pickupRowIdForBroadcast = it.getLongExtra("pickup_row_id", pickupId);

        if (transactionId > 0) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_ACTIVE_TX, transactionId)
                    .apply();
        }

        tvTaskId.setText(String.valueOf(transactionId));
        tvPickupAddress.setText((pickupAddress != null ? pickupAddress : "-") + " (Pickup)");
        tvDeliveryAddress.setText((deliveryAddress != null ? deliveryAddress : "-") + " (Delivery)");

        String details = "Task from Verdi";
        if (paymentType != null && !paymentType.isEmpty()) details += ", Payment: " + paymentType;
        if (orderAmount != null && !orderAmount.isEmpty()) details += ", Order Amount: " + orderAmount + " KD";
        tvTaskDetails.setText(details);
        tvOrderId.setText(orderId != null ? "ORDER ID " + orderId : "");

        if (!Double.isNaN(pickupLat) && !Double.isNaN(pickupLng)) {
            paciMapView.setViewpointAsync(
                    new Viewpoint(new Point(pickupLng, pickupLat, SpatialReferences.getWgs84()), 10000)
            );
        }

        ivCall.setOnClickListener(v -> openDialForCurrentStage());

        ivWhatsApp.setOnClickListener(v -> openWhatsAppForCurrentStage());

        ivDirection.setOnClickListener(v -> {
            if (transactionId <= 0) {
                Toast.makeText(this, "Invalid transaction id", Toast.LENGTH_SHORT).show();
                return;
            }
            fetchTaskDetailsThenOpenStageLocation(transactionId);
        });

        swipePickup.setText("Start Pickup");
        swipeDelivery.setText("Start Delivery");

        Phase phase = loadLocalPhase();
        if (phase == null) {
            try { if (currentPhaseStr != null) phase = Phase.valueOf(currentPhaseStr); } catch (Exception ignored) {}
        }
        if (phase == null) phase = Phase.PICKUP_STARTABLE;

        applyPhaseUI(phase);

        String savedStatus = loadLocalStatusText();
        if (savedStatus != null) tvTaskStatus.setText(savedStatus);

        swipePickup.setOnSlideCompleteListener(view -> {
            Log.d(TAG, "SWIPE pickupStep=" + pickupStep + " tx=" + transactionId);

            if (pickupId <= 0) {
                Toast.makeText(this, "No pickup row id", Toast.LENGTH_SHORT).show();
                swipePickup.resetSlider();
                return;
            }

            if (pickupStep == 0) {
                callStatusRaw(pickupId, "started",
                        () -> {
                            tvTaskStatus.setText("Pickup Started");
                            applyPhaseUI(Phase.PICKUP_STARTED);
                            broadcastPhase(Phase.PICKUP_STARTED);
                        },
                        swipePickup::resetSlider
                );
                return;
            }

            if (pickupStep == 1) {
                gatePickupOrAlert("mark Pickup Arrived",
                        () -> callStatusRaw(pickupId, "arrived",
                                () -> {
                                    tvTaskStatus.setText("Pickup Arrived");
                                    applyPhaseUI(Phase.PICKUP_ARRIVED);
                                    broadcastPhase(Phase.PICKUP_ARRIVED);
                                },
                                swipePickup::resetSlider
                        ),
                        swipePickup::resetSlider
                );
                return;
            }

            if (pickupStep == 2) {
                gatePickupOrAlert("Complete Pickup",
                        () -> callStatusRaw(pickupId, "success",
                                () -> {
                                    tvTaskStatus.setText("Pickup Completed");
                                    applyPhaseUI(Phase.PICKUP_COMPLETED);
                                    broadcastPhase(Phase.PICKUP_COMPLETED);
                                    autoStartDeliveryAfterPickupCompleted("pickup_swipe_success");
                                },
                                swipePickup::resetSlider
                        ),
                        swipePickup::resetSlider
                );
                return;
            }

            swipePickup.resetSlider();
        });

        swipeDelivery.setOnSlideCompleteListener(view -> {
            Log.d(TAG, "SWIPE deliveryStep=" + deliveryStep + " tx=" + transactionId);

            if (deliveryStep == 0) {
                final Runnable proceedLocal = () -> {
                    tvTaskStatus.setText("Delivery Started");
                    applyPhaseUI(Phase.DELIVERY_STARTED);
                    broadcastPhase(Phase.DELIVERY_STARTED);
                };

                if (deliveryId <= 0) {
                    Toast.makeText(this, "No delivery row id — proceeding locally", Toast.LENGTH_SHORT).show();
                    proceedLocal.run();
                    return;
                }

                callStatusRaw(deliveryId, "started",
                        proceedLocal,
                        () -> {
                            Toast.makeText(this, "Server rejected 'started' — continuing locally", Toast.LENGTH_SHORT).show();
                            proceedLocal.run();
                        }
                );
                return;
            }

            if (deliveryStep == 1) {
                if (deliveryId <= 0) {
                    Toast.makeText(this, "No delivery row id", Toast.LENGTH_SHORT).show();
                    swipeDelivery.resetSlider();
                    return;
                }

                gateDeliveryOrAlert("mark Delivery Arrived",
                        () -> callStatusRaw(deliveryId, "arrived",
                                () -> {
                                    tvTaskStatus.setText("Delivery Arrived");
                                    applyPhaseUI(Phase.DELIVERY_ARRIVED);
                                    broadcastPhase(Phase.DELIVERY_ARRIVED);
                                },
                                swipeDelivery::resetSlider
                        ),
                        swipeDelivery::resetSlider
                );
                return;
            }

            if (deliveryStep == 2) {
                if (deliveryId <= 0) {
                    Toast.makeText(this, "No delivery row id", Toast.LENGTH_SHORT).show();
                    swipeDelivery.resetSlider();
                    return;
                }

                gateDeliveryOrAlert("Complete Delivery",
                        () -> callStatusRaw(deliveryId, "success",
                                () -> {
                                    tvTaskStatus.setText("Delivery Completed");
                                    applyPhaseUI(Phase.DELIVERY_COMPLETED);
                                    broadcastPhase(Phase.DELIVERY_COMPLETED);
                                    finishTaskAndGoHome("swipe_delivery_success");
                                },
                                swipeDelivery::resetSlider
                        ),
                        swipeDelivery::resetSlider
                );
                return;
            }

            swipeDelivery.resetSlider();
        });

        registerLbmReceiversIfNeeded();
    }

    // =========================================================
    // Direction click -> Call API -> open maps
    // =========================================================
    private void fetchTaskDetailsThenOpenStageLocation(long txId) {
        String bearer = AuthPrefs.bearer(this);
        if (bearer == null || bearer.trim().isEmpty()) {
            Toast.makeText(this, "Session expired. Login again.", Toast.LENGTH_SHORT).show();
            return;
        }

        setUpdating(true);

        api.getTaskDetails(bearer, txId).enqueue(new Callback<com.example.loginapp.net.model.TaskDetailsResponse>() {
            @Override
            public void onResponse(Call<com.example.loginapp.net.model.TaskDetailsResponse> call,
                                   Response<com.example.loginapp.net.model.TaskDetailsResponse> res) {

                setUpdating(false);

                if (!res.isSuccessful() || res.body() == null || !res.body().success || res.body().data == null) {
                    Toast.makeText(TaskDetailActivity.this, "Task details failed: " + res.code(), Toast.LENGTH_SHORT).show();
                    return;
                }

                com.example.loginapp.net.model.TaskDetailsResponse.Data d = res.body().data;

                if (d.pickup_task != null) {
                    pickupPhone = d.pickup_task.phone;
                }

                if (d.delivery_task != null) {
                    deliveryPhone = d.delivery_task.phone;
                }

                double apiPickupLat = safeParseDouble(d.pickup_task != null ? d.pickup_task.lat : null);
                double apiPickupLng = safeParseDouble(d.pickup_task != null ? d.pickup_task.lng : null);

                double apiDeliveryLat = safeParseDouble(d.delivery_task != null ? d.delivery_task.lat : null);
                double apiDeliveryLng = safeParseDouble(d.delivery_task != null ? d.delivery_task.lng : null);

                // keep latest API truth locally too
                if (!Double.isNaN(apiPickupLat) && !Double.isNaN(apiPickupLng)) {
                    pickupLat = apiPickupLat;
                    pickupLng = apiPickupLng;
                }

                if (!Double.isNaN(apiDeliveryLat) && !Double.isNaN(apiDeliveryLng)) {
                    deliveryLat = apiDeliveryLat;
                    deliveryLng = apiDeliveryLng;
                }

                boolean isDeliveryStage =
                        currentPhase == Phase.PICKUP_COMPLETED ||
                                currentPhase == Phase.DELIVERY_STARTED ||
                                currentPhase == Phase.DELIVERY_ARRIVED ||
                                currentPhase == Phase.DELIVERY_COMPLETED;

                if (isDeliveryStage) {
                    if (Double.isNaN(apiDeliveryLat) || Double.isNaN(apiDeliveryLng)) {
                        Toast.makeText(TaskDetailActivity.this, "Delivery coordinates missing", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    openGoogleMapsNavigation(apiDeliveryLat, apiDeliveryLng);
                } else {
                    if (Double.isNaN(apiPickupLat) || Double.isNaN(apiPickupLng)) {
                        Toast.makeText(TaskDetailActivity.this, "Pickup coordinates missing", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    openGoogleMapsNavigation(apiPickupLat, apiPickupLng);
                }
            }

            @Override
            public void onFailure(Call<com.example.loginapp.net.model.TaskDetailsResponse> call, Throwable t) {
                setUpdating(false);
                Toast.makeText(TaskDetailActivity.this, "Network error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openGoogleMapsNavigation(double lat, double lng) {
        Uri uri = Uri.parse("google.navigation:q=" + lat + "," + lng + "&mode=d");

        Intent navIntent = new Intent(Intent.ACTION_VIEW, uri);
        navIntent.setPackage("com.google.android.apps.maps");

        try {
            startActivity(navIntent);
        } catch (Exception e) {
            Intent fallbackIntent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + lat + "," + lng + "&travelmode=driving")
            );
            startActivity(fallbackIntent);
        }
    }

    private double safeParseDouble(@Nullable String s) {
        if (s == null) return Double.NaN;
        try {
            String t = s.trim();
            if (t.isEmpty() || t.equalsIgnoreCase("null")) return Double.NaN;
            return Double.parseDouble(t);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    // =========================================================
    // PUSH → PHASE
    // =========================================================
    private void applyStatusFromAdminPush(@Nullable String status, long pushedTaskTxId) {
        if (status == null) return;

        String s = status.trim().toLowerCase();
        boolean isDelivery = (pushedTaskTxId > 0 && pushedTaskTxId == deliveryId);

        if (s.contains("started")) {
            if (isDelivery) { tvTaskStatus.setText("Delivery Started"); applyPhaseUI(Phase.DELIVERY_STARTED); }
            else { tvTaskStatus.setText("Pickup Started"); applyPhaseUI(Phase.PICKUP_STARTED); }
            return;
        }

        if (s.contains("arrived")) {
            if (isDelivery) { tvTaskStatus.setText("Delivery Arrived"); applyPhaseUI(Phase.DELIVERY_ARRIVED); }
            else { tvTaskStatus.setText("Pickup Arrived"); applyPhaseUI(Phase.PICKUP_ARRIVED); }
            return;
        }

        if (s.contains("success") || s.contains("completed")) {
            if (isDelivery) {
                tvTaskStatus.setText("Delivery Completed");
                applyPhaseUI(Phase.DELIVERY_COMPLETED);
                finishTaskAndGoHome("admin_push_delivery_completed");
            } else {
                tvTaskStatus.setText("Pickup Completed");
                applyPhaseUI(Phase.PICKUP_COMPLETED);
                autoStartDeliveryAfterPickupCompleted("admin_push_pickup_completed");
            }
        }
    }

    private void applyFromTaskDetailsStatuses(@Nullable String pickupTaskStatus,
                                              @Nullable String deliveryTaskStatus) {

        String p = pickupTaskStatus != null ? pickupTaskStatus.trim().toLowerCase() : "";
        String d = deliveryTaskStatus != null ? deliveryTaskStatus.trim().toLowerCase() : "";

        if (!d.isEmpty()) {
            if (d.contains("success") || d.contains("completed")) {
                tvTaskStatus.setText("Delivery Completed");
                applyPhaseUI(Phase.DELIVERY_COMPLETED);
                finishTaskAndGoHome("api_task_details_delivery_completed");
                return;
            }
            if (d.contains("arrived")) { tvTaskStatus.setText("Delivery Arrived"); applyPhaseUI(Phase.DELIVERY_ARRIVED); return; }
            if (d.contains("started")) { tvTaskStatus.setText("Delivery Started"); applyPhaseUI(Phase.DELIVERY_STARTED); return; }
        }

        if (!p.isEmpty()) {
            if (p.contains("success") || p.contains("completed")) {
                tvTaskStatus.setText("Pickup Completed");
                applyPhaseUI(Phase.PICKUP_COMPLETED);
                autoStartDeliveryAfterPickupCompleted("api_task_details_pickup_completed");
                return;
            }
            if (p.contains("arrived")) { tvTaskStatus.setText("Pickup Arrived"); applyPhaseUI(Phase.PICKUP_ARRIVED); return; }
            if (p.contains("started") || p.contains("pending")) {
                tvTaskStatus.setText("Pickup Started");
                applyPhaseUI(Phase.PICKUP_STARTED);
            }
        }
    }

    private void applyPhaseUI(@NonNull Phase phase) {
        currentPhase = phase;

        if (phase == Phase.PICKUP_STARTED) {
            pickupAutoArrivedSent = false;
            pickupAutoInFlight = false;
        }
        if (phase == Phase.DELIVERY_STARTED) {
            deliveryAutoArrivedSent = false;
            deliveryAutoInFlight = false;
        }

        findViewById(R.id.layoutPickupStage).setVisibility(LinearLayout.VISIBLE);
        findViewById(R.id.layoutDeliveryStage).setVisibility(LinearLayout.GONE);

        tvTaskTimeType.setText("Pickup");
        tvTaskAddress.setText(pickupAddress != null ? pickupAddress : "-");

        if (!Double.isNaN(pickupLat) && !Double.isNaN(pickupLng)) {
            paciMapView.setViewpointAsync(new Viewpoint(
                    new Point(pickupLng, pickupLat, SpatialReferences.getWgs84()), 10000));
        }

        swipePickup.resetSlider();
        swipeDelivery.resetSlider();
        swipePickup.setEnabled(true);
        swipeDelivery.setEnabled(false);

        switch (phase) {
            case PICKUP_STARTABLE:
                pickupStep = 0;
                swipePickup.setText("Start Pickup");
                swipeDelivery.setText("Start Delivery");
                break;

            case PICKUP_STARTED:
                pickupStep = 1;
                swipePickup.setText("Arrive at Pickup");
                swipeDelivery.setText("Start Delivery");
                break;

            case PICKUP_ARRIVED:
                pickupStep = 2;
                swipePickup.setText("Complete Pickup");
                swipeDelivery.setText("Start Delivery");
                break;

            case PICKUP_COMPLETED:
                findViewById(R.id.layoutPickupStage).setVisibility(LinearLayout.GONE);
                findViewById(R.id.layoutDeliveryStage).setVisibility(LinearLayout.VISIBLE);

                tvTaskTimeType.setText("Delivery");
                tvTaskAddress.setText(deliveryAddress != null ? deliveryAddress : "-");

                if (!Double.isNaN(deliveryLat) && !Double.isNaN(deliveryLng)) {
                    paciMapView.setViewpointAsync(new Viewpoint(
                            new Point(deliveryLng, deliveryLat, SpatialReferences.getWgs84()), 10000));
                }

                swipePickup.setEnabled(false);
                deliveryStep = 0;
                swipeDelivery.setEnabled(true);
                swipeDelivery.setText("Start Delivery");
                break;

            case DELIVERY_STARTED:
                findViewById(R.id.layoutPickupStage).setVisibility(LinearLayout.GONE);
                findViewById(R.id.layoutDeliveryStage).setVisibility(LinearLayout.VISIBLE);

                tvTaskTimeType.setText("Delivery");
                tvTaskAddress.setText(deliveryAddress != null ? deliveryAddress : "-");

                if (!Double.isNaN(deliveryLat) && !Double.isNaN(deliveryLng)) {
                    paciMapView.setViewpointAsync(new Viewpoint(
                            new Point(deliveryLng, deliveryLat, SpatialReferences.getWgs84()), 10000));
                }

                deliveryStep = 1;
                swipeDelivery.setEnabled(true);
                swipeDelivery.setText("Arrive at Dropoff");
                swipePickup.setEnabled(false);
                break;

            case DELIVERY_ARRIVED:
                findViewById(R.id.layoutPickupStage).setVisibility(LinearLayout.GONE);
                findViewById(R.id.layoutDeliveryStage).setVisibility(LinearLayout.VISIBLE);

                tvTaskTimeType.setText("Delivery");
                tvTaskAddress.setText(deliveryAddress != null ? deliveryAddress : "-");

                if (!Double.isNaN(deliveryLat) && !Double.isNaN(deliveryLng)) {
                    paciMapView.setViewpointAsync(new Viewpoint(
                            new Point(deliveryLng, deliveryLat, SpatialReferences.getWgs84()), 10000));
                }

                deliveryStep = 2;
                swipeDelivery.setEnabled(true);
                swipeDelivery.setText("Complete Delivery");
                swipePickup.setEnabled(false);
                break;

            case DELIVERY_COMPLETED:
                swipeDelivery.setEnabled(false);
                swipePickup.setEnabled(false);
                break;
        }

        saveLocalPhase(phase);
    }

    private void broadcastPhase(Phase phase) {
        Intent i = new Intent(ACTION_TASK_PHASE);
        i.putExtra("row_id", pickupRowIdForBroadcast);
        i.putExtra("transaction_id", transactionId);
        i.putExtra("phase", phase.name());
        sendBroadcast(i);
    }

    private void setUpdating(boolean updating) {
        if (progressUpdate != null) {
            progressUpdate.setVisibility(updating ? View.VISIBLE : View.GONE);
        }
        if (swipePickup != null) swipePickup.setEnabled(!updating && findViewById(R.id.layoutPickupStage).getVisibility() == View.VISIBLE);
        if (swipeDelivery != null) swipeDelivery.setEnabled(!updating && findViewById(R.id.layoutDeliveryStage).getVisibility() == View.VISIBLE);
    }

    private void callStatusRaw(long rowId, String status, Runnable ok, Runnable err) {
        String bearer = AuthPrefs.bearer(this);

        runOnUiThread(() -> setUpdating(true));

        api.updateTaskStatus(bearer, rowId, status).enqueue(new Callback<GenericResponse>() {
            @Override public void onResponse(Call<GenericResponse> call, Response<GenericResponse> res) {
                runOnUiThread(() -> setUpdating(false));

                if (res.isSuccessful() && res.body() != null && res.body().success) {
                    if (ok != null) ok.run();
                } else {
                    Log.w(TAG, "updateTaskStatus failed: status=" + status + " rowId=" + rowId + " http=" + res.code());
                    if (err != null) err.run();
                }
            }

            @Override public void onFailure(Call<GenericResponse> call, Throwable t) {
                Log.e(TAG, "updateTaskStatus error: status=" + status + " rowId=" + rowId, t);
                runOnUiThread(() -> setUpdating(false));
                if (err != null) err.run();
            }
        });
    }

    private void stopOwnershipWatcher() {
        if (ownershipReg != null) {
            try { ownershipReg.remove(); } catch (Exception ignored) {}
        }
        ownershipReg = null;
    }

    private void removeTxFromLocal(long txId) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

        java.util.HashSet<String> set = new java.util.HashSet<>(
                sp.getStringSet(KEY_ACCEPTED_TX_IDS, new java.util.HashSet<>())
        );
        set.remove(String.valueOf(txId));
        sp.edit().putStringSet(KEY_ACCEPTED_TX_IDS, set).apply();

        long active = sp.getLong(KEY_ACTIVE_TX, 0L);
        if (active == txId) sp.edit().putLong(KEY_ACTIVE_TX, 0L).apply();
    }

    private void clearFcmAssignmentTrigger() {
        long driverId = AuthPrefs.driverId(this);
        if (driverId <= 0) return;

        DocumentReference ref = FirebaseFirestore.getInstance()
                .collection("drivers")
                .document(String.valueOf(driverId));

        Map<String, Object> u = new HashMap<>();
        ref.set(u, SetOptions.merge());
    }

    private void markTransactionCompleted(long txId) {
        long driverId = AuthPrefs.driverId(this);
        if (driverId <= 0) return;

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        DocumentReference ref = db.collection("drivers").document(String.valueOf(driverId));

        db.runTransaction(tr -> {
            Map<String, Object> upd = new HashMap<>();
            tr.set(ref, upd, SetOptions.merge());
            return null;
        }).addOnFailureListener(e -> Log.e(TAG, "markTransactionCompleted failed txId=" + txId, e));
    }

    private void refreshTruthFromServer() {
        String bearer = AuthPrefs.bearer(this);
        if (bearer == null || bearer.trim().isEmpty() || transactionId <= 0) return;

        api.getTaskDetails(bearer, transactionId).enqueue(new retrofit2.Callback<com.example.loginapp.net.model.TaskDetailsResponse>() {
            @Override
            public void onResponse(retrofit2.Call<com.example.loginapp.net.model.TaskDetailsResponse> call,
                                   retrofit2.Response<com.example.loginapp.net.model.TaskDetailsResponse> res) {
                if (!res.isSuccessful() || res.body() == null || !res.body().success || res.body().data == null) return;

                com.example.loginapp.net.model.TaskDetailsResponse.Data d = res.body().data;
                if (d.pickup_task != null) {
                    pickupPhone = d.pickup_task.phone;
                }

                if (d.delivery_task != null) {
                    deliveryPhone = d.delivery_task.phone;
                }


                String pickupSt   = (d.pickup_task != null) ? d.pickup_task.task_status : null;
                String deliverySt = (d.delivery_task != null) ? d.delivery_task.task_status : null;

                runOnUiThread(() -> applyFromTaskDetailsStatuses(pickupSt, deliverySt));
            }

            @Override public void onFailure(retrofit2.Call<com.example.loginapp.net.model.TaskDetailsResponse> call, Throwable t) { }
        });
    }

    // =========================================================
    // ✅ Lifecycle
    // =========================================================
    @Override protected void onResume() {
        super.onResume();
        if (paciMapView != null) paciMapView.resume();

        registerLbmReceiversIfNeeded();
        refreshTruthFromServer();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        startAutoArriveWatcher();
    }

    @Override protected void onPause() {
        if (paciMapView != null) paciMapView.pause();

        stopAutoArriveWatcher();
        stopOwnershipWatcher();

        // DO NOT unregister here
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopOwnershipWatcher();
        stopAutoArriveWatcher();

        unregisterLbmReceivers();

        if (paciMapView != null) paciMapView.dispose();
        super.onDestroy();
    }

    // =========================================================
    // Auto-start delivery after pickup complete
    // =========================================================
    private void autoStartDeliveryAfterPickupCompleted(@NonNull String reason) {
        if (currentPhase == Phase.DELIVERY_STARTED
                || currentPhase == Phase.DELIVERY_ARRIVED
                || currentPhase == Phase.DELIVERY_COMPLETED) return;

        if (deliveryId <= 0) {
            Log.w(TAG, "autoStartDeliveryAfterPickupCompleted: no deliveryId, local. reason=" + reason);
            tvTaskStatus.setText("Delivery Started");
            applyPhaseUI(Phase.DELIVERY_STARTED);
            broadcastPhase(Phase.DELIVERY_STARTED);
            return;
        }

        callStatusRaw(deliveryId, "started",
                () -> {
                    tvTaskStatus.setText("Delivery Started");
                    applyPhaseUI(Phase.DELIVERY_STARTED);
                    broadcastPhase(Phase.DELIVERY_STARTED);
                },
                () -> {
                    tvTaskStatus.setText("Delivery Started");
                    applyPhaseUI(Phase.DELIVERY_STARTED);
                    broadcastPhase(Phase.DELIVERY_STARTED);
                }
        );
    }

    // =========================================================
    // Revoke notification
    // =========================================================
    private void ensureRevokeChannel() {
        if (Build.VERSION.SDK_INT < 26) return;

        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        android.app.NotificationChannel existing = nm.getNotificationChannel(CH_REVOKE);
        if (existing != null) return;

        android.net.Uri soundUri = android.net.Uri.parse(
                "android.resource://" + getPackageName() + "/" + R.raw.un_rem
        );

        android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        android.app.NotificationChannel ch = new android.app.NotificationChannel(
                CH_REVOKE,
                "Task Updates",
                android.app.NotificationManager.IMPORTANCE_HIGH
        );
        ch.enableVibration(true);
        ch.setSound(soundUri, attrs);

        nm.createNotificationChannel(ch);
    }

    private void showRevokeNotification(@NonNull String title, @NonNull String message) {
        ensureRevokeChannel();

        if (Build.VERSION.SDK_INT >= 33 &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted - cannot show notification");
            return;
        }

        Intent tap = new Intent(this, TaskDetailActivity.class);
        tap.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        tap.putExtra("transaction_id", transactionId);
        tap.putExtra("pickup_id", pickupId);
        tap.putExtra("delivery_id", deliveryId);

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
                new androidx.core.app.NotificationCompat.Builder(this, CH_REVOKE)
                        .setSmallIcon(R.drawable.ic_notifications_24)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(message))
                        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pi)
                        .setSound(soundUri);

        androidx.core.app.NotificationManagerCompat.from(this)
                .notify(NOTIF_REVOKE_ID, b.build());
    }

    // (Kept from your paste; not used)
    private void showSmsStyleRevokeNotification(@NonNull String title, @NonNull String message) {
        ensureRevokeChannel();

        if (Build.VERSION.SDK_INT >= 33) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted - cannot show notification");
                return;
            }
        }

        Intent tap = new Intent(this, HomeActivity.class);
        tap.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

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
                new androidx.core.app.NotificationCompat.Builder(this, CH_REVOKE)
                        .setSmallIcon(R.drawable.ic_notifications_24)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(message))
                        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pi)
                        .setSound(soundUri);

        androidx.core.app.NotificationManagerCompat.from(this)
                .notify(NOTIF_REVOKE_ID, b.build());
    }

    @Nullable
    private String getCurrentStagePhone() {
        boolean isDeliveryStage =
                currentPhase == Phase.PICKUP_COMPLETED ||
                        currentPhase == Phase.DELIVERY_STARTED ||
                        currentPhase == Phase.DELIVERY_ARRIVED ||
                        currentPhase == Phase.DELIVERY_COMPLETED;

        String phone = isDeliveryStage ? deliveryPhone : pickupPhone;
        if (phone == null) return null;

        phone = phone.trim().replace(" ", "").replace("-", "");
        if (phone.isEmpty() || phone.equalsIgnoreCase("null")) return null;

        return phone;
    }

    private void openDialForCurrentStage() {
        String p = getCurrentStagePhone();
        if (p == null) {
            Toast.makeText(this, "Phone not available", Toast.LENGTH_SHORT).show();
            return;
        }

        String dialNumber = p.startsWith("+") ? p : "+965" + p;
        startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + dialNumber)));
    }

    private void openWhatsAppForCurrentStage() {
        String p = getCurrentStagePhone();
        if (p == null) {
            Toast.makeText(this, "Phone not available", Toast.LENGTH_SHORT).show();
            return;
        }

        String waNumber = p.startsWith("+") ? p.substring(1) : "965" + p;

        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + waNumber));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "WhatsApp not available", Toast.LENGTH_SHORT).show();
        }
    }
}

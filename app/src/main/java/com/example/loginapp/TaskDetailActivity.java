// app/src/main/java/com/example/loginapp/TaskDetailActivity.java
package com.example.loginapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

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
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;
import com.ncorti.slidetoact.SlideToActView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class TaskDetailActivity extends AppCompatActivity {

    private static final String TAG = "TaskDetail";

    private static final String PREFS = "verdi_prefs";
    private static final String KEY_ACCEPTED_TX_IDS = "accepted_tx_ids_set";
    private static final String KEY_ACTIVE_TX = "active_txid";

    private static final String ACTION_TASK_PHASE = "com.example.loginapp.ACTION_TASK_PHASE";

    private MapView paciMapView;
    private BottomSheetBehavior<LinearLayout> bottomSheetBehavior;

    private ImageView ivBack, ivCall, ivWhatsApp, ivDirection;
    private TextView tvTaskId, tvTaskTimeType, tvTaskStatus, tvTaskAddress, tvTaskDetails, tvOrderId;
    private TextView tvPickupAddress, tvDeliveryAddress;

    private SlideToActView swipePickup;
    private SlideToActView swipeDelivery;

    private ApiService api;

    private long transactionId, pickupId, deliveryId;

    private String pickupAddress, pickupPhone;
    private double pickupLat, pickupLng;

    private String deliveryAddress, deliveryPhone;
    private double deliveryLat, deliveryLng;

    private String paymentType, orderAmount, orderId;

    // 0 = before start, 1 = started (awaiting arrive), 2 = arrived (awaiting complete)
    private int pickupStep = 0;
    private int deliveryStep = 0;

    private String currentPhaseStr;
    private long pickupRowIdForBroadcast;

    // 🔎 Realtime watch of “ownership” of the current transaction
    private ListenerRegistration ownershipReg;
    private boolean revokeDialogShown = false;

    private enum Phase {
        PICKUP_STARTABLE,
        PICKUP_STARTED,
        PICKUP_ARRIVED,
        PICKUP_COMPLETED,
        DELIVERY_STARTED,
        DELIVERY_ARRIVED,
        DELIVERY_COMPLETED
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task_details);

        api = ApiClient.get().create(ApiService.class);

        // Map setup
        paciMapView = findViewById(R.id.paciMapView);
        ArcGISVectorTiledLayer layer = new ArcGISVectorTiledLayer(
                "https://kuwaitportal.paci.gov.kw/arcgisportal/rest/services/Hosted/PACIKFBasemap/VectorTileServer");
        ArcGISMap map = new ArcGISMap(new Basemap(layer));
        paciMapView.setMap(map);
        paciMapView.setViewpointAsync(
                new Viewpoint(
                        new Point(47.9783, 29.3759, SpatialReferences.getWgs84()), // Kuwait-ish center
                        10000
                )
        );

        // BottomSheet setup
        LinearLayout bottomSheet = findViewById(R.id.bottomSheet);
        if (bottomSheet != null) {
            bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);
            bottomSheetBehavior.setPeekHeight(0);
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }

        // Views
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

        ivBack.setOnClickListener(v -> finish());

        // Read intent extras
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

        currentPhaseStr = it.getStringExtra("current_phase");   // e.g., "PICKUP_STARTED"
        pickupRowIdForBroadcast = it.getLongExtra("pickup_row_id", pickupId);

        // Populate UI basics
        tvTaskId.setText(String.valueOf(transactionId));
        tvPickupAddress.setText((pickupAddress != null ? pickupAddress : "-") + " (Pickup)");
        tvDeliveryAddress.setText((deliveryAddress != null ? deliveryAddress : "-") + " (Delivery)");

        String details = "Task from Verdi";
        if (paymentType != null && !paymentType.isEmpty()) {
            details += ", Payment: " + paymentType;
        }
        if (orderAmount != null && !orderAmount.isEmpty()) {
            details += ", Order Amount: " + orderAmount + " KD";
        }
        tvTaskDetails.setText(details);

        tvOrderId.setText(orderId != null ? "ORDER ID " + orderId : "");

        // Focus map on pickup first if coords available
        if (!Double.isNaN(pickupLat) && !Double.isNaN(pickupLng)) {
            paciMapView.setViewpointAsync(
                    new Viewpoint(
                            new Point(pickupLng, pickupLat, SpatialReferences.getWgs84()),
                            10000
                    )
            );
        }

        // Call / WhatsApp / Navigation actions
        ivCall.setOnClickListener(v -> {
            String p = pickupPhone != null ? pickupPhone : "";
            if (!p.isEmpty()) {
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:+965" + p)));
            } else {
                Toast.makeText(this, "Phone not available", Toast.LENGTH_SHORT).show();
            }
        });

        ivWhatsApp.setOnClickListener(v -> {
            String p = pickupPhone != null ? pickupPhone : "";
            if (!p.isEmpty()) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + p)));
            } else {
                Toast.makeText(this, "Phone not available", Toast.LENGTH_SHORT).show();
            }
        });

        ivDirection.setOnClickListener(v -> {
            String origin = buildPlaceParamPreferCoords(pickupLat, pickupLng, pickupAddress);            // pickup
            String dest   = buildPlaceParamPreferAddress(deliveryLat, deliveryLng, deliveryAddress);     // delivery (prefer address)

            if (origin == null || dest == null) {
                Toast.makeText(this, "Missing pickup/delivery location", Toast.LENGTH_SHORT).show();
                return;
            }

            Uri uri = Uri.parse(
                    "https://www.google.com/maps/dir/?api=1"
                            + "&origin=" + origin
                            + "&destination=" + dest
                            + "&travelmode=driving"
            );

            Intent intent2 = new Intent(Intent.ACTION_VIEW, uri);
            intent2.setPackage("com.google.android.apps.maps");

            try {
                startActivity(intent2);
            } catch (Exception e) {
                // fallback (no package)
                intent2.setPackage(null);
                startActivity(intent2);
            }
        });

        // Initial slider labels
        swipePickup.setText("Arrive at Pickup");
        swipeDelivery.setText("Start Delivery");

        // Phase & UI state
        Phase phase = Phase.PICKUP_STARTED; // fallback default
        try {
            if (currentPhaseStr != null) {
                phase = Phase.valueOf(currentPhaseStr);
            }
        } catch (Exception ignored) {}
        applyPhaseUI(phase);

        // PICKUP slider flow: Arrive -> Complete
        swipePickup.setOnSlideCompleteListener(view -> {
            if (pickupStep == 0) {
                // ARRIVE @ pickup (row = pickupId)
                callStatusRaw(
                        pickupId,
                        "arrived",
                        () -> {
                            pickupStep = 1;
                            tvTaskStatus.setText("Pickup Arrived");
                            swipePickup.setText("Complete Pickup");
                            swipePickup.resetSlider();
                            broadcastPhase(Phase.PICKUP_ARRIVED);
                        },
                        swipePickup::resetSlider
                );
            } else {
                // COMPLETE pickup (row = pickupId)
                callStatusRaw(
                        pickupId,
                        "success",
                        () -> {
                            tvTaskStatus.setText("Pickup Completed");
                            broadcastPhase(Phase.PICKUP_COMPLETED);

                            // Switch UI to delivery stage
                            findViewById(R.id.layoutPickupStage).setVisibility(LinearLayout.GONE);
                            findViewById(R.id.layoutDeliveryStage).setVisibility(LinearLayout.VISIBLE);

                            tvTaskTimeType.setText("Delivery");
                            tvTaskAddress.setText(deliveryAddress != null ? deliveryAddress : "-");

                            if (!Double.isNaN(deliveryLat) && !Double.isNaN(deliveryLng)) {
                                paciMapView.setViewpointAsync(
                                        new Viewpoint(
                                                new Point(deliveryLng, deliveryLat, SpatialReferences.getWgs84()),
                                                10000
                                        )
                                );
                            }

                            // reset delivery flow
                            deliveryStep = 0;
                            swipeDelivery.setEnabled(true);
                            swipeDelivery.setText("Start Delivery");
                            swipeDelivery.resetSlider();
                        },
                        swipePickup::resetSlider
                );
            }
        });

        // DELIVERY slider flow: Start -> Arrive -> Complete
        swipeDelivery.setOnSlideCompleteListener(view -> {
            if (deliveryStep == 0) {
                // START delivery (row = deliveryId)
                final Runnable proceedLocal = () -> {
                    deliveryStep = 1;
                    tvTaskStatus.setText("Delivery Started");
                    swipeDelivery.setText("Arrive at Dropoff");
                    swipeDelivery.resetSlider();
                    broadcastPhase(Phase.DELIVERY_STARTED);
                };

                if (deliveryId <= 0) {
                    Toast.makeText(this, "No delivery row id — proceeding locally", Toast.LENGTH_SHORT).show();
                    proceedLocal.run();
                    return;
                }

                callStatusRaw(
                        deliveryId,
                        "started",
                        proceedLocal,
                        () -> {
                            Toast.makeText(this, "Server rejected 'started' — continuing locally", Toast.LENGTH_SHORT).show();
                            proceedLocal.run();
                        }
                );

            } else if (deliveryStep == 1) {
                // ARRIVE at delivery
                callStatusRaw(
                        deliveryId,
                        "arrived",
                        () -> {
                            deliveryStep = 2;
                            tvTaskStatus.setText("Delivery Arrived");
                            swipeDelivery.setText("Complete Delivery");
                            swipeDelivery.resetSlider();
                            broadcastPhase(Phase.DELIVERY_ARRIVED);
                        },
                        swipeDelivery::resetSlider
                );

            } else if (deliveryStep == 2) {
                // COMPLETE delivery
                callStatusRaw(
                        deliveryId,
                        "success",
                        () -> {
                            tvTaskStatus.setText("Delivery Completed");
                            broadcastPhase(Phase.DELIVERY_COMPLETED);

                            // Local + Firestore cleanup
                            removeTxFromLocal(transactionId);
                            markTransactionCompleted(transactionId);

                            // Go back Home
                            new android.os.Handler().postDelayed(() -> {
                                Intent home = new Intent(TaskDetailActivity.this, HomeActivity.class);
                                home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(home);
                                finish();
                            }, 800);
                        },
                        swipeDelivery::resetSlider
                );

            } else {
                swipeDelivery.resetSlider();
            }
        });
    }

    // Start listening to confirm this task is still assigned to me
    private void startOwnershipWatcher() {
        stopOwnershipWatcher();
        long driverId = AuthPrefs.driverId(this);
        if (driverId <= 0 || transactionId <= 0) return;

        DocumentReference ref = FirebaseFirestore.getInstance()
                .collection("drivers")
                .document(String.valueOf(driverId));

        ownershipReg = ref.addSnapshotListener((snap, err) -> {
            if (err != null || snap == null || !snap.exists()) return;

            boolean inActive = containsLong(snap.get("active_transactions"), transactionId);
            boolean inAssigned = containsLong(snap.get("assigned_transactions"), transactionId);

            if (!inActive && !inAssigned) {
                onTaskRevoked();
            }
        });
    }

    private void stopOwnershipWatcher() {
        if (ownershipReg != null) {
            try {
                ownershipReg.remove();
            } catch (Exception ignored) {}
            ownershipReg = null;
        }
    }

    private boolean containsLong(Object listObj, long target) {
        if (!(listObj instanceof List<?>)) return false;
        for (Object o : (List<?>) listObj) {
            if (o == null) continue;
            try {
                long v = (o instanceof Number)
                        ? ((Number) o).longValue()
                        : Long.parseLong(String.valueOf(o).trim());
                if (v == target) return true;
            } catch (Exception ignore) {}
        }
        return false;
    }

    private void onTaskRevoked() {
        if (revokeDialogShown || isFinishing() || isDestroyed()) return;
        revokeDialogShown = true;

        removeTxFromLocal(transactionId);

        runOnUiThread(() -> {
            new AlertDialog.Builder(TaskDetailActivity.this)
                    .setTitle("Assignment Removed")
                    .setMessage("Your current assignment #" + transactionId + " was removed by dispatch.")
                    .setCancelable(false)
                    .setPositiveButton("OK", (d, w) -> {
                        Intent home = new Intent(TaskDetailActivity.this, HomeActivity.class);
                        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(home);
                        finish();
                    })
                    .show();
        });
    }

    // local SharedPreferences cleanup for accepted_tx_ids_set & active_txid
    private void removeTxFromLocal(long txId) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

        // accepted_tx_ids_set -> remove txId
        java.util.HashSet<String> set = new java.util.HashSet<>(
                sp.getStringSet(KEY_ACCEPTED_TX_IDS, new java.util.HashSet<>())
        );
        set.remove(String.valueOf(txId));
        sp.edit().putStringSet(KEY_ACCEPTED_TX_IDS, set).apply();

        // active_txid -> clear if matches
        long active = sp.getLong(KEY_ACTIVE_TX, 0L);
        if (active == txId) {
            sp.edit().putLong(KEY_ACTIVE_TX, 0L).apply();
        }
    }

    // prefer coordinates for origin (pickup leg)
    private String buildPlaceParamPreferCoords(double lat, double lng, String address) {
        if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
            return lat + "," + lng;
        }
        if (address != null && !address.trim().isEmpty()) {
            return Uri.encode(address);
        }
        return null;
    }

    // prefer human-readable address for destination (dropoff leg)
    private String buildPlaceParamPreferAddress(double lat, double lng, String address) {
        if (address != null && !address.trim().isEmpty()) {
            // normalize spellings Kuwait Maps struggles with
            String norm = address
                    .replaceAll("(?i)mohabulla|mohabullah|muhabulla|mahbula", "Mahboula")
                    .trim();
            return Uri.encode(norm);
        }
        if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
            return lat + "," + lng;
        }
        return null;
    }

    // apply UI state for a given phase
    private void applyPhaseUI(Phase phase) {
        // default view assumes pickup stage visible, delivery hidden
        findViewById(R.id.layoutPickupStage).setVisibility(LinearLayout.VISIBLE);
        findViewById(R.id.layoutDeliveryStage).setVisibility(LinearLayout.GONE);

        tvTaskTimeType.setText("Pickup");
        tvTaskAddress.setText(pickupAddress != null ? pickupAddress : "-");
        tvTaskStatus.setText("Pickup Started");

        if (!Double.isNaN(pickupLat) && !Double.isNaN(pickupLng)) {
            paciMapView.setViewpointAsync(
                    new Viewpoint(
                            new Point(pickupLng, pickupLat, SpatialReferences.getWgs84()),
                            10000
                    )
            );
        }

        // can't move delivery slider yet unless pickup done
        swipeDelivery.setEnabled(false);

        switch (phase) {
            case PICKUP_STARTABLE:
            case PICKUP_STARTED:
                pickupStep = 0;
                tvTaskStatus.setText("Pickup Started");
                swipePickup.setText("Arrive at Pickup");
                break;

            case PICKUP_ARRIVED:
                pickupStep = 1;
                tvTaskStatus.setText("Pickup Arrived");
                swipePickup.setText("Complete Pickup");
                break;

            case PICKUP_COMPLETED:
                // flip UI to delivery stage
                findViewById(R.id.layoutPickupStage).setVisibility(LinearLayout.GONE);
                findViewById(R.id.layoutDeliveryStage).setVisibility(LinearLayout.VISIBLE);

                tvTaskTimeType.setText("Delivery");
                tvTaskAddress.setText(deliveryAddress != null ? deliveryAddress : "-");
                if (!Double.isNaN(deliveryLat) && !Double.isNaN(deliveryLng)) {
                    paciMapView.setViewpointAsync(
                            new Viewpoint(
                                    new Point(deliveryLng, deliveryLat, SpatialReferences.getWgs84()),
                                    10000
                            )
                    );
                }

                deliveryStep = 0;
                tvTaskStatus.setText("Pickup Completed");
                swipeDelivery.setEnabled(true);
                swipeDelivery.setText("Start Delivery");
                break;

            case DELIVERY_STARTED:
                findViewById(R.id.layoutPickupStage).setVisibility(LinearLayout.GONE);
                findViewById(R.id.layoutDeliveryStage).setVisibility(LinearLayout.VISIBLE);

                tvTaskTimeType.setText("Delivery");
                tvTaskAddress.setText(deliveryAddress != null ? deliveryAddress : "-");
                if (!Double.isNaN(deliveryLat) && !Double.isNaN(deliveryLng)) {
                    paciMapView.setViewpointAsync(
                            new Viewpoint(
                                    new Point(deliveryLng, deliveryLat, SpatialReferences.getWgs84()),
                                    10000
                            )
                    );
                }

                deliveryStep = 1;
                tvTaskStatus.setText("Delivery Started");
                swipeDelivery.setEnabled(true);
                swipeDelivery.setText("Arrive at Dropoff");
                break;

            case DELIVERY_ARRIVED:
                findViewById(R.id.layoutPickupStage).setVisibility(LinearLayout.GONE);
                findViewById(R.id.layoutDeliveryStage).setVisibility(LinearLayout.VISIBLE);

                tvTaskTimeType.setText("Delivery");
                tvTaskAddress.setText(deliveryAddress != null ? deliveryAddress : "-");
                if (!Double.isNaN(deliveryLat) && !Double.isNaN(deliveryLng)) {
                    paciMapView.setViewpointAsync(
                            new Viewpoint(
                                    new Point(deliveryLng, deliveryLat, SpatialReferences.getWgs84()),
                                    10000
                            )
                    );
                }

                deliveryStep = 2;
                tvTaskStatus.setText("Delivery Arrived");
                swipeDelivery.setEnabled(true);
                swipeDelivery.setText("Complete Delivery");
                break;

            case DELIVERY_COMPLETED:
                // final state
                findViewById(R.id.layoutPickupStage).setVisibility(LinearLayout.GONE);
                findViewById(R.id.layoutDeliveryStage).setVisibility(LinearLayout.VISIBLE);

                tvTaskTimeType.setText("Delivery");
                tvTaskAddress.setText(deliveryAddress != null ? deliveryAddress : "-");
                tvTaskStatus.setText("Delivery Completed");

                swipeDelivery.setEnabled(false);
                swipePickup.setEnabled(false);
                break;
        }
    }

    // tell HomeActivity / others about phase change
    private void broadcastPhase(Phase phase) {
        Intent i = new Intent(ACTION_TASK_PHASE);
        i.putExtra("row_id", pickupRowIdForBroadcast);
        i.putExtra("transaction_id", transactionId);
        i.putExtra("phase", phase.name());
        sendBroadcast(i);
    }

    // hit backend to mark task status and run callbacks
    private void callStatusRaw(long rowId, String status, Runnable ok, Runnable err) {
        String bearer = AuthPrefs.bearer(this);

        api.updateTaskStatus(bearer, rowId, status).enqueue(new Callback<GenericResponse>() {
            @Override
            public void onResponse(Call<GenericResponse> call, Response<GenericResponse> res) {
                if (res.isSuccessful()
                        && res.body() != null
                        && res.body().success) {
                    if (ok != null) ok.run();
                } else {
                    Log.w(
                            TAG,
                            "updateTaskStatus failed: status=" + status +
                                    " rowId=" + rowId +
                                    " http=" + res.code()
                    );
                    if (err != null) err.run();
                }
            }

            @Override
            public void onFailure(Call<GenericResponse> call, Throwable t) {
                Log.e(
                        TAG,
                        "updateTaskStatus error: status=" + status +
                                " rowId=" + rowId,
                        t
                );
                if (err != null) err.run();
            }
        });
    }

    /**
     * After delivery is completed:
     * - Remove txId from active_transactions
     * - havingtask = active_transactions not empty
     * - completed_transactions:
     *      * If completed_date == today's Kuwait date → append txId (no duplicate)
     *      * Else (new day) → replace list with only this txId
     * - completed_date ALWAYS set to today's Kuwait date
     * - task_updated_at gets serverTimestamp()
     */
    private void markTransactionCompleted(long txId) {
        long driverId = AuthPrefs.driverId(this);
        if (driverId <= 0) return;

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        DocumentReference ref = db.collection("drivers").document(String.valueOf(driverId));

        db.runTransaction(tr -> {
            com.google.firebase.firestore.DocumentSnapshot snap = tr.get(ref);

            // 1. figure out "today" (now, Kuwait time)
            java.util.Calendar cal = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("Asia/Kuwait")
            );
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Kuwait"));
            String todayStr = sdf.format(cal.getTime()); // e.g. "2025-10-27"

            // 2. remove this txId from active_transactions
            List<Long> active = (List<Long>) snap.get("active_transactions");
            if (active == null) active = new ArrayList<>();
            active = new ArrayList<>(active); // mutable copy

            for (int i = active.size() - 1; i >= 0; i--) {
                Long v = active.get(i);
                if (v != null && v.longValue() == txId) {
                    active.remove(i);
                }
            }

            boolean stillHasTask = !active.isEmpty();

            // 3. today's completed_transactions
            String storedDay = null;
            Object storedDayObj = snap.get("completed_date");
            if (storedDayObj != null) {
                storedDay = String.valueOf(storedDayObj);
            }

            List<Long> todayCompleted;
            if (storedDay != null && storedDay.equals(todayStr)) {
                // same Kuwait day -> just append if needed
                List<Long> existing = (List<Long>) snap.get("completed_transactions");
                if (existing == null) existing = new ArrayList<>();
                todayCompleted = new ArrayList<>(existing);

                boolean alreadyThere = false;
                for (Long v : todayCompleted) {
                    if (v != null && v.longValue() == txId) {
                        alreadyThere = true;
                        break;
                    }
                }
                if (!alreadyThere) {
                    todayCompleted.add(txId);
                }
            } else {
                // new day in Kuwait -> wipe old list, start fresh
                todayCompleted = new ArrayList<>();
                todayCompleted.add(txId);
            }

            // 4. build update
            Map<String, Object> upd = new HashMap<>();
            upd.put("active_transactions", active);
            upd.put("havingtask", stillHasTask);

            // dashboard/app reads this
            upd.put("completed_transactions", todayCompleted);

            // ALWAYS write today's Kuwait date so it visibly changes day by day
            upd.put("completed_date", todayStr);

            // audit + "last activity" timestamp
            upd.put("driver_id", driverId);
            upd.put("task_updated_at", FieldValue.serverTimestamp());

            tr.set(ref, upd, SetOptions.merge());
            return null;
        }).addOnFailureListener(e -> {
            Log.e(TAG, "markTransactionCompleted failed txId=" + txId, e);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (paciMapView != null) paciMapView.resume();
        startOwnershipWatcher();   // listen to Firestore ownership of this tx
    }

    @Override
    protected void onPause() {
        if (paciMapView != null) paciMapView.pause();
        stopOwnershipWatcher();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopOwnershipWatcher();
        if (paciMapView != null) paciMapView.dispose();
        super.onDestroy();
    }
}

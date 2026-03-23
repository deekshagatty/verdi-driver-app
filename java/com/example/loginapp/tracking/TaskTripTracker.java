package com.example.loginapp.tracking;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.loginapp.AuthPrefs;
import com.example.loginapp.SettingsPrefs;
import com.example.loginapp.net.ApiClient;
import com.example.loginapp.net.ApiService;
import com.example.loginapp.net.model.GenericResponse;
import com.example.loginapp.net.model.TaskDetailsResponse;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public final class TaskTripTracker {

    private static final String TAG = "TaskTripTracker";

    private static final long DETAILS_REFRESH_MS = 15_000L;

    private final Context appCtx;
    private final ApiService api;

    private long activeTxId = 0L;

    private long pickupTaskTxnId = 0L;
    private long deliveryTaskTxnId = 0L;

    private boolean pickupDone = false;
    private boolean deliveryDone = false;

    private long lastDetailsAt = 0L;

    private volatile boolean fetching = false;
    private volatile boolean sending = false;

    @Nullable private Location lastSent = null;

    public TaskTripTracker(@NonNull Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.api = ApiClient.get().create(ApiService.class);
    }

    public void setActiveTx(long txId) {
        if (txId <= 0) {
            reset();
            return;
        }
        if (this.activeTxId != txId) {
            this.activeTxId = txId;
            this.pickupTaskTxnId = 0L;
            this.deliveryTaskTxnId = 0L;
            this.pickupDone = false;
            this.deliveryDone = false;
            this.lastDetailsAt = 0L;
            this.lastSent = null;
            this.fetching = false;
            this.sending = false;
            Log.d(TAG, "ActiveTx changed -> " + txId);
        }
    }

    /** Call on every new location (only when onDuty) */
    public void onLocation(@NonNull Location loc) {
        if (activeTxId <= 0) return;
        if (pickupDone && deliveryDone) return;

        long now = System.currentTimeMillis();
        if ((now - lastDetailsAt) > DETAILS_REFRESH_MS || (pickupTaskTxnId <= 0 && deliveryTaskTxnId <= 0)) {
            fetchDetails();
        }

        long taskTxnId = currentTaskTransactionId();
        if (taskTxnId <= 0) return;

        if (shouldSend(loc)) {
            sendTrip(taskTxnId, loc);
        }
    }

    private boolean shouldSend(@NonNull Location loc) {
        if (lastSent == null) return true;

        // ✅ dynamic meters from Ably "trip_polling"
        float meters = SettingsPrefs.tripPollingMeters(appCtx);

        float d = lastSent.distanceTo(loc);
        return d >= meters;
    }

    private long currentTaskTransactionId() {
        if (!pickupDone) return pickupTaskTxnId;     // pickup stage
        if (!deliveryDone) return deliveryTaskTxnId; // delivery stage
        return 0L;
    }

    private void fetchDetails() {
        if (fetching) return;

        String bearer = AuthPrefs.bearer(appCtx);
        if (bearer == null || bearer.trim().isEmpty()) return;

        fetching = true;
        lastDetailsAt = System.currentTimeMillis();

        api.getTaskDetails(bearer, activeTxId).enqueue(new Callback<TaskDetailsResponse>() {
            @Override
            public void onResponse(@NonNull Call<TaskDetailsResponse> call, @NonNull Response<TaskDetailsResponse> res) {
                fetching = false;
                if (!res.isSuccessful() || res.body() == null || !res.body().success || res.body().data == null) return;

                TaskDetailsResponse.Data d = res.body().data;

                pickupTaskTxnId = (d.pickup_task != null) ? d.pickup_task.id : 0L;
                deliveryTaskTxnId = (d.delivery_task != null) ? d.delivery_task.id : 0L;

                String pStat = (d.pickup_task != null) ? d.pickup_task.task_status : null;
                String dlStat = (d.delivery_task != null) ? d.delivery_task.task_status : null;

                pickupDone = isSuccess(pStat);
                deliveryDone = isSuccess(dlStat);

                Log.d(TAG, "details tx=" + activeTxId +
                        " pickupId=" + pickupTaskTxnId + " pickupDone=" + pickupDone +
                        " deliveryId=" + deliveryTaskTxnId + " deliveryDone=" + deliveryDone);

                if (pickupDone && deliveryDone) {
                    Log.d(TAG, "tx " + activeTxId + " fully completed -> stop trip tracking");
                }
            }

            @Override
            public void onFailure(@NonNull Call<TaskDetailsResponse> call, @NonNull Throwable t) {
                fetching = false;
            }
        });
    }

    private void sendTrip(long taskTransactionId, @NonNull Location loc) {
        if (sending) return;

        long driverId = AuthPrefs.driverId(appCtx);
        if (driverId <= 0) {
            Log.w(TAG, "driverId=0 -> skip");
            return;
        }

        String bearer = AuthPrefs.bearer(appCtx);
        if (bearer == null || bearer.trim().isEmpty()) {
            Log.w(TAG, "bearer missing -> skip");
            return;
        }

        double lat = loc.getLatitude();
        double lng = loc.getLongitude();

        RequestBody tid = rb(String.valueOf(taskTransactionId));
        RequestBody did = rb(String.valueOf(driverId));
        RequestBody la  = rb(String.valueOf(lat));
        RequestBody lo  = rb(String.valueOf(lng));

        sending = true;

        api.saveDriverTripLocation(bearer, tid, did, la, lo).enqueue(new Callback<GenericResponse>() {
            @Override
            public void onResponse(@NonNull Call<GenericResponse> call, @NonNull Response<GenericResponse> res) {
                sending = false;
                if (res.isSuccessful() && res.body() != null && res.body().success) {
                    lastSent = new Location(loc);
                    Log.d(TAG, "✅ trip saved task_transaction_id=" + taskTransactionId +
                            " driver_id=" + driverId + " lat=" + lat + " lng=" + lng +
                            " meters=" + SettingsPrefs.tripPollingMeters(appCtx));
                } else {
                    Log.w(TAG, "trip save failed code=" + res.code());
                }
            }

            @Override
            public void onFailure(@NonNull Call<GenericResponse> call, @NonNull Throwable t) {
                sending = false;
                Log.w(TAG, "trip save error: " + t.getMessage());
            }
        });
    }

    private static RequestBody rb(String v) {
        return RequestBody.create(v, MediaType.parse("text/plain"));
    }

    private static boolean isSuccess(@Nullable String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase();
        return t.contains("success") || t.contains("completed");
    }

    private void reset() {
        activeTxId = 0L;
        pickupTaskTxnId = 0L;
        deliveryTaskTxnId = 0L;
        pickupDone = false;
        deliveryDone = false;
        lastDetailsAt = 0L;
        fetching = false;
        sending = false;
        lastSent = null;
    }
}

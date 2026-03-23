package com.example.loginapp;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.loginapp.net.ApiClient;
import com.example.loginapp.net.ApiService;
import com.example.loginapp.net.model.GenericResponse;
import com.example.loginapp.net.model.TaskDetailsResponse;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class TaskPopup {
    private TaskPopup() {}

    private static final String PREFS = "verdi_prefs";
    private static final String KEY_ACCEPTED_TX_IDS = "accepted_tx_ids_set";
    private static final String KEY_LAST_TXID = "last_seen_txid";
    public static final String ACTION_TX_ACCEPTED = "com.example.loginapp.ACTION_TX_ACCEPTED";

    private static java.lang.ref.WeakReference<BottomSheetDialog> sDialogRef =
            new java.lang.ref.WeakReference<>(null);
    private static Long sVisibleTxId = null;

    // Prevent double "Accept" races across components
    private static final Set<Long> sAcceptInFlight =
            Collections.synchronizedSet(new HashSet<>());

    public static boolean isShowing() {
        BottomSheetDialog d = sDialogRef.get();
        return d != null && d.isShowing();
    }

    public static void show(Activity activity, long txId) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        BottomSheetDialog current = sDialogRef.get();
        if (current != null && current.isShowing()) {
            if (sVisibleTxId != null && sVisibleTxId == txId) return;
            return;
        }

        sVisibleTxId = txId;

        ApiService api = ApiClient.get().create(ApiService.class);

        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        View v = LayoutInflater.from(activity).inflate(R.layout.view_task_popup, null, false);
        dialog.setContentView(v);
        dialog.setCancelable(false);
        dialog.setOnDismissListener(d -> {
            sVisibleTxId = null;
            sDialogRef = new java.lang.ref.WeakReference<>(null);
            activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                    .edit().remove(KEY_LAST_TXID).apply();
        });

        TextView tvTitle   = v.findViewById(R.id.tvTitle);
        TextView tvPickup  = v.findViewById(R.id.tvPickup);
        TextView tvDrop    = v.findViewById(R.id.tvDrop);
        TextView tvType    = v.findViewById(R.id.tvType);
        TextView tvStatus  = v.findViewById(R.id.tvStatus);
        TextView tvCreated = v.findViewById(R.id.tvCreated);
        Button btnAccept   = v.findViewById(R.id.btnAccept);
        Button btnDismiss  = v.findViewById(R.id.btnDismiss);

        if (tvTitle != null) tvTitle.setText("New Assignment #" + txId);

        String bearer = AuthPrefs.bearer(activity);
        if (bearer == null || bearer.isEmpty()) {
            Toast.makeText(activity, "Login missing token", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            return;
        }

        api.getTaskDetails(bearer, txId)
                .enqueue(new retrofit2.Callback<TaskDetailsResponse>() {
                    @Override
                    public void onResponse(retrofit2.Call<TaskDetailsResponse> call,
                                           retrofit2.Response<TaskDetailsResponse> res) {
                        if (!res.isSuccessful() || res.body() == null || !res.body().success || res.body().data == null) {
                            Toast.makeText(activity, "Failed to load task", Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                            return;
                        }
                        TaskDetailsResponse.Data d = res.body().data;

                        if (tvPickup != null)
                            tvPickup.setText("Pickup: " + ((d.pickup_task != null && d.pickup_task.address != null) ? d.pickup_task.address : "-"));
                        if (tvDrop != null)
                            tvDrop.setText("Drop: " + ((d.delivery_task != null && d.delivery_task.address != null) ? d.delivery_task.address : "-"));
                        if (tvType != null)
                            tvType.setText("Task Type: " + (d.type != null ? d.type :
                                    (d.pickup_task != null && d.pickup_task.task_type != null ? d.pickup_task.task_type : "-")));
                        if (tvStatus != null)
                            tvStatus.setText("Task Status: " + (d.status != null ? d.status :
                                    (d.pickup_task != null && d.pickup_task.task_status != null ? d.pickup_task.task_status :
                                            (d.delivery_task != null && d.delivery_task.task_status != null ? d.delivery_task.task_status : "-"))));
                        if (tvCreated != null) tvCreated.setText("Created: " + prettyTime(d.created_at));

                        if (btnAccept != null) btnAccept.setOnClickListener(b -> accept(activity, bearer, d, dialog));
                        if (btnDismiss != null) btnDismiss.setOnClickListener(b -> dialog.dismiss());
                    }

                    @Override
                    public void onFailure(retrofit2.Call<TaskDetailsResponse> call, Throwable t) {
                        Toast.makeText(activity, "Network error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    }
                });

        sDialogRef = new java.lang.ref.WeakReference<>(dialog);
        dialog.show();
    }

    private static void accept(Activity a, String bearer, TaskDetailsResponse.Data d, BottomSheetDialog dialog) {
        ApiService api = ApiClient.get().create(ApiService.class);
        long driverId = AuthPrefs.driverId(a);
        if (driverId <= 0) {
            Toast.makeText(a, "Missing driver id", Toast.LENGTH_SHORT).show();
            return;
        }

        // In-flight guard per transaction
        if (!sAcceptInFlight.add(d.id)) {
            // already accepting somewhere
            return;
        }

        api.assignDriver(bearer, d.id, driverId, "accepted").enqueue(new retrofit2.Callback<GenericResponse>() {
            @Override public void onResponse(retrofit2.Call<GenericResponse> call,
                                             retrofit2.Response<GenericResponse> res) {
                sAcceptInFlight.remove(d.id);

                if (!res.isSuccessful() || res.body() == null || !res.body().success) {
                    String msg = (res.code() == 409)
                            ? "Task already accepted by another driver."
                            : "Assign driver failed";
                    Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
                    return;
                }

                // SUCCESS: No update_task_status("accepted") calls here.
                addAcceptedTxId(a, d.id);              // keep
                FsDriver.setHavingTask(a, true);      // local only
                FsDriver.setLastTxId(a, String.valueOf(d.id));
                FsDriver.setActiveTx(a, d.id);        // local only


                // Notify app
                android.content.Intent accepted = new android.content.Intent(ACTION_TX_ACCEPTED);
                accepted.putExtra("accepted_tx", d.id);
                a.sendBroadcast(accepted);

                Toast.makeText(a, "Accepted.", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            }

            @Override public void onFailure(retrofit2.Call<GenericResponse> call, Throwable t) {
                sAcceptInFlight.remove(d.id);
                Toast.makeText(a, "Assign driver error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void addAcceptedTxId(Activity a, long txId) {
        SharedPreferences sp = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        HashSet<String> set = new HashSet<>(sp.getStringSet(KEY_ACCEPTED_TX_IDS, new HashSet<>()));
        set.add(String.valueOf(txId));
        sp.edit().putStringSet(KEY_ACCEPTED_TX_IDS, set).apply();
    }

    private static String prettyTime(String iso) {
        try {
            String trimmed = iso;
            int dot = trimmed.indexOf('.');
            if (dot > 0) trimmed = trimmed.substring(0, dot) + "Z";
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            in.setLenient(true);
            Date d = in.parse(trimmed);
            SimpleDateFormat out = new SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault());
            return d != null ? out.format(d) : iso;
        } catch (Exception e) {
            return iso;
        }
    }
}

package com.example.loginapp;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.loginapp.net.ApiClient;
import com.example.loginapp.net.ApiService;
import com.example.loginapp.net.model.GenericResponse;
import com.example.loginapp.net.model.TaskDetailsResponse;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class TaskDialogActivity extends AppCompatActivity {
    public static final String EXTRA_TX_ID = "extra_tx_id";
    private ApiService api;
    private long txId = 0L;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = ApiClient.get().create(ApiService.class);

        txId = getIntent().getLongExtra(EXTRA_TX_ID, 0L);
        if (txId <= 0) {
            finish();
            return;
        }
        fetchAndShow(txId);
    }

    private void fetchAndShow(long txId) {
        String token = AuthPrefs.token(this);
        String bearer = (token == null || token.isEmpty()) ? "" : "Bearer " + token;
        api.getTaskDetails(bearer, txId).enqueue(new Callback<TaskDetailsResponse>() {
            @Override public void onResponse(Call<TaskDetailsResponse> call,
                                             Response<TaskDetailsResponse> res) {
                if (!res.isSuccessful() || res.body() == null || !res.body().success || res.body().data == null) {
                    finish();
                    return;
                }
                showSheet(res.body().data);
            }
            @Override public void onFailure(Call<TaskDetailsResponse> call, Throwable t) { finish(); }
        });
    }

    private void showSheet(TaskDetailsResponse.Data d) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        final var v = getLayoutInflater().inflate(R.layout.layout_assignment_popup, null, false);
        dialog.setContentView(v);
        dialog.setOnDismissListener(di -> finish());

        TextView tvTitle   = v.findViewById(R.id.tvTitle);
        TextView tvPickup  = v.findViewById(R.id.tvPickup);
        TextView tvDrop    = v.findViewById(R.id.tvDrop);
        TextView tvType    = v.findViewById(R.id.tvType);
        TextView tvStatus  = v.findViewById(R.id.tvStatus);
        TextView tvCreated = v.findViewById(R.id.tvCreated);
        Button btnAccept   = v.findViewById(R.id.btnOpen);
        Button btnDismiss  = v.findViewById(R.id.btnDismiss);

        String pickupAddr = (d.pickup_task != null && d.pickup_task.address != null) ? d.pickup_task.address : "-";
        String dropAddr   = (d.delivery_task != null && d.delivery_task.address != null) ? d.delivery_task.address : "-";
        String taskType   = (d.type != null) ? d.type :
                (d.pickup_task != null && d.pickup_task.task_type != null ? d.pickup_task.task_type : "-");
        String taskStatus = (d.status != null) ? d.status :
                (d.pickup_task != null && d.pickup_task.task_status != null ? d.pickup_task.task_status :
                        (d.delivery_task != null && d.delivery_task.task_status != null ? d.delivery_task.task_status : "-"));

        if (tvTitle != null) tvTitle.setText("New Assignment #" + d.id);
        if (tvPickup != null) tvPickup.setText("Pickup: " + pickupAddr);
        if (tvDrop != null) tvDrop.setText("Drop: " + dropAddr);
        if (tvType != null) tvType.setText("Task Type: " + taskType);
        if (tvStatus != null) tvStatus.setText("Task Status: " + taskStatus);
        if (tvCreated != null && d.created_at != null) tvCreated.setText("Created: " + prettyTime(d.created_at));

        btnAccept.setText("Accept");
        btnAccept.setOnClickListener(b -> acceptAndQueue(d, dialog));
        btnDismiss.setOnClickListener(b -> dialog.dismiss());

        dialog.show();
    }

    private void acceptAndQueue(TaskDetailsResponse.Data d, BottomSheetDialog dialog) {
        long driverId = AuthPrefs.driverId(this);
        if (driverId <= 0) {
            Toast.makeText(this, "Login missing driverId", Toast.LENGTH_SHORT).show();
            return;
        }
        String token = AuthPrefs.token(this);
        String bearer = (token == null || token.isEmpty()) ? "" : "Bearer " + token;

        ApiClient.get().create(ApiService.class)
                .assignDriver(bearer, d.id, driverId)
                .enqueue(new Callback<GenericResponse>() {
                    @Override public void onResponse(Call<GenericResponse> call, Response<GenericResponse> res) {
                        if (!res.isSuccessful() || res.body() == null || !res.body().success) {
                            Toast.makeText(TaskDialogActivity.this, "Assign failed", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        ArrayList<Long> ids = new ArrayList<>();
                        if (d.pickup_task != null) ids.add(d.pickup_task.id);
                        if (d.delivery_task != null) ids.add(d.delivery_task.id);
                        if (ids.isEmpty()) {
                            Toast.makeText(TaskDialogActivity.this, "No rows", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        final int[] left = { ids.size() };
                        final boolean[] anyOk = { false };
                        for (long rowId : ids) {
                            ApiClient.get().create(ApiService.class)
                                    .updateTaskStatus(bearer, rowId, "accepted")
                                    .enqueue(new Callback<GenericResponse>() {
                                        @Override public void onResponse(Call<GenericResponse> call,
                                                                         Response<GenericResponse> res2) {
                                            if (res2.isSuccessful() && res2.body()!=null && res2.body().success) anyOk[0] = true;
                                            done();
                                        }
                                        @Override public void onFailure(Call<GenericResponse> call, Throwable t) { done(); }
                                        private void done() {
                                            left[0]--;
                                            if (left[0] == 0) {
                                                if (anyOk[0]) {
                                                    FsDriver.addAcceptedTxLocal(TaskDialogActivity.this, d.id);
                                                    FsDriver.mirrorHavingTask(TaskDialogActivity.this, true);
                                                    FsDriver.mirrorLastTransactionId(TaskDialogActivity.this, String.valueOf(d.id));
                                                    FsDriver.addActiveTxFirestore(TaskDialogActivity.this, d.id);
                                                    Toast.makeText(TaskDialogActivity.this, "Task accepted and queued", Toast.LENGTH_SHORT).show();
                                                } else {
                                                    Toast.makeText(TaskDialogActivity.this, "Accept failed", Toast.LENGTH_SHORT).show();
                                                }
                                                if (dialog != null) dialog.dismiss();
                                                finish();
                                            }
                                        }
                                    });
                        }
                    }
                    @Override public void onFailure(Call<GenericResponse> call, Throwable t) {
                        Toast.makeText(TaskDialogActivity.this, "Network error", Toast.LENGTH_SHORT).show();
                    }
                });
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

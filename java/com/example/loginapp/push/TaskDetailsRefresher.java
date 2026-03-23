package com.example.loginapp.push;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.loginapp.Actions;
import com.example.loginapp.AuthPrefs;
import com.example.loginapp.net.ApiClient;
import com.example.loginapp.net.ApiService;
import com.example.loginapp.net.model.TaskDetailsResponse;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public final class TaskDetailsRefresher {

    private static final String TAG = "TaskDetailsRefresher";

    private TaskDetailsRefresher() {}

    public static void refreshAndBroadcast(@NonNull Context ctx, long transactionId) {
        if (transactionId <= 0) return;

        String bearer = AuthPrefs.bearer(ctx);
        if (bearer == null || bearer.trim().isEmpty()) {
            Log.w(TAG, "No bearer token; cannot call task_details");
            return;
        }

        ApiService api = ApiClient.get().create(ApiService.class);

        api.getTaskDetails(bearer, transactionId).enqueue(new Callback<TaskDetailsResponse>() {
            @Override
            public void onResponse(Call<TaskDetailsResponse> call, Response<TaskDetailsResponse> res) {
                if (!res.isSuccessful() || res.body() == null || !res.body().success || res.body().data == null) {
                    Log.w(TAG, "task_details failed http=" + res.code());
                    return;
                }

                TaskDetailsResponse.Data d = res.body().data;

                Intent i = new Intent(Actions.TASK_DETAILS_UPDATED);

                // transaction id + transaction status
                i.putExtra(Actions.EXTRA_TX_ID, d.id);
                i.putExtra(Actions.EXTRA_STATUS_TEXT, safeStr(d.status));

                // vendor/order info
                i.putExtra(Actions.EXTRA_PAYMENT_TYPE, safeStr(d.vendor_payment_type));
                i.putExtra(Actions.EXTRA_ORDER_AMOUNT, safeStr(d.order_amount));
                i.putExtra(Actions.EXTRA_ORDER_ID, safeStr(d.order_id));

                // pickup task
                TaskDetailsResponse.Task p = d.pickup_task;
                if (p != null) {
                    i.putExtra(Actions.EXTRA_PICKUP_ID, p.id);
                    i.putExtra(Actions.EXTRA_PICKUP_ADDRESS, safeStr(p.address));
                    i.putExtra(Actions.EXTRA_PICKUP_PHONE, safeStr(p.phone));
                    i.putExtra(Actions.EXTRA_PICKUP_LAT, parseDoubleSafe(p.lat));
                    i.putExtra(Actions.EXTRA_PICKUP_LNG, parseDoubleSafe(p.lng));
                    i.putExtra(Actions.EXTRA_PICKUP_TASK_STATUS, safeStr(p.task_status));
                }

                // delivery task
                TaskDetailsResponse.Task del = d.delivery_task;
                if (del != null) {
                    i.putExtra(Actions.EXTRA_DELIVERY_ID, del.id);
                    i.putExtra(Actions.EXTRA_DELIVERY_ADDRESS, safeStr(del.address));
                    i.putExtra(Actions.EXTRA_DELIVERY_PHONE, safeStr(del.phone));
                    i.putExtra(Actions.EXTRA_DELIVERY_LAT, parseDoubleSafe(del.lat));
                    i.putExtra(Actions.EXTRA_DELIVERY_LNG, parseDoubleSafe(del.lng));
                    i.putExtra(Actions.EXTRA_DELIVERY_TASK_STATUS, safeStr(del.task_status));
                }

                LocalBroadcastManager.getInstance(ctx).sendBroadcast(i);
                Log.d(TAG, "Broadcasted TASK_DETAILS_UPDATED tx=" + d.id + " status=" + d.status);
            }

            @Override
            public void onFailure(Call<TaskDetailsResponse> call, Throwable t) {
                Log.e(TAG, "task_details error tx=" + transactionId, t);
            }
        });
    }

    private static String safeStr(String s) {
        return (s == null) ? "" : s;
    }

    private static double parseDoubleSafe(String s) {
        try {
            if (s == null) return Double.NaN;
            String t = s.trim();
            if (t.isEmpty()) return Double.NaN;
            return Double.parseDouble(t);
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}

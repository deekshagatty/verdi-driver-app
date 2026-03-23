// app/src/main/java/com/example/loginapp/push/TaskStatusPushHandler.java
package com.example.loginapp.push;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.loginapp.Actions;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TaskStatusPushHandler {

    private static final String TAG = "TaskStatusHandler";

    private static final String PREFS = "verdi_prefs";
    private static final String KEY_ACTIVE_TX = "active_txid";

    // old: "295,success" OR TTL: "295,10"
    private static final Pattern BODY_2 =
            Pattern.compile("^\\s*(\\d+)\\s*,\\s*(.+?)\\s*$");

    // new: "310,348,success"
    private static final Pattern BODY_3 =
            Pattern.compile("^\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(.+?)\\s*$");

    private static final Pattern ONLY_NUMBER =
            Pattern.compile("^\\d+$");

    private TaskStatusPushHandler() {}

    public static boolean handleIfStatusUpdate(@NonNull Context ctx,
                                               @NonNull Map<String, String> data,
                                               @Nullable String notificationBody) {

        Log.d(TAG, "handleIfStatusUpdate called. body=" + notificationBody + " data=" + data);

        // ✅ main transaction id for /task_details?transaction_id=304
        String mainTxStr = firstNonEmpty(data,
                "transaction_id", "transactionId", "tx_id", "txId");

        // ✅ task row id: 347/348 (pickup_task.id / delivery_task.id)
        String taskTxStr = firstNonEmpty(data,
                "task_transaction_id", "taskTransactionId", "task_tx_id", "taskTxId");

        String stStr = firstNonEmpty(data,
                "status", "task_status", "status_code", "statusCode", "taskStatus");

        // Body parse fallback
        if ((taskTxStr == null || stStr == null || mainTxStr == null) && notificationBody != null) {

            // 1) Try new format: "310,348,success"
            Matcher m3 = BODY_3.matcher(notificationBody);
            if (m3.find()) {
                // ✅ IMPORTANT: DO NOT ignore main tx id
                mainTxStr = m3.group(1); // ✅ 310 (MAIN transaction)
                taskTxStr = m3.group(2); // ✅ 348 (task row)
                stStr     = m3.group(3); // ✅ success
            } else {
                // 2) Old format: "295,success" OR "295,10"
                Matcher m2 = BODY_2.matcher(notificationBody);
                if (m2.find()) {
                    taskTxStr = m2.group(1); // treat as taskTx in old format
                    stStr     = m2.group(2);
                }
            }
        }

        // TTL guard: "305,10" => ignore
        if (stStr != null && ONLY_NUMBER.matcher(stStr.trim()).matches()) {
            Log.d(TAG, "Ignoring numeric status payload (looks like TTL): " + taskTxStr + "," + stStr);
            return false;
        }

        if (taskTxStr == null || stStr == null) return false;

        long taskTxId = 0L;
        try { taskTxId = Long.parseLong(taskTxStr.trim()); } catch (Exception ignored) {}
        if (taskTxId <= 0) return false;

        // ✅ main tx id
        long mainTxId = 0L;
        try { if (mainTxStr != null) mainTxId = Long.parseLong(mainTxStr.trim()); } catch (Exception ignored) {}

        // ✅ fallback: use active tx from prefs if backend didn’t send transaction_id
        if (mainTxId <= 0) {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
            mainTxId = sp.getLong(KEY_ACTIVE_TX, 0L);
        }

        if (mainTxId <= 0) {
            Log.w(TAG, "No main transaction_id available. Need it for task_details. taskTx=" + taskTxId);
            return false;
        }

        String msg = firstNonEmpty(data, "message", "msg");
        if (msg == null || msg.trim().isEmpty()) msg = "Status changed by the admin";

        Intent i = new Intent(Actions.TASK_STATUS_CHANGED);
        i.putExtra(Actions.EXTRA_TX_ID, mainTxId);          // ✅ MAIN tx id
        i.putExtra(Actions.EXTRA_TASK_TX_ID, taskTxId);     // ✅ task row id
        i.putExtra(Actions.EXTRA_TASK_STATUS, stStr);
        i.putExtra(Actions.EXTRA_STATUS_MESSAGE, msg);
        i.putExtra(Actions.EXTRA_STATUS_RAW, notificationBody);

        // ✅ IMPORTANT: keep it inside app only (safer)
        i.setPackage(ctx.getPackageName());

        Log.d(TAG, "SENDING broadcast TASK_STATUS_CHANGED mainTx=" + mainTxId
                + " taskTx=" + taskTxId + " status=" + stStr + " msg=" + msg);

        // 1) LocalBroadcast (TaskDetailActivity listens to this)
        try {
            boolean ok = LocalBroadcastManager.getInstance(ctx).sendBroadcast(i);
            Log.d(TAG, "LocalBroadcastManager.sendBroadcast ok=" + ok);
        } catch (Exception e) {
            Log.e(TAG, "LocalBroadcastManager send failed", e);
        }

        // 2) Normal broadcast fallback (only useful if someone registered with registerReceiver())
        try {
            ctx.sendBroadcast(i);
            Log.d(TAG, "ctx.sendBroadcast sent (package-scoped)");
        } catch (Exception e) {
            Log.e(TAG, "ctx.sendBroadcast failed", e);
        }

        // ✅ always refresh truth using MAIN transaction id
        TaskDetailsRefresher.refreshAndBroadcast(ctx, mainTxId);

        return true;
    }

    @Nullable
    private static String firstNonEmpty(Map<String, String> data, String... keys) {
        for (String k : keys) {
            String v = data.get(k);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }
}

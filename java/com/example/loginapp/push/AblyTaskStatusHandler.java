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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AblyTaskStatusHandler {

    private static final String TAG = "AblyStatusHandler";

    private static final String PREFS = "verdi_prefs";
    private static final String KEY_ACTIVE_TX = "active_txid";

    // "24,38,success"
    private static final Pattern BODY_3 =
            Pattern.compile("^\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(.+?)\\s*$");

    // "37,success" or "37,10"
    private static final Pattern BODY_2 =
            Pattern.compile("^\\s*(\\d+)\\s*,\\s*(.+?)\\s*$");

    private static final Pattern ONLY_NUMBER =
            Pattern.compile("^\\d+$");

    private AblyTaskStatusHandler() {}

    public static boolean handle(@NonNull Context ctx,
                                 @Nullable Object payloadObj,
                                 @Nullable String rawBody) {

        String body = (rawBody != null) ? rawBody.trim() : "";
        if (body.isEmpty() && payloadObj != null) body = String.valueOf(payloadObj).trim();

        Log.e(TAG, "handle() raw=" + body);

        String mainTxStr = null;
        String taskTxStr = null;
        String stStr     = null;

        Matcher m3 = BODY_3.matcher(body);
        if (m3.find()) {
            mainTxStr = m3.group(1); // main transaction_id
            taskTxStr = m3.group(2); // task_transaction_id (row id)
            stStr     = m3.group(3); // status
        } else {
            Matcher m2 = BODY_2.matcher(body);
            if (m2.find()) {
                taskTxStr = m2.group(1);
                stStr     = m2.group(2);
            }
        }

        // TTL guard: "305,10"
        if (stStr != null && ONLY_NUMBER.matcher(stStr.trim()).matches()) {
            Log.d(TAG, "Ignoring TTL-like payload: " + body);
            return false;
        }

        long taskTxId = 0L;
        try { if (taskTxStr != null) taskTxId = Long.parseLong(taskTxStr.trim()); } catch (Exception ignored) {}
        if (taskTxId <= 0) return false;

        long mainTxId = 0L;
        try { if (mainTxStr != null) mainTxId = Long.parseLong(mainTxStr.trim()); } catch (Exception ignored) {}

        // fallback: use active tx
        if (mainTxId <= 0) {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
            mainTxId = sp.getLong(KEY_ACTIVE_TX, 0L);
        }

        if (mainTxId <= 0) {
            Log.w(TAG, "No mainTxId available. payload=" + body);
            return false;
        }

        Intent i = new Intent(Actions.TASK_STATUS_CHANGED);
        i.putExtra(Actions.EXTRA_TX_ID, mainTxId);
        i.putExtra(Actions.EXTRA_TASK_TX_ID, taskTxId);
        i.putExtra(Actions.EXTRA_TASK_STATUS, stStr);
        i.putExtra(Actions.EXTRA_STATUS_MESSAGE, "Status changed by the admin");
        i.putExtra(Actions.EXTRA_STATUS_RAW, body);
        i.setPackage(ctx.getPackageName());

        Log.e(TAG, "ABLY STATUS -> broadcast mainTx=" + mainTxId
                + " taskTx=" + taskTxId + " status=" + stStr);

        try {
            boolean ok = LocalBroadcastManager.getInstance(ctx).sendBroadcast(i);
            Log.e(TAG, "LBM.sendBroadcast ok=" + ok);
        } catch (Exception e) {
            Log.e(TAG, "LBM send failed", e);
        }

        try {
            ctx.sendBroadcast(i);
        } catch (Exception ignored) {}

        TaskDetailsRefresher.refreshAndBroadcast(ctx, mainTxId);
        return true;
    }
}

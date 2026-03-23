package com.example.loginapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public abstract class BaseActivity extends AppCompatActivity {

    private final BroadcastReceiver assignmentReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Actions.NEW_ASSIGNMENT.equals(intent.getAction())) {
                long txId = intent.getLongExtra(Actions.EXTRA_TX_ID, 0L);
                if (txId > 0) TaskPopup.show(BaseActivity.this, txId);
            }
        }
    };

    // ✅ NEW: status-update alert receiver (separate)
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!Actions.TASK_STATUS_CHANGED.equals(intent.getAction())) return;

            long txId = intent.getLongExtra(Actions.EXTRA_TX_ID, 0L);
            String status = intent.getStringExtra(Actions.EXTRA_TASK_STATUS);
            String msg = intent.getStringExtra(Actions.EXTRA_STATUS_MESSAGE);

            Log.d("BaseActivity", "StatusReceiver tx=" + txId + " status=" + status + " msg=" + msg);

            String title = "Status Updated";
            String body = "Transaction ID: " + txId + "\nStatus: " + status +
                    (msg != null && !msg.trim().isEmpty() ? "\n\n" + msg : "");

            runOnUiThread(() -> {
                if (isFinishing()) return;
                new AlertDialog.Builder(BaseActivity.this)
                        .setTitle(title)
                        .setMessage(body)
                        .setPositiveButton("OK", (d, w) -> d.dismiss())
                        .show();
            });
        }
    };

    @Override protected void onResume() {
        super.onResume();
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(assignmentReceiver, new IntentFilter(Actions.NEW_ASSIGNMENT));
        lbm.registerReceiver(statusReceiver, new IntentFilter(Actions.TASK_STATUS_CHANGED));
    }

    @Override protected void onPause() {
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        try { lbm.unregisterReceiver(assignmentReceiver); } catch (Exception ignored) {}
        try { lbm.unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
        super.onPause();
    }
}

// BaseActivity.java
package com.example.loginapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

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

    @Override protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(assignmentReceiver, new IntentFilter(Actions.NEW_ASSIGNMENT));
    }

    @Override protected void onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(assignmentReceiver);
        super.onPause();
    }
}

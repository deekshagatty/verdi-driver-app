package com.example.loginapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AssignmentDismissReceiver extends BroadcastReceiver {
    private static final String PREFS = "verdi_prefs";
    private static final String KEY_LAST_TXID = "last_seen_txid";

    @Override public void onReceive(Context ctx, Intent i) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_LAST_TXID).apply();
    }
}

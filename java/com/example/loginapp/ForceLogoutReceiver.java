package com.example.loginapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class ForceLogoutReceiver extends BroadcastReceiver {

    private static final String TAG = "ForceLogoutRcvr";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (!LocationPingService.ACTION_FORCE_LOGOUT.equals(action)) return;

        Log.e(TAG, "ACTION_FORCE_LOGOUT received → opening LoginActivity");

        Intent i = new Intent(context, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(i);
    }
}
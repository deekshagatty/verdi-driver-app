package com.example.loginapp;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends android.content.BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        SharedPreferences sp = ctx.getSharedPreferences("verdi_prefs", Context.MODE_PRIVATE);
        boolean duty = sp.getBoolean("on_duty_toggle_state", false);
        if (!duty) return;

        Intent svc = new Intent(ctx, LocationPingService.class);
        svc.setAction(LocationPingService.ACTION_START_ON_DUTY);
        svc.putExtra("immediate", true);
        androidx.core.content.ContextCompat.startForegroundService(ctx, svc);
    }
}


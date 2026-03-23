package com.example.loginapp;

import android.content.Context;
import android.content.Intent;

public class SessionKiller {

    public static void forceLogout(Context ctx) {
        Context app = ctx.getApplicationContext();

        // stop service
        try {
            Intent stop = new Intent(app, LocationPingService.class);
            stop.setAction(LocationPingService.ACTION_STOP);
            app.startService(stop);
        } catch (Exception ignored) {}

        // local off duty
        app.getSharedPreferences("verdi_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("on_duty_toggle_state", false)
                .apply();

        // clear login/session
        AuthPrefs.clearLogin(app);

        // open login
        Intent i = new Intent(app, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        app.startActivity(i);
    }
}

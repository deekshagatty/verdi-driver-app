// com/example/loginapp/update/UpgradeReceiver.java
package com.example.loginapp.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.loginapp.HomeActivity;

// AndroidManifest registered above
// com/example/loginapp/update/UpgradeReceiver.java
public class UpgradeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        // Only react if it's our own package
        String pkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null;
        if (!ctx.getPackageName().equals(pkg)) return;

        Intent launch = new Intent(ctx, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ctx.startActivity(launch);
}
}
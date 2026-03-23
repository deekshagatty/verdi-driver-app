package com.example.loginapp;

import android.app.Application;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.FirebaseDatabase;

public class MyApp extends Application {
    @Override public void onCreate() {
        super.onCreate();

        FirebaseApp app = FirebaseApp.initializeApp(this);
        if (app == null) {
            Log.w("MyApp", "Firebase not initialized (missing google-services.json?)");
            return;
        }
        try {
        } catch (IllegalStateException ignore) {
        }
    }
}

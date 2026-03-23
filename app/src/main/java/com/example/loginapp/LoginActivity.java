package com.example.loginapp;

import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.loginapp.net.ApiClient;
import com.example.loginapp.net.ApiService;
import com.example.loginapp.net.model.GenericResponse;
import com.example.loginapp.net.model.LoginResponse;
import com.example.loginapp.push.MyFcmService;
import com.google.firebase.messaging.FirebaseMessaging;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class LoginActivity extends AppCompatActivity {
    private EditText etUsername, etPassword;
    private Button btnSignIn;
    private TextView tvForgot;
    private ApiService api;
    private ProgressDialog pd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (AuthPrefs.isSessionValid(this)) {
            AuthPrefs.touchSession(this);
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }

        setContentView(R.layout.activity_login);

        requestPostNotificationsIfNeeded();
        ensureNotificationChannel();

        api = ApiClient.get().create(ApiService.class);

        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        btnSignIn  = findViewById(R.id.btnSignIn);
        tvForgot   = findViewById(R.id.tvForgot);

        btnSignIn.setOnClickListener(v -> doLogin());
        tvForgot.setOnClickListener(v ->
                Toast.makeText(this, "Forgot Password Clicked", Toast.LENGTH_SHORT).show());
    }

    private void doLogin() {
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Enter username and password", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSignIn.setEnabled(false);
        pd = ProgressDialog.show(this, null, "Signing in…", true, false);

        api.login(username, password).enqueue(new Callback<LoginResponse>() {
            @Override public void onResponse(Call<LoginResponse> call, Response<LoginResponse> res) {
                btnSignIn.setEnabled(true);
                if (pd != null) pd.dismiss();

                if (!res.isSuccessful() || res.body() == null) {
                    Toast.makeText(LoginActivity.this, "Login failed (" + res.code() + ")", Toast.LENGTH_LONG).show();
                    return;
                }

                LoginResponse body = res.body();
                if (!body.success) {
                    Toast.makeText(LoginActivity.this, body.message != null ? body.message : "Login failed", Toast.LENGTH_LONG).show();
                    return;
                }

                String apiName     = body.driver != null ? body.driver.name     : "";
                String apiUsername = body.driver != null ? body.driver.username : "";
                String apiPhone    = body.driver != null ? body.driver.phone    : "";

                AuthPrefs.saveLogin(
                        LoginActivity.this,
                        body.token,
                        body.driver != null ? body.driver.id : 0,
                        apiName, apiUsername, apiPhone
                );

                final String bearerHeader = resolveBearerHeader();
                final long driverId = AuthPrefs.driverId(LoginActivity.this);

                FirebaseMessaging.getInstance().getToken()
                        .addOnSuccessListener(token -> {
                            getSharedPreferences("verdi_prefs", MODE_PRIVATE)
                                    .edit().putString("fcm_token", token).apply();
                            MyFcmService.pushCachedToken(getApplicationContext()); // fine to keep
                            uploadFcmIfNeeded(bearerHeader, driverId, token);      // now hits /fcm_token
                        })
                        .addOnFailureListener(e -> {
                            String cached = getSharedPreferences("verdi_prefs", MODE_PRIVATE)
                                    .getString("fcm_token", null);
                            if (cached != null && !cached.isEmpty()) {
                                uploadFcmIfNeeded(bearerHeader, driverId, cached);
                            } else {
                                Log.w("Login", "FCM getToken() failed and no cached token: " + e.getMessage());
                            }
                        });

                Intent intent = new Intent(LoginActivity.this, HomeActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                intent.putExtra("driver_name",     apiName.isEmpty() ? username : apiName);
                intent.putExtra("driver_username", apiUsername);
                intent.putExtra("driver_phone",    apiPhone);
                startActivity(intent);
                finish();
            }

            @Override public void onFailure(Call<LoginResponse> call, Throwable t) {
                btnSignIn.setEnabled(true);
                if (pd != null) pd.dismiss();
                Toast.makeText(LoginActivity.this, "Network error: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void uploadFcmIfNeeded(String bearer, long driverId, String token) {
        if (bearer == null || bearer.isEmpty() || driverId <= 0 || token == null || token.isEmpty()) return;

        SharedPreferences sp = getSharedPreferences("verdi_prefs", MODE_PRIVATE);
        String lastToken = sp.getString("fcm_token_uploaded", "");
        long lastDriver  = sp.getLong("fcm_token_uploaded_driver", -1);
        if (token.equals(lastToken) && driverId == lastDriver) {
            return; // already uploaded
        }

        ApiService api = ApiClient.get().create(ApiService.class);

        okhttp3.RequestBody tokenPart =
                okhttp3.RequestBody.create(token, okhttp3.MediaType.parse("text/plain"));

        api.uploadFcmToken(bearer, tokenPart)
                .enqueue(new Callback<GenericResponse>() {
                    @Override public void onResponse(Call<GenericResponse> call, Response<GenericResponse> resp) {
                        if (resp.isSuccessful()) {
                            sp.edit()
                                    .putString("fcm_token_uploaded", token)
                                    .putLong("fcm_token_uploaded_driver", driverId)
                                    .apply();
                        } else {
                            try {
                                String err = resp.errorBody() != null ? resp.errorBody().string() : "";
                                Log.e("Login", "uploadFcmToken HTTP " + resp.code() + " body=" + err);
                            } catch (Exception ignore) {}
                        }
                    }
                    @Override public void onFailure(Call<GenericResponse> call, Throwable t) {
                        Log.w("Login", "uploadFcmToken failed", t);
                    }
                });
    }

    private String resolveBearerHeader() {
        String asIs = AuthPrefs.bearer(this); // already "Bearer <jwt>"?
        if (asIs != null && !asIs.trim().isEmpty()) return asIs;

        String raw = AuthPrefs.token(this);   // raw JWT
        return (raw != null && !raw.trim().isEmpty()) ? ("Bearer " + raw) : "";
    }

    private void requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != getPackageManager().PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1234);
        }
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = "general_notifs";
            String name = "General";
            String desc = "General notifications";
            int importance = android.app.NotificationManager.IMPORTANCE_HIGH;
            android.app.NotificationChannel ch =
                    new android.app.NotificationChannel(channelId, name, importance);
            ch.setDescription(desc);
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }
}

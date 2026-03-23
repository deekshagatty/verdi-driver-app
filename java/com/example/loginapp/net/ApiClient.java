package com.example.loginapp.net;

import com.example.loginapp.ApiConstants;
import com.example.loginapp.App;
import com.example.loginapp.AuthPrefs;
import com.example.loginapp.BuildConfig;
import com.example.loginapp.SessionKiller;
import com.google.gson.GsonBuilder;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;

import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.util.concurrent.TimeUnit;

public final class ApiClient {
    private static volatile Retrofit INSTANCE;

    private ApiClient() {}

    public static Retrofit get() {
        if (INSTANCE == null) {
            synchronized (ApiClient.class) {
                if (INSTANCE == null) {

                    OkHttpClient.Builder ok = new OkHttpClient.Builder()
                            .connectTimeout(30, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS);

                    // ✅ 1) Add Authorization header automatically
                    ok.addInterceptor((Interceptor) chain -> {
                        Request original = chain.request();

                        // If you want to skip auth for some endpoints, you can add header "No-Auth:1"
                        boolean skipAuth = "1".equals(original.header("No-Auth"));

                        if (skipAuth) {
                            Request clean = original.newBuilder().removeHeader("No-Auth").build();
                            return chain.proceed(clean);
                        }

                        String bearer = AuthPrefs.bearer(App.get());
                        if (bearer == null || bearer.trim().isEmpty()) {
                            String raw = AuthPrefs.token(App.get());
                            if (raw != null && !raw.trim().isEmpty()) bearer = "Bearer " + raw;
                        }

                        Request.Builder b = original.newBuilder();
                        if (bearer != null && !bearer.trim().isEmpty()) {
                            b.header("Authorization", bearer);
                        }

                        return chain.proceed(b.build());
                    });

                    // ✅ 2) Catch 401 globally and do OffDuty + Logout
                    ok.addInterceptor((Interceptor) chain -> {
                        Response res = chain.proceed(chain.request());

                        boolean skip401 = "1".equals(chain.request().header("X-SKIP-401"));

                        if (!skip401 && res.code() == 401) {
                            try { res.close(); } catch (Exception ignored) {}
                            SessionKiller.forceLogout(App.get()); // off duty + login
                        }
                        return res;
                    });

                    // ✅ logging (your same)
                    if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor log = new HttpLoggingInterceptor();
                        log.setLevel(HttpLoggingInterceptor.Level.BODY);
                        ok.addInterceptor(log);
                    }

                    INSTANCE = new Retrofit.Builder()
                            .baseUrl(ApiConstants.BASE_URL) // must end with '/'
                            .addConverterFactory(GsonConverterFactory.create(
                                    new GsonBuilder().setLenient().create()))
                            .client(ok.build())
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}

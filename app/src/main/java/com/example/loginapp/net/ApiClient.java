package com.example.loginapp.net;

import com.example.loginapp.ApiConstants;
import com.example.loginapp.BuildConfig;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import java.util.concurrent.TimeUnit;
import com.google.gson.GsonBuilder;



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

                    if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor log = new HttpLoggingInterceptor();
                        log.setLevel(HttpLoggingInterceptor.Level.BODY);
                        ok.addInterceptor(log);
                    }

                    INSTANCE = new Retrofit.Builder()
                            .baseUrl(ApiConstants.BASE_URL)// must end with '/'
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

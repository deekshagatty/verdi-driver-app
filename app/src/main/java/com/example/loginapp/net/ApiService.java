package com.example.loginapp.net;

import com.example.loginapp.net.model.GenericResponse;
import com.example.loginapp.net.model.LoginResponse;
import com.example.loginapp.net.model.TaskDetailsResponse;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Query;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import retrofit2.http.Multipart;
import retrofit2.http.Part;

public interface ApiService {

    @FormUrlEncoded
    @POST("driver_login")
    Call<LoginResponse> login(
            @Field("username") String username,
            @Field("password") String password
    );

    @GET("update_on_duty")
    Call<GenericResponse> updateOnDuty(
            @Header("Authorization") String bearer,   // pass "Bearer <jwt>"
            @Query("driver_id") long driverId,
            @Query("isOnline") int isOnline           // 1 or 0
    );

    @GET("task_details")
    Call<TaskDetailsResponse> getTaskDetails(
            @Header("Authorization") String bearer,   // "Bearer <jwt>"
            @Query("transaction_id") long transactionId
    );

    @FormUrlEncoded
    @POST("update_task_status")
    Call<GenericResponse> updateTaskStatus(
            @Header("Authorization") String bearer,   // "Bearer <jwt>"
            @Field("task_transaction_id") long taskRowId,
            @Field("status") String status            // "accepted", "arrived", "success", ...
    );

    @FormUrlEncoded
    @POST("assign_driver")
    Call<GenericResponse> assignDriver(
            @Header("Authorization") String bearer,   // "Bearer <jwt>"
            @Field("transaction_id") long transactionId,
            @Field("driver_id") long driverId
    );

    @FormUrlEncoded
    @POST("driver_attach_firebase")
    Call<GenericResponse> attachFirebase(
            @Header("Authorization") String bearer,
            @Field("driver_id") long driverId,
            @Field("firebase_uid") String firebaseUid,
            @Field("fcm_token") String fcmToken
    );

    @Multipart
    @POST("fcm_token")
    Call<GenericResponse> uploadFcmToken(
            @Header("Authorization") String bearer,          // "Bearer <jwt>"
            @Part("fcm_token") RequestBody fcmTokenPart      // form-data: fcm_token=...
    );

    @FormUrlEncoded
    @POST("fcm_token")
    Call<GenericResponse> uploadFcmToken(
            @Header("Authorization") String bearer,
            @Field("fcm_token") String token
    );
}

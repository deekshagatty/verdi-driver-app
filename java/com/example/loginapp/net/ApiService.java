package com.example.loginapp.net;

import com.example.loginapp.net.model.DriverTaskRow;
import com.example.loginapp.net.model.GenericResponse;
import com.example.loginapp.net.model.LoginResponse;
import com.example.loginapp.net.model.TaskDetailsResponse;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Query;

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

//    @GET("update_on_duty")
//    Call<GenericResponse> updateOnDuty(
//            @Header("Authorization") String bearer,   // pass "Bearer <jwt>"
//            @Query("driver_id") long driverId,
//            @Query("isOnline") int isOnline           // 1 or 0
//    );

    @FormUrlEncoded
    @POST("update_on_duty")
    Call<GenericResponse> updateOnDuty(
            @Header("Authorization") String bearer,   // "Bearer <jwt>"
            @Field("driver_id") long driverId,
            @Field("isOnline") int isOnline           // 1 or 0
    );

    // ✅ Update Logged In (login=1, logout=0)
    @FormUrlEncoded
    @POST("update_logged_in")
    Call<GenericResponse> updateLoggedIn(
            @Header("Authorization") String bearer,
            @Field("driver_id") long driverId,
            @Field("isLoggedIn") int isLoggedIn
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
            @Field("driver_id") long driverId,
            @Field("type") String type
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

    @FormUrlEncoded
    @POST("accept_task")
    Call<GenericResponse> acceptTask(
            @Header("Authorization") String bearer,
            @Field("task_transaction_id") long taskTransactionId
    );

    @Multipart
    @POST("save_driver_location")
    Call<GenericResponse> saveDriverLocation(
            @Header("Authorization") String bearer,
            @Part("driver_id") RequestBody driverId,
            @Part("lat") RequestBody lat,
            @Part("lng") RequestBody lng
    );

    @Multipart
    @POST("save_driver_trip_location")
    Call<GenericResponse> saveDriverTripLocation(
            @Header("Authorization") String bearer,
            @Part("task_transaction_id") RequestBody taskTransactionId,
            @Part("driver_id") RequestBody driverId,
            @Part("lat") RequestBody lat,
            @Part("lng") RequestBody lng
    );

    @FormUrlEncoded
    @POST("update_driver_busy")
    Call<GenericResponse> updateDriverBusy(
            @Header("Authorization") String bearer,
            @Field("driver_id") long driverId,
            @Field("isBusy") int isBusy
    );

    @GET("driver_task")
    Call<com.example.loginapp.net.model.DriverTaskResponse> getDriverTasks(
            @Header("Authorization") String bearer,
            @Query("driver_id") long driverId,
            @Query("date") String date
    );


}

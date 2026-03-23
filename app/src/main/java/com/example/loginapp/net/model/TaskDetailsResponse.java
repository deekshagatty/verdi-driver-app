package com.example.loginapp.net.model;

import com.google.gson.annotations.SerializedName;

public class TaskDetailsResponse {
    public boolean success;
    public Data data;

    public static class Data {
        public long id;                    // transaction id
        public String status;
        public String type;
        @SerializedName("created_at") public String created_at;

        @SerializedName("pickup_task")   public Task pickup_task;
        @SerializedName("delivery_task") public Task delivery_task;

        // extra vendor/order info used for Home cards
        @SerializedName("vendor_payment_type") public String vendor_payment_type;
        @SerializedName("order_amount")        public String order_amount;
        @SerializedName("order_id")            public String order_id;
    }

    public static class Task {
        public long id; // row id
        @SerializedName("transaction_id") public long transaction_id;
        public String name;
        public String phone;
        public String address;

        // keep as Strings; we parse safely
        @SerializedName("lat") public String lat;
        @SerializedName("lng") public String lng;

        @SerializedName("task_type")   public String task_type;   // pickup|delivery
        @SerializedName("task_status") public String task_status; // pending|...

        @SerializedName("created_at")  public String created_at;
    }
}

package com.example.loginapp.net.model;

import com.google.gson.annotations.SerializedName;

public class DriverTaskRow {

    @SerializedName("id")
    public long id;

    @SerializedName("amount")
    public String amount;

    @SerializedName("order_id")
    public String order_id;

    @SerializedName("created_at")
    public String created_at;

    // optional (backend may send sometimes)
    @SerializedName("type")
    public String type;
}

package com.example.loginapp.net.model;

import com.google.gson.annotations.SerializedName;

public class GenericResponse {
    @SerializedName("success") public boolean success;
    @SerializedName("message") public String message;


}

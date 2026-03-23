package com.example.loginapp.net.model;

public class LoginResponse {
    public boolean success;
    public String message;
    public Driver driver;
    public String token;

    public static class Driver {
        public int id;
        public String name;
        public String username;
        public String phone;

    }
}

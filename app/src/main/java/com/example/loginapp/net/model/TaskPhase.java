package com.example.loginapp.net.model;

public enum TaskPhase {
    ACCEPTED,
    PICKUP_STARTED,
    PICKUP_ARRIVED,
    DELIVERY_ARRIVED,
    DELIVERY_COMPLETED;

    /** Map to the exact backend strings. */
    public static String toApi(TaskPhase p) {
        switch (p) {
            case ACCEPTED:           return "accepted";
            case PICKUP_STARTED:     return "started";   // first try
            case PICKUP_ARRIVED:     return "arrived";
            case DELIVERY_ARRIVED:   return "arrived";
            case DELIVERY_COMPLETED: return "success";
        }
        return "accepted";
    }
}

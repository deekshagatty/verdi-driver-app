// Actions.java
package com.example.loginapp;
public final class Actions {
    private Actions(){}
    public static final String NEW_ASSIGNMENT = "com.example.loginapp.NEW_ASSIGNMENT";
//    public static final String EXTRA_TX_ID    = "tx_id";
    public static final String TASK_STATUS_CHANGED = "com.example.loginapp.TASK_STATUS_CHANGED";
    public static final String EXTRA_TASK_STATUS = "extra_task_status";
    public static final String EXTRA_STATUS_MESSAGE = "extra_status_message";
    public static final String EXTRA_TX_ID = "extra_tx_id";

    // ✅ NEW: keep raw status text also
    public static final String EXTRA_STATUS_RAW = "extra_status_raw";


    // ✅ NEW: full task_details refresh broadcast
    public static final String TASK_DETAILS_UPDATED = "com.example.loginapp.TASK_DETAILS_UPDATED";

    // ✅ fields for TaskDetailActivity update
    public static final String EXTRA_STATUS_TEXT      = "extra_status_text"; // data.status (transaction status)

    public static final String EXTRA_PICKUP_ID        = "extra_pickup_id";
    public static final String EXTRA_PICKUP_ADDRESS   = "extra_pickup_address";
    public static final String EXTRA_PICKUP_PHONE     = "extra_pickup_phone";
    public static final String EXTRA_PICKUP_LAT       = "extra_pickup_lat";
    public static final String EXTRA_PICKUP_LNG       = "extra_pickup_lng";
    public static final String EXTRA_PICKUP_TASK_STATUS = "extra_pickup_task_status"; // pickup_task.task_status

    public static final String EXTRA_DELIVERY_ID        = "extra_delivery_id";
    public static final String EXTRA_DELIVERY_ADDRESS   = "extra_delivery_address";
    public static final String EXTRA_DELIVERY_PHONE     = "extra_delivery_phone";
    public static final String EXTRA_DELIVERY_LAT       = "extra_delivery_lat";
    public static final String EXTRA_DELIVERY_LNG       = "extra_delivery_lng";
    public static final String EXTRA_DELIVERY_TASK_STATUS = "extra_delivery_task_status"; // delivery_task.task_status

    public static final String EXTRA_PAYMENT_TYPE     = "extra_payment_type"; // vendor_payment_type
    public static final String EXTRA_ORDER_AMOUNT     = "extra_order_amount"; // order_amount
    public static final String EXTRA_ORDER_ID         = "extra_order_id";     // order_id

    public static final String EXTRA_TASK_TX_ID = "extra_task_tx_id";

    // ✅ ADD THIS:
    public static final String TASK_REVOKED = "com.example.loginapp.TASK_REVOKED";


    // ✅ ADD THESE (optional but useful):
    public static final String EXTRA_REASON = "reason"; // "unassigned" / "cancelled"

}

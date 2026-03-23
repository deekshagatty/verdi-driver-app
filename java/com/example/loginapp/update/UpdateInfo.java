package com.example.loginapp.update;

public final class UpdateInfo {
    public final int latest;
    public final int minSupported;
    public final String apkUrl;
    public final String notes;

    public UpdateInfo(int latest, int minSupported, String apkUrl, String notes) {
        this.latest = latest;
        this.minSupported = minSupported;
        this.apkUrl = apkUrl;
        this.notes = notes;
}
}
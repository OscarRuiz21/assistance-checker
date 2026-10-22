package com.assistance.attendance.api;

public enum CaptureMethod {
    NFC("nfc"),
    PIN("pin"),
    MANUAL("manual");

    private final String dbValue;

    CaptureMethod(String dbValue) { this.dbValue = dbValue; }

    public String dbValue() { return dbValue; }

    public static CaptureMethod fromDb(String s) {
        for (CaptureMethod m : values()) if (m.dbValue.equals(s)) return m;
        throw new IllegalArgumentException("Unknown capture method: " + s);
    }
}

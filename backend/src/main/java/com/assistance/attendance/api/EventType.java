package com.assistance.attendance.api;

public enum EventType {
    CHECK_IN("check_in"),
    CHECK_OUT("check_out"),
    LUNCH_OUT("lunch_out"),
    LUNCH_IN("lunch_in");

    private final String dbValue;

    EventType(String dbValue) { this.dbValue = dbValue; }

    public String dbValue() { return dbValue; }

    public static EventType fromDb(String s) {
        for (EventType t : values()) if (t.dbValue.equals(s)) return t;
        throw new IllegalArgumentException("Unknown event type: " + s);
    }
}

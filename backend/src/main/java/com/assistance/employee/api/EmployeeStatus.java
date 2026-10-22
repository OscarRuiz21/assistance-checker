package com.assistance.employee.api;

public enum EmployeeStatus {
    ACTIVE,
    ON_LEAVE,
    TERMINATED;

    public static EmployeeStatus fromDb(String s) {
        return switch (s) {
            case "active" -> ACTIVE;
            case "on_leave" -> ON_LEAVE;
            case "terminated" -> TERMINATED;
            default -> throw new IllegalArgumentException("Unknown employee status: " + s);
        };
    }
}

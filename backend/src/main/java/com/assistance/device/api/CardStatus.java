package com.assistance.device.api;

public enum CardStatus {
    ACTIVE,
    BLOCKED,
    LOST;

    public static CardStatus fromDb(String s) {
        return switch (s) {
            case "active" -> ACTIVE;
            case "blocked" -> BLOCKED;
            case "lost" -> LOST;
            default -> throw new IllegalArgumentException("Unknown card status: " + s);
        };
    }
}

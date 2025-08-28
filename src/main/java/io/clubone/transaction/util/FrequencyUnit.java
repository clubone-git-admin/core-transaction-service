package io.clubone.transaction.util;

public enum FrequencyUnit {
    DAY, WEEK, MONTH, YEAR;

    public static FrequencyUnit fromDb(String frequencyName) {
        if (frequencyName == null) return MONTH;
        return switch (frequencyName.trim().toUpperCase()) {
            case "DAY", "DAILY" -> DAY;
            case "WEEK", "WEEKLY" -> WEEK;
            case "MONTH", "MONTHLY" -> MONTH;
            case "YEAR", "YEARLY", "ANNUAL" -> YEAR;
            default -> MONTH;
        };
    }
}

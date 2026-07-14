package io.clubone.transaction.inventory;

public record ClientInventoryApiResponse<T>(
        String status,
        String message,
        T data
) {
    public boolean success() {
        return "SUCCESS".equalsIgnoreCase(status);
    }
}

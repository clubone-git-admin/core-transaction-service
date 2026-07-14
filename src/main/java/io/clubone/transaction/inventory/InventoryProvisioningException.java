package io.clubone.transaction.inventory;

public class InventoryProvisioningException
        extends RuntimeException {

    public InventoryProvisioningException(String message) {
        super(message);
    }

    public InventoryProvisioningException(
            String message,
            Throwable cause) {
        super(message, cause);
    }
}

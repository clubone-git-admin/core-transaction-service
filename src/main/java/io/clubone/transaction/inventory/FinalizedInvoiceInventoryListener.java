package io.clubone.transaction.inventory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class FinalizedInvoiceInventoryListener {

    private final FinalizeInventoryProvisioningHelper inventoryProvisioningHelper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void provisionInventory(FinalizedInvoiceInventoryEvent event) {
        log.info(
                "[inventory-provisioning] step=listener_received "
                        + "invoiceId={}, clientPaymentTransactionId={}, "
                        + "actorId={}, locationId={}, applicationId={}, correlationId={}",
                event.invoiceId(),
                event.clientPaymentTransactionId(),
                event.actorId(),
                event.locationId(),
                event.applicationId(),
                event.correlationId()
        );

        try {
            InventoryProvisioningResult result =
                    inventoryProvisioningHelper.provisionForFinalizedInvoice(
                            event.invoiceId(),
                            event.clientPaymentTransactionId(),
                            event.actorId(),
                            event.locationId(),
                            event.applicationId(),
                            event.correlationId()
                    );

            log.info(
                    "Inventory provisioning completed "
                            + "invoiceId={}, clientRoleId={}, "
                            + "invoiceEntityCount={}, entitlementCount={}, "
                            + "createdCount={}, skippedCount={}",
                    result.invoiceId(), result.clientRoleId(),
                    result.invoiceEntityCount(), result.entitlementCount(),
                    result.createdCount(), result.skippedCount()
            );
        } catch (Exception exception) {
            log.error(
                    "Inventory provisioning failed after finalize "
                            + "invoiceId={}, clientPaymentTransactionId={}, "
                            + "correlationId={}, message={}",
                    event.invoiceId(), event.clientPaymentTransactionId(),
                    event.correlationId(), exception.getMessage(), exception
            );
        }
    }
}
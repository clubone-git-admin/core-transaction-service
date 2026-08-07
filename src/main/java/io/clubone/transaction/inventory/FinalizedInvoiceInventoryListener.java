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

    private final FinalizeInventoryProvisioningHelper
            inventoryProvisioningHelper;

    /**
     * fallbackExecution is required for agreement purchases because their
     * inventory event is published after asynchronous billing-quote
     * persistence has committed and therefore has no surrounding transaction.
     */
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void provisionInventory(
            FinalizedInvoiceInventoryEvent event) {

        log.info(
                "[inventory-provisioning] step=listener_received "
                        + "invoiceId={}, clientPaymentTransactionId={}, "
                        + "actorId={}, locationId={}, applicationId={}, "
                        + "correlationId={}",
                event.invoiceId(),
                event.clientPaymentTransactionId(),
                event.actorId(),
                event.locationId(),
                event.applicationId(),
                event.correlationId()
        );

        try {
            InventoryProvisioningResult result =
                    inventoryProvisioningHelper
                            .provisionForFinalizedInvoice(
                                    event.invoiceId(),
                                    event.clientPaymentTransactionId(),
                                    event.actorId(),
                                    event.locationId(),
                                    event.applicationId(),
                                    event.correlationId()
                            );

            log.info(
                    "[inventory-provisioning] step=complete outcome=ok "
                            + "invoiceId={} clientRoleId={} "
                            + "invoiceEntityCount={} entitlementCount={} "
                            + "createdCount={} skippedCount={}",
                    result.invoiceId(),
                    result.clientRoleId(),
                    result.invoiceEntityCount(),
                    result.entitlementCount(),
                    result.createdCount(),
                    result.skippedCount()
            );
        } catch (Exception exception) {
            log.error(
                    "[inventory-provisioning] step=complete outcome=failed "
                            + "invoiceId={} clientPaymentTransactionId={} "
                            + "correlationId={} message={}",
                    event.invoiceId(),
                    event.clientPaymentTransactionId(),
                    event.correlationId(),
                    exception.getMessage(),
                    exception
            );
        }
    }
}

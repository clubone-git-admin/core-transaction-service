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

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    public void provisionInventory(
            FinalizedInvoiceInventoryEvent event) {

        try {
            InventoryProvisioningResult result =
                    inventoryProvisioningHelper
                            .provisionForFinalizedInvoice(
                                    event.invoiceId(),
                                    event.clientPaymentTransactionId(),
                                    event.actorId(),
                                    event.correlationId()
                            );

            log.info(
                    "Inventory provisioning completed "
                            + "invoiceId={}, clientRoleId={}, "
                            + "invoiceEntityCount={}, "
                            + "entitlementCount={}, createdCount={}, "
                            + "skippedCount={}",
                    result.invoiceId(),
                    result.clientRoleId(),
                    result.invoiceEntityCount(),
                    result.entitlementCount(),
                    result.createdCount(),
                    result.skippedCount()
            );

        } catch (Exception exception) {
            /*
             * The payment and invoice have already committed.
             * Therefore, do not throw this error back into the
             * completed payment transaction.
             *
             * Production recommendation:
             * persist an outbox/retry record here.
             */
            log.error(
                    "Inventory provisioning failed after finalize "
                            + "invoiceId={}, "
                            + "clientPaymentTransactionId={}, "
                            + "correlationId={}, message={}",
                    event.invoiceId(),
                    event.clientPaymentTransactionId(),
                    event.correlationId(),
                    exception.getMessage(),
                    exception
            );
        }
    }
}
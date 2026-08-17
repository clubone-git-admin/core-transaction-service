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

    /**
     * fallbackExecution is required for agreement purchases because their
     * inventory event is published after asynchronous billing-quote
     * persistence has committed and therefore has no surrounding transaction.
     */
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void provisionInventory(FinalizedInvoiceInventoryEvent event) {

        log.info(
                "[inventory-provisioning] step=listener_received "
                        + "thread={} invoiceId={} clientPaymentTransactionId={} "
                        + "actorId={} locationId={} applicationId={} correlationId={}",
                Thread.currentThread().getName(),
                event.invoiceId(),
                event.clientPaymentTransactionId(),
                event.actorId(),
                event.locationId(),
                event.applicationId(),
                event.correlationId()
        );

        try {
            log.info(
                    "[inventory-provisioning] step=helper_call start "
                            + "invoiceId={} clientPaymentTransactionId={} correlationId={}",
                    event.invoiceId(),
                    event.clientPaymentTransactionId(),
                    event.correlationId()
            );

            InventoryProvisioningResult result =
                    inventoryProvisioningHelper.provisionForFinalizedInvoice(
                            event.invoiceId(),
                            event.clientPaymentTransactionId(),
                            event.actorId(),
                            event.locationId(),
                            event.applicationId(),
                            event.correlationId()
                    );

            if (result == null) {
                log.warn(
                        "[inventory-provisioning] step=helper_call outcome=null_result "
                                + "invoiceId={} clientPaymentTransactionId={} correlationId={}",
                        event.invoiceId(),
                        event.clientPaymentTransactionId(),
                        event.correlationId()
                );
                return;
            }

            log.info(
                    "[inventory-provisioning] step=complete outcome=ok "
                            + "invoiceId={} clientRoleId={} "
                            + "invoiceEntityCount={} entitlementCount={} "
                            + "createdCount={} skippedCount={} correlationId={}",
                    result.invoiceId(),
                    result.clientRoleId(),
                    result.invoiceEntityCount(),
                    result.entitlementCount(),
                    result.createdCount(),
                    result.skippedCount(),
                    event.correlationId()
            );
        } catch (Exception exception) {
            log.error(
                    "[inventory-provisioning] step=complete outcome=failed "
                            + "exceptionType={} invoiceId={} clientPaymentTransactionId={} "
                            + "actorId={} locationId={} applicationId={} "
                            + "correlationId={} message={}",
                    exception.getClass().getName(),
                    event.invoiceId(),
                    event.clientPaymentTransactionId(),
                    event.actorId(),
                    event.locationId(),
                    event.applicationId(),
                    event.correlationId(),
                    exception.getMessage(),
                    exception
            );
        }
    }
}

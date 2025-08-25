package io.clubone.transaction.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import io.clubone.transaction.vo.InvoiceEntityDTO;
import lombok.Data;

@Data
public class CreateInvoiceRequestV3 {
	private UUID clientRoleId;
    private UUID invoiceStatusId; // PENDING_PAYMENT UUID
    private UUID clientAgreementId;
    private UUID bundleId;
    private UUID levelId;
    private String billingAddress;

    private BigDecimal totalAmount;
    private BigDecimal subTotal;
    private BigDecimal taxAmount;
    private BigDecimal discountAmount;
    private boolean isPaid;

    private UUID createdBy;
    private List<InvoiceEntityDTO> lineItems;

}

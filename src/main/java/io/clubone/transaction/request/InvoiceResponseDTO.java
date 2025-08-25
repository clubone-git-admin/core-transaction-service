package io.clubone.transaction.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import io.clubone.transaction.vo.BundleDTO;
import io.clubone.transaction.vo.InvoiceItemDTO;
import io.clubone.transaction.vo.TransactionInfoDTO;
import lombok.Data;

@Data
public class InvoiceResponseDTO {
 private UUID invoiceId;
 private String invoiceNumber;
 private LocalDate invoiceDate;

 private BigDecimal totalAmount;
 private BigDecimal subTotal;
 private BigDecimal taxAmount;
 private BigDecimal discountAmount;

 private TransactionInfoDTO transaction;


 private List<BundleDTO> bundles = new ArrayList<>();     
 private List<InvoiceItemDTO> items = new ArrayList<>(); 

}


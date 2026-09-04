package io.clubone.transaction.dao;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.clubone.transaction.v2.vo.InvoicePromotionDetailDTO;
import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.TransactionDTO;

public interface InvoiceDAO {

	InvoiceDTO findResolvedById(UUID invoiceId);

	/** Header + lines + taxes, discounts, promotions, price bands, entity names */
	InvoiceDTO findResolvedFullById(UUID invoiceId);

	Optional<UUID> findInvoiceIdByNumber(String invoiceNumber);

	TransactionDTO findLatestTransactionByInvoiceId(UUID invoiceId);

	List<TransactionDTO> findAllTransactionsByInvoiceId(UUID invoiceId);

	int updateClientAgreementId(UUID invoiceId, UUID clientAgreementId);

	List<InvoicePromotionDetailDTO> findPromotionDetailsByInvoiceId(UUID invoiceId);

	List<AgreementPromotionSummary> findAgreementPromotionSummaries(UUID clientAgreementId);

	record AgreementPromotionSummary(
			UUID promotionId,
			UUID promotionVersionId,
			String promotionName,
			BigDecimal discountAmount
	) {
	}

}

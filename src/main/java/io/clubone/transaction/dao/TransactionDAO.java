package io.clubone.transaction.dao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.clubone.transaction.v2.vo.BundlePriceCycleBandDTO;
import io.clubone.transaction.v2.vo.DiscountDetailDTO;
import io.clubone.transaction.vo.BundleComponent;
import io.clubone.transaction.vo.BundleItemPriceDTO;
import io.clubone.transaction.vo.EntityTypeDTO;
import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.InvoiceEntityRow;
import io.clubone.transaction.vo.InvoiceFlatRow;
import io.clubone.transaction.vo.InvoiceSummaryDTO;
import io.clubone.transaction.vo.ItemPriceDTO;
import io.clubone.transaction.vo.TaxRateAllocationDTO;
import io.clubone.transaction.vo.TransactionDTO;

public interface TransactionDAO {

	UUID saveInvoice(InvoiceDTO dto);

	String findInvoiceNumber(UUID invoiceId);

	UUID saveTransaction(TransactionDTO dto);

	UUID findTransactionIdByInvoiceId(UUID invoiceId); // return null if not found

	UUID findClientPaymentTxnIdByTransactionId(UUID transactionId);

	UUID findInvoiceStatusIdByName(String statusName);

	void updateInvoiceStatusAndPaidFlag(UUID invoiceId, UUID statusId, boolean paid, UUID modifiedBy);

	String currentInvoiceStatusName(UUID invoiceId);

	List<BundleComponent> findBundleComponents(UUID bundleId);

	UUID findEntityTypeIdByName(String string);

	List<BundleItemPriceDTO> getBundleItemsWithPrices(UUID bundleId, UUID levelId);

	Optional<EntityTypeDTO> getEntityTypeById(UUID entityTypeId);

	UUID saveInvoiceV3(InvoiceDTO inv);

	Optional<ItemPriceDTO> getItemPriceByItemAndLevel(UUID itemId, UUID levelId);

	UUID saveTransactionV3(TransactionDTO txn);

	Optional<InvoiceSummaryDTO> getInvoiceSummaryById(UUID invoiceId);

	int updateClientAgreementId(UUID transactionId, UUID clientAgreementId);

	List<TaxRateAllocationDTO> getTaxRatesByGroupAndLevel(UUID taxGroupId, UUID levelId);
	
	List<InvoiceFlatRow> findInvoicesWithLatestTxnByClientRole(UUID clientRoleId);

	List<InvoiceEntityRow> findEntitiesByInvoiceIds(List<UUID> invoiceIds);

	UUID findTaxGroupIdForItem(UUID entityId, UUID levelId);
	
	Optional<DiscountDetailDTO> findBestDiscountForItemByIds(UUID itemId, UUID levelId, List<UUID> discountIds);
	
	 List<BundlePriceCycleBandDTO> findByPriceCycleBandId(UUID priceCycleBandId);

}

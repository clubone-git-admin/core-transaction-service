package io.clubone.transaction.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clubone.transaction.dao.InvoiceDAO;
import io.clubone.transaction.dao.TransactionDAO;
import io.clubone.transaction.helper.AgreementHelper;
import io.clubone.transaction.helper.InvoiceNotificationHelper;
import io.clubone.transaction.billing.quote.BillingQuoteSubscriptionPersistenceService;
import io.clubone.transaction.helper.SubscriptionPlanHelper;
import io.clubone.transaction.request.CreateInvoiceRequest;
import io.clubone.transaction.request.CreateInvoiceRequestV3;
import io.clubone.transaction.request.CreateTransactionRequest;
import io.clubone.transaction.request.FinalizeTransactionRequest;
import io.clubone.transaction.request.InvoiceResponseDTO;
import io.clubone.transaction.request.TransactionLineItemRequest;
import io.clubone.transaction.response.BillingQuoteLineItemsResponse;
import io.clubone.transaction.response.CreateInvoiceResponse;
import io.clubone.transaction.response.FinalizeTransactionResponse;
import io.clubone.transaction.service.PaymentService;
import io.clubone.transaction.service.TransactionService;
import io.clubone.transaction.vo.BundleDTO;
import io.clubone.transaction.vo.BundleItemPriceDTO;
import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.InvoiceEntityDTO;
import io.clubone.transaction.vo.InvoiceEntityRow;
import io.clubone.transaction.vo.InvoiceEntityTaxDTO;
import io.clubone.transaction.vo.InvoiceFlatRow;
import io.clubone.transaction.vo.InvoiceItemDTO;
import io.clubone.transaction.vo.InvoiceSummaryDTO;
import io.clubone.transaction.vo.ItemPriceDTO;
import io.clubone.transaction.vo.PaymentRequestDTO;
import io.clubone.transaction.vo.TaxRateAllocationDTO;
import io.clubone.transaction.vo.TransactionDTO;
import io.clubone.transaction.vo.TransactionEntityDTO;
import io.clubone.transaction.vo.TransactionEntityTaxDTO;
import io.clubone.transaction.vo.TransactionInfoDTO;

@Service
public class TransactionServiceImpl implements TransactionService {

	@Autowired
	private TransactionDAO transactionDAO;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private SubscriptionPlanHelper subscriptionPlanHelper;

	@Autowired
	private BillingQuoteSubscriptionPersistenceService billingQuoteSubscriptionPersistenceService;

	@Autowired
	private AgreementHelper agreementHelper;
	
	@Autowired
	private InvoiceDAO invoiceDao;
	
	@Autowired
	private InvoiceNotificationHelper invoiceNotificationHelper;

	private static final Logger logger = LoggerFactory.getLogger(TransactionServiceImpl.class);

	@Value("${clubone.transaction.finalize.amount-tolerance:0.09}")
	private BigDecimal finalizeAmountTolerance;

	@Value("${clubone.transaction.finalize.partially-paid-status-name:}")
	private String partiallyPaidStatusName;

	@Value("${clubone.invoice.initial-status-name:PENDING}")
	private String invoiceInitialStatusName;

	@Override
	public UUID createInvoice(InvoiceDTO dto) {
		return transactionDAO.saveInvoice(dto);
	}

	@Override
	public UUID createTransaction(TransactionDTO dto) {
		return transactionDAO.saveTransaction(dto);
	}

	@Override
	public UUID createAndFinalizeTransaction(CreateTransactionRequest request) {
		Instant now = Instant.now();

		// Step 1: Build InvoiceDTO
		InvoiceDTO invoice = new InvoiceDTO();
		invoice.setInvoiceDate(Timestamp.from(now));
		invoice.setClientRoleId(request.getClientRoleId());
		invoice.setBillingAddress(request.getBillingAddress());
		invoice.setInvoiceStatusId(transactionDAO.findInvoiceStatusIdByName("PENDING"));
		invoice.setSubTotal(request.getSubTotal());
		invoice.setTaxAmount(request.getTaxAmount());
		invoice.setDiscountAmount(request.getDiscountAmount());
		invoice.setTotalAmount(request.getTotalAmount());
		invoice.setPaid(false);

		// Step 2: Build PaymentRequestDTO
		PaymentRequestDTO payment = new PaymentRequestDTO();
		payment.setClientRoleId(request.getClientRoleId());
		payment.setAmount(request.getTotalAmount());
		payment.setPaymentGatewayCode("MANUAL");
		payment.setPaymentMethodCode("CASH");
		payment.setPaymentTypeCode("CASH");
		payment.setCreatedBy(request.getCreatedBy());

		// Step 3: Build TransactionDTO
		TransactionDTO txn = new TransactionDTO();
		txn.setClientAgreementId(request.getClientAgreementId());
		txn.setLevelId(request.getLevelId());
		txn.setTransactionDate(Timestamp.from(now));

		if (request.getLineItems() != null) {
			List<TransactionEntityDTO> entityList = new ArrayList<>();
			for (TransactionLineItemRequest li : request.getLineItems()) {
				TransactionEntityDTO entity = new TransactionEntityDTO();
				entity.setEntityTypeId(li.getEntityTypeId());
				entity.setEntityId(li.getEntityId());
				entity.setEntityDescription(li.getEntityDescription());
				entity.setQuantity(li.getQuantity());
				entity.setUnitPrice(li.getUnitPrice());
				entity.setDiscountAmount(li.getDiscountAmount());
				entity.setTaxAmount(li.getTaxAmount());
				entity.setTotalAmount(li.getUnitPrice().multiply(BigDecimal.valueOf(li.getQuantity())));
				entity.setTaxes(new ArrayList<>()); // If needed
				entityList.add(entity);
			}
			txn.setLineItems(entityList);
		}

		// Step 4: Create Invoice
		UUID invoiceId = transactionDAO.saveInvoice(invoice);

		// Step 5: Create Payment
		UUID clientPaymentTransactionId = paymentService.processManualPayment(payment);

		// Step 6: Finalize Transaction
		txn.setInvoiceId(invoiceId);
		txn.setClientPaymentTransactionId(clientPaymentTransactionId);

		return transactionDAO.saveTransaction(txn);
	}

	@Override
	@Transactional
	public CreateInvoiceResponse createInvoice(CreateInvoiceRequest req) {

		req.setInvoiceStatusId(transactionDAO.findInvoiceStatusIdByName("PENDING"));
		// Validate totals vs line items
		BigDecimal lineSum = req.getLineItems().stream().map(TransactionLineItemRequest::getTotalAmount)
				.filter(Objects::nonNull) // ignore nulls
				.map(bd -> bd.setScale(2, RoundingMode.HALF_UP)) // normalize to currency scale
				.reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);

		BigDecimal headerTotal = Optional.ofNullable(req.getTotalAmount()).orElse(BigDecimal.ZERO).setScale(2,
				RoundingMode.HALF_UP);

		if (lineSum.compareTo(headerTotal) != 0) {
			logger.error("Invoice total mismatch: header={}, sumOfLines={}, difference={}", headerTotal, lineSum,
					headerTotal.subtract(lineSum));
			/*
			 * throw new IllegalArgumentException( String.
			 * format("Invoice total mismatch: header=%s, sumOfLines=%s, difference=%s",
			 * headerTotal, lineSum, headerTotal.subtract(lineSum)));
			 */
		}

		InvoiceDTO inv = new InvoiceDTO();
		inv.setInvoiceDate(Timestamp.from(Instant.now()));
		inv.setClientRoleId(req.getClientRoleId());
		inv.setBillingAddress(req.getBillingAddress());
		inv.setInvoiceStatusId(req.getInvoiceStatusId()); // PENDING_PAYMENT from caller
		inv.setSubTotal(req.getSubTotal());
		inv.setTaxAmount(req.getTaxAmount());
		inv.setDiscountAmount(req.getDiscountAmount());
		inv.setTotalAmount(req.getTotalAmount());
		inv.setPaid(false);
		inv.setCreatedBy(req.getCreatedBy());

		UUID invoiceId = transactionDAO.saveInvoice(inv);
		String invoiceNumber = transactionDAO.findInvoiceNumber(invoiceId);

		return CreateInvoiceResponse.basic(invoiceId, invoiceNumber, "PENDING_PAYMENT");
	}

	@Override
	@Transactional
	public FinalizeTransactionResponse finalizeTransaction(FinalizeTransactionRequest req) {
		// Idempotency guard: if a transaction already exists for this invoice, return
		// it
		UUID existingTxn = transactionDAO.findTransactionIdByInvoiceId(req.getInvoiceId());
		if (existingTxn != null) {
			UUID existingCpt = transactionDAO.findClientPaymentTxnIdByTransactionId(existingTxn);
			String status = transactionDAO.currentInvoiceStatusName(req.getInvoiceId());
			return new FinalizeTransactionResponse(req.getInvoiceId(), status, existingCpt, existingTxn, "");
		}

		// Validate totals vs line items
		BigDecimal lineSum = req.getLineItems().stream().map(TransactionLineItemRequest::getTotalAmount)
				.filter(Objects::nonNull) // ignore nulls
				.map(bd -> bd.setScale(2, RoundingMode.HALF_UP)) // normalize to currency scale
				.reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);

		BigDecimal headerTotal = Optional.ofNullable(req.getTotalAmount()).orElse(BigDecimal.ZERO).setScale(2,
				RoundingMode.HALF_UP);

		if (lineSum.compareTo(headerTotal) != 0) {
			logger.error("Invoice total mismatch: header={}, sumOfLines={}, difference={}", headerTotal, lineSum,
					headerTotal.subtract(lineSum));
			/*
			 * throw new IllegalArgumentException( String.
			 * format("Invoice total mismatch: header=%s, sumOfLines=%s, difference=%s",
			 * headerTotal, lineSum, headerTotal.subtract(lineSum)));
			 */
		}

		// Call your existing /payment/v1
		PaymentRequestDTO pay = new PaymentRequestDTO();
		pay.setClientRoleId(req.getClientRoleId());
		pay.setAmount(req.getTotalAmount());
		pay.setPaymentGatewayCode(req.getPaymentGatewayCode());
		pay.setPaymentMethodCode(req.getPaymentMethodCode());
		pay.setPaymentTypeCode(req.getPaymentTypeCode());
		pay.setPaymentGatewayCurrencyTypeId(req.getPaymentGatewayCurrencyTypeId());
		pay.setCreatedBy(req.getCreatedBy());

		UUID clientPaymentTransactionId = paymentService.processManualPayment(pay);

		// Build TransactionDTO from request
		TransactionDTO txn = new TransactionDTO();
		txn.setClientAgreementId(req.getClientAgreementId());
		txn.setLevelId(req.getLevelId());
		txn.setInvoiceId(req.getInvoiceId());
		txn.setClientPaymentTransactionId(clientPaymentTransactionId);
		txn.setTransactionDate(new Timestamp(System.currentTimeMillis()));
		txn.setCreatedBy(req.getCreatedBy());

		List<TransactionEntityDTO> items = new ArrayList<>();

		boolean hasAgreement = req.getClientAgreementId() != null;
		boolean hasBundle = req.getBundleId() != null;

		if (hasAgreement) {
			// AGREEMENT header (amounts = 0 to avoid double counting)
			UUID agreementTypeId = transactionDAO.findEntityTypeIdByName("AGREEMENT");

			TransactionEntityDTO agreementHeader = new TransactionEntityDTO();
			agreementHeader.setEntityTypeId(agreementTypeId);
			agreementHeader.setEntityId(req.getClientAgreementId());
			agreementHeader.setEntityDescription("Agreement Purchase");
			agreementHeader.setQuantity(1);
			agreementHeader.setUnitPrice(BigDecimal.ZERO);
			agreementHeader.setDiscountAmount(BigDecimal.ZERO);
			agreementHeader.setTaxAmount(BigDecimal.ZERO);
			agreementHeader.setTotalAmount(BigDecimal.ZERO);
			agreementHeader.setTaxes(List.of());
			// no parent for agreement header
			items.add(agreementHeader);
		}

		UUID bundleHeaderEntityTypeId = null;
		if (hasBundle) {
			// BUNDLE header (carry basket totals)
			bundleHeaderEntityTypeId = transactionDAO.findEntityTypeIdByName("BUNDLE");

			TransactionEntityDTO bundleHeader = new TransactionEntityDTO();
			bundleHeader.setEntityTypeId(bundleHeaderEntityTypeId);
			bundleHeader.setEntityId(req.getBundleId());
			bundleHeader.setEntityDescription("Bundle Purchase");
			bundleHeader.setQuantity(1);
			bundleHeader.setUnitPrice(req.getSubTotal()); // or your bundle base price
			bundleHeader.setDiscountAmount(req.getDiscountAmount());
			bundleHeader.setTaxAmount(req.getTaxAmount());
			bundleHeader.setTotalAmount(req.getTotalAmount());
			bundleHeader.setTaxes(List.of());
			// no parent for bundle header
			items.add(bundleHeader);
		}

		for (TransactionLineItemRequest li : req.getLineItems()) {
			TransactionEntityDTO e = new TransactionEntityDTO();
			e.setEntityTypeId(li.getEntityTypeId());
			e.setEntityId(li.getEntityId());
			e.setEntityDescription(li.getEntityDescription());
			e.setQuantity(li.getQuantity());
			e.setUnitPrice(li.getUnitPrice());
			e.setDiscountAmount(li.getDiscountAmount());
			e.setTaxAmount(li.getTaxAmount());
			e.setTotalAmount(li.getTotalAmount());
			if (li.isUpsellItem()) {
				e.setParentTransactionEntityId(req.getClientAgreementId());
			}
			if (li.getTaxes() != null) {
				List<TransactionEntityTaxDTO> taxes = li.getTaxes().stream().map(t -> {
					TransactionEntityTaxDTO tt = new TransactionEntityTaxDTO();
					tt.setTaxRateId(t.getTaxRateId());
					tt.setTaxRate(t.getTaxRate());
					tt.setTaxAmount(t.getTaxAmount());
					return tt;
				}).toList();
				e.setTaxes(taxes);
			} else {
				e.setTaxes(List.of());
			}
			items.add(e);
		}
		txn.setLineItems(items);

		UUID transactionId = transactionDAO.saveTransaction(txn);

		// Mark invoice as PAID
		UUID paidStatusId = transactionDAO.findInvoiceStatusIdByName("PAID");
		transactionDAO.updateInvoiceStatusAndPaidFlag(req.getInvoiceId(), paidStatusId, true, req.getCreatedBy());

		return new FinalizeTransactionResponse(req.getInvoiceId(), "PAID", clientPaymentTransactionId, transactionId,
				"");
	}

	@Override
	public CreateInvoiceResponse createInvoiceV3(CreateInvoiceRequestV3 req) {

		req.setInvoiceStatusId(transactionDAO.findInvoiceStatusIdByName("PENDING"));
		UUID itemEntityTypeId = transactionDAO.findEntityTypeIdByName("Item");
		UUID bundleEntityTypeId = transactionDAO.findEntityTypeIdByName("Bundle");
		System.out.println("Bundle Entity type Id " + bundleEntityTypeId);
		BigDecimal totalTaxAmount = BigDecimal.ZERO;
		BigDecimal totalDiscountAmount = BigDecimal.ZERO;
		BigDecimal totalUnitPrice = BigDecimal.ZERO;
		BigDecimal totalAmount = BigDecimal.ZERO;
		BigDecimal subTotalSum = BigDecimal.ZERO;
		BigDecimal taxSum = BigDecimal.ZERO;
		BigDecimal discountSum = BigDecimal.ZERO;

		InvoiceDTO inv = new InvoiceDTO();
		inv.setInvoiceDate(Timestamp.from(Instant.now()));
		inv.setClientRoleId(req.getClientRoleId());
		inv.setLevelId(req.getLevelId());
		inv.setBillingAddress(req.getBillingAddress());
		inv.setInvoiceStatusId(req.getInvoiceStatusId());

		List<InvoiceEntityDTO> invoiceEntities = new ArrayList<>();

		for (InvoiceEntityDTO invoiceEntityDTO : req.getLineItems()) {
			// Optional<EntityTypeDTO> entityType =
			// transactionDAO.getEntityTypeById(invoiceEntityDTO.getEntityTypeId());
			String bundleDescription = "";
			System.out.println("Invoice Entity type Id " + invoiceEntityDTO.getEntityTypeId());
			if (bundleEntityTypeId.equals(invoiceEntityDTO.getEntityTypeId())) {
				System.out.println("here");
				List<BundleItemPriceDTO> items = transactionDAO.getBundleItemsWithPrices(invoiceEntityDTO.getEntityId(),
						req.getLevelId());
				List<InvoiceEntityDTO> itemsEntity = new ArrayList<>();
				BigDecimal bundleunitPrice = BigDecimal.ZERO;
				BigDecimal bundletaxAmount = BigDecimal.ZERO;
				for (BundleItemPriceDTO bundleprice : items) {
					BigDecimal taxAmount = BigDecimal.ZERO;
					System.out.println("here1");
					List<InvoiceEntityTaxDTO> taxes = new ArrayList<>();
					bundleDescription = bundleprice.getDescription();
					InvoiceEntityDTO dto = new InvoiceEntityDTO();
					dto.setEntityId(bundleprice.getItemId());
					dto.setEntityTypeId(itemEntityTypeId);
					// dto.setParentInvoiceEntityId(invoiceEntityDTO.getEntityId());
					dto.setEntityDescription(bundleprice.getItemDescription());
					dto.setQuantity(bundleprice.getItemQuantity().intValue());

					dto.setDiscountAmount(BigDecimal.ZERO);
					dto.setUnitPrice(bundleprice.getItemPrice());
					dto.setTotalAmount(bundleprice.getItemQuantity().multiply(dto.getUnitPrice()));
					subTotalSum = subTotalSum.add(dto.getTotalAmount());
					dto.setUpsellItem(false);
					bundleunitPrice = bundleunitPrice.add(dto.getUnitPrice());

					if (bundleprice.getTaxGroupId() != null) {
						List<TaxRateAllocationDTO> taxAllocations = transactionDAO
								.getTaxRatesByGroupAndLevel(bundleprice.getTaxGroupId(), req.getLevelId());
						if (!CollectionUtils.isEmpty(taxAllocations)) {
							for (TaxRateAllocationDTO taxRate : taxAllocations) {
								InvoiceEntityTaxDTO invoiceEntityTaxDTO = new InvoiceEntityTaxDTO();
								invoiceEntityTaxDTO.setTaxRate(taxRate.getTaxRatePercentage());
								invoiceEntityTaxDTO.setTaxRateId(taxRate.getTaxRateId());
								invoiceEntityTaxDTO.setTaxAmount(dto.getUnitPrice()
										.multiply(bundleprice.getItemQuantity())
										.multiply(taxRate.getTaxRatePercentage()).divide(new BigDecimal("100")));
								taxes.add(invoiceEntityTaxDTO);

								taxAmount = taxAmount.add(invoiceEntityTaxDTO.getTaxAmount());
								bundletaxAmount = taxAmount;
								taxSum = taxSum.add(invoiceEntityTaxDTO.getTaxAmount());

								totalTaxAmount = totalTaxAmount.add(invoiceEntityTaxDTO.getTaxAmount());
							}
							System.out.println("TaxAmount " + taxAmount);
							dto.setTaxAmount(taxAmount);
							System.out.println("Total " + bundleprice.getItemQuantity().multiply(dto.getUnitPrice())
									.add(dto.getTaxAmount()));
							dto.setTotalAmount(
									bundleprice.getItemQuantity().multiply(dto.getUnitPrice()).add(dto.getTaxAmount()));

							dto.setTaxes(taxes);
						}
					}

					totalDiscountAmount = totalDiscountAmount.add(dto.getDiscountAmount());
					totalUnitPrice = totalUnitPrice.add(dto.getUnitPrice());
					totalAmount = totalAmount.add(dto.getTotalAmount());

					itemsEntity.add(dto);

					if (Objects.nonNull(bundleprice.getIsContinuous()) && bundleprice.getIsContinuous()) {

						// For Subscription Plan

					}
				}
				invoiceEntityDTO.setEntityDescription(bundleDescription);
				invoiceEntityDTO.setTaxAmount(bundletaxAmount);
				invoiceEntityDTO.setTotalAmount(bundleunitPrice.add(bundletaxAmount));
				invoiceEntityDTO.setUnitPrice(bundleunitPrice);
				invoiceEntityDTO.setDiscountAmount(totalDiscountAmount);

				invoiceEntities.add(invoiceEntityDTO);
				invoiceEntities.addAll(itemsEntity);
			} else if (itemEntityTypeId.equals(invoiceEntityDTO.getEntityTypeId())) {
				Optional<ItemPriceDTO> items = transactionDAO.getItemPriceByItemAndLevel(invoiceEntityDTO.getEntityId(),
						req.getLevelId());
				if (items.isPresent() && items.get() != null) {
					ItemPriceDTO item = items.get();
					BigDecimal taxAmount = BigDecimal.ZERO;
					InvoiceEntityDTO dto = new InvoiceEntityDTO();
					dto.setEntityId(invoiceEntityDTO.getEntityTypeId());
					dto.setEntityTypeId(itemEntityTypeId);
					dto.setParentInvoiceEntityId(invoiceEntityDTO.getEntityId());
					dto.setEntityDescription(item.getItemDescription());
					dto.setQuantity(invoiceEntityDTO.getQuantity());

					dto.setDiscountAmount(BigDecimal.ZERO);
					dto.setUnitPrice(item.getItemPrice());
					dto.setTotalAmount(BigDecimal.valueOf(invoiceEntityDTO.getQuantity()).multiply(dto.getUnitPrice()));
					subTotalSum = subTotalSum.add(dto.getTotalAmount());
					dto.setUpsellItem(false);

					if (item.getTaxGroupId() != null) {
						List<TaxRateAllocationDTO> taxAllocations = transactionDAO
								.getTaxRatesByGroupAndLevel(item.getTaxGroupId(), req.getLevelId());
						if (!CollectionUtils.isEmpty(taxAllocations)) {
							List<InvoiceEntityTaxDTO> taxes = new ArrayList<>();
							for (TaxRateAllocationDTO taxRate : taxAllocations) {
								InvoiceEntityTaxDTO invoiceEntityTaxDTO = new InvoiceEntityTaxDTO();
								invoiceEntityTaxDTO.setTaxRate(taxRate.getTaxRatePercentage());
								invoiceEntityTaxDTO.setTaxRateId(taxRate.getTaxRateId());
								invoiceEntityTaxDTO.setTaxAmount(dto.getUnitPrice()
										.multiply(BigDecimal.valueOf(invoiceEntityDTO.getQuantity()))
										.multiply(taxRate.getTaxRatePercentage()).divide(new BigDecimal("100")));
								taxAmount = taxAmount.add(invoiceEntityTaxDTO.getTaxAmount());
								taxes.add(invoiceEntityTaxDTO);
								taxSum = taxSum.add(invoiceEntityTaxDTO.getTaxAmount());
								totalTaxAmount = totalTaxAmount.add(invoiceEntityTaxDTO.getTaxAmount());
							}
							dto.setTaxAmount(taxAmount);
							dto.setTotalAmount(BigDecimal.valueOf(invoiceEntityDTO.getQuantity())
									.multiply(dto.getUnitPrice()).add(dto.getTaxAmount()));
							dto.setTaxes(taxes);
						}
					}

					totalTaxAmount = totalTaxAmount.add(dto.getTaxAmount());
					totalDiscountAmount = totalDiscountAmount.add(dto.getDiscountAmount());
					totalUnitPrice = totalUnitPrice.add(dto.getUnitPrice());
					totalAmount = totalAmount.add(dto.getTotalAmount());

					invoiceEntities.add(dto);
				}
			}
		}

		inv.setTotalAmount(subTotalSum.add(taxSum));
		inv.setSubTotal(subTotalSum);
		inv.setTaxAmount(taxSum);
		inv.setDiscountAmount(totalDiscountAmount);
		inv.setPaid(false);
		inv.setCreatedBy(req.getCreatedBy());
		inv.setLineItems(invoiceEntities);

		ObjectMapper mapper = new ObjectMapper();
		try {
			System.out.println("Data " + mapper.writeValueAsString(inv));
		} catch (JsonProcessingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		UUID invoiceId = transactionDAO.saveInvoiceV3(inv);
		String invoiceNumber = transactionDAO.findInvoiceNumber(invoiceId);

		return CreateInvoiceResponse.basic(invoiceId, invoiceNumber, "PENDING_PAYMENT");
	}

	@Override
	@Transactional
	public FinalizeTransactionResponse finalizeTransactionV3(FinalizeTransactionRequest req) {
		logger.info(
				"[transactions/v3/finalize] step=start invoiceId={} paymentGatewayCode={} paymentMethodCode={} hasClientPaymentTransactionId={} amountToPayNow={} billingQuoteSpecCount={} clientAgreementId={}",
				req.getInvoiceId(), req.getPaymentGatewayCode(), req.getPaymentMethodCode(),
				req.getClientPaymentTransactionId() != null, req.getAmountToPayNow(),
				req.getBillingQuoteFinalizeSpecs() == null ? 0 : req.getBillingQuoteFinalizeSpecs().size(),
				req.getClientAgreementId());

		if (req.getInvoiceId() == null) {
			logger.warn("[transactions/v3/finalize] step=validation outcome=reject reason=missing_invoice_id");
			return new FinalizeTransactionResponse(null, "UNPAID", null, null, "invoiceId is required");
		}

		Optional<InvoiceSummaryDTO> invoiceSummaryOpt = transactionDAO.getInvoiceSummaryById(req.getInvoiceId());
		if (invoiceSummaryOpt.isEmpty()) {
			logger.warn("[transactions/v3/finalize] step=load_invoice outcome=reject invoiceId={} reason=not_found",
					req.getInvoiceId());
			return new FinalizeTransactionResponse(req.getInvoiceId(), "UNPAID", null, null, "Invoice not found");
		}
		InvoiceSummaryDTO invoiceSummary = invoiceSummaryOpt.get();
		req.setClientRoleId(invoiceSummary.getClientRoleId());
		req.setTotalAmount(invoiceSummary.getTotalAmount());
		req.setLevelId(invoiceSummary.getLevelId());
		final UUID effectiveClientAgreementId = req.getClientAgreementId() != null ? req.getClientAgreementId()
				: invoiceSummary.getClientAgreementId();
		logger.info(
				"[transactions/v3/finalize] step=load_invoice outcome=ok invoiceId={} clientRoleId={} levelId={} invoiceTotal={} invoiceClientAgreementId={}",
				req.getInvoiceId(), invoiceSummary.getClientRoleId(), invoiceSummary.getLevelId(),
				invoiceSummary.getTotalAmount(), invoiceSummary.getClientAgreementId());
		logger.info(
				"[transactions/v3/finalize] step=resolve_client_agreement invoiceId={} source={} effectiveClientAgreementId={}",
				req.getInvoiceId(),
				req.getClientAgreementId() != null ? "request"
						: (effectiveClientAgreementId != null ? "invoice" : "none"),
				effectiveClientAgreementId);

		final boolean isManual = isManualPaymentGateway(req);
		logger.info("[transactions/v3/finalize] step=payment_mode invoiceId={} isManual={} gatewayCode={}",
				req.getInvoiceId(), isManual, req.getPaymentGatewayCode());

		BigDecimal tolerance = finalizeAmountTolerance != null ? finalizeAmountTolerance.setScale(2, RoundingMode.HALF_UP)
				: new BigDecimal("0.09").setScale(2, RoundingMode.HALF_UP);
		BigDecimal invoiceTotal = nz(invoiceSummary.getTotalAmount()).setScale(2, RoundingMode.HALF_UP);

		BigDecimal payAmount;
		if (req.getAmountToPayNow() != null) {
			payAmount = req.getAmountToPayNow().setScale(2, RoundingMode.HALF_UP);
		} else {
			payAmount = invoiceTotal;
		}

		if (invoiceTotal.compareTo(BigDecimal.ZERO) > 0 && payAmount.compareTo(BigDecimal.ZERO) <= 0) {
			logger.warn(
					"[transactions/v3/finalize] step=amount_validation outcome=reject invoiceId={} reason=non_positive_pay_amount invoiceTotal={} payAmount={}",
					req.getInvoiceId(), invoiceTotal, payAmount);
			return new FinalizeTransactionResponse(req.getInvoiceId(), "UNPAID", null, null,
					"Payment amount must be greater than zero");
		}

		if (payAmount.subtract(invoiceTotal).compareTo(tolerance) > 0) {
			logger.warn(
					"[transactions/v3/finalize] step=amount_validation outcome=reject invoiceId={} payAmount={} invoiceTotal={} tolerance={} reason=overpayment",
					req.getInvoiceId(), payAmount, invoiceTotal, tolerance);
			return new FinalizeTransactionResponse(req.getInvoiceId(), "UNPAID", null, null,
					"Payment amount exceeds invoice balance");
		}

		boolean fullPayment = invoiceTotal.compareTo(BigDecimal.ZERO) == 0
				|| payAmount.add(tolerance).compareTo(invoiceTotal) >= 0;
		boolean partialPayment = invoiceTotal.compareTo(BigDecimal.ZERO) > 0 && !fullPayment;
		logger.info(
				"[transactions/v3/finalize] step=amount_validation outcome=ok invoiceId={} invoiceTotal={} payAmount={} tolerance={} fullPayment={} partialPayment={}",
				req.getInvoiceId(), invoiceTotal, payAmount, tolerance, fullPayment, partialPayment);

		UUID clientPaymentTransactionId = null;

		if (!isManual) {
			if (invoiceTotal.compareTo(BigDecimal.ZERO) > 0 && req.getClientPaymentTransactionId() == null) {
				logger.warn(
						"[transactions/v3/finalize] step=gateway_payment outcome=reject invoiceId={} reason=missing_client_payment_transaction_id",
						req.getInvoiceId());
				return new FinalizeTransactionResponse(req.getInvoiceId(), "UNPAID", null, null,
						"Missing clientPaymentTransactionId for gateway finalize");
			}
			clientPaymentTransactionId = req.getClientPaymentTransactionId();
			if (clientPaymentTransactionId != null) {
				/*
				 * UUID idempotentTxn =
				 * transactionDAO.findTransactionIdByInvoiceAndClientPaymentTransaction(
				 * req.getInvoiceId(), clientPaymentTransactionId); if (idempotentTxn != null) {
				 * String status = transactionDAO.currentInvoiceStatusName(req.getInvoiceId());
				 * logger.info(
				 * "[transactions/v3/finalize] step=idempotency outcome=short_circuit invoiceId={} clientPaymentTransactionId={} existingTransactionId={} invoiceStatus={}"
				 * , req.getInvoiceId(), clientPaymentTransactionId, idempotentTxn, status);
				 * return new FinalizeTransactionResponse(req.getInvoiceId(), status,
				 * clientPaymentTransactionId, idempotentTxn, ""); }
				 */
			}
			logger.info(
					"[transactions/v3/finalize] step=gateway_payment outcome=use_existing_cpt invoiceId={} clientPaymentTransactionId={}",
					req.getInvoiceId(), clientPaymentTransactionId);
		}

		if (invoiceTotal.compareTo(BigDecimal.ZERO) <= 0) {
			clientPaymentTransactionId = isManual ? null : req.getClientPaymentTransactionId();
			logger.info(
					"[transactions/v3/finalize] step=collect_payment outcome=zero_balance invoiceId={} clientPaymentTransactionId={}",
					req.getInvoiceId(), clientPaymentTransactionId);
		} else if (isManual) {
			logger.info(
					"[transactions/v3/finalize] step=collect_payment mode=manual invoiceId={} payAmount={} calling_payment_service=true",
					req.getInvoiceId(), payAmount);
			PaymentRequestDTO pay = new PaymentRequestDTO();
			pay.setClientRoleId(req.getClientRoleId());
			pay.setInvoiceId(req.getInvoiceId());
			pay.setAmount(payAmount);
			pay.setPaymentGatewayCode(req.getPaymentGatewayCode());
			pay.setPaymentMethodCode(req.getPaymentMethodCode());
			pay.setPaymentTypeCode(req.getPaymentTypeCode());
			pay.setPaymentGatewayCurrencyTypeId(req.getPaymentGatewayCurrencyTypeId());
			pay.setCreatedBy(req.getCreatedBy());
			clientPaymentTransactionId = paymentService.processManualPayment(pay);
			logger.info(
					"[transactions/v3/finalize] step=collect_payment mode=manual outcome=ok invoiceId={} clientPaymentTransactionId={}",
					req.getInvoiceId(), clientPaymentTransactionId);
		}
		// else: gateway — clientPaymentTransactionId already set and idempotency checked above

		TransactionDTO txn = new TransactionDTO();
		txn.setClientAgreementId(effectiveClientAgreementId);
		txn.setLevelId(req.getLevelId());
		txn.setInvoiceId(req.getInvoiceId());
		txn.setClientPaymentTransactionId(clientPaymentTransactionId);
		txn.setTransactionDate(Timestamp.from(Instant.now()));
		txn.setCreatedBy(req.getCreatedBy());

		UUID transactionId = transactionDAO.saveTransactionV3(txn);
		logger.info(
				"[transactions/v3/finalize] step=persist_transaction outcome=ok invoiceId={} transactionId={} clientPaymentTransactionId={}",
				req.getInvoiceId(), transactionId, clientPaymentTransactionId);

		logger.info("[transactions/v3/finalize] step=notification invoiceId={} sending_email=true", req.getInvoiceId());
		sendFinalizeInvoiceNotification(req, isManual);
		logger.info("[transactions/v3/finalize] step=notification invoiceId={} completed", req.getInvoiceId());

		String responseStatusName;
		if (partialPayment) {
			logger.info("[transactions/v3/finalize] step=invoice_update branch=partial invoiceId={} payAmount={} invoiceTotal={}",
					req.getInvoiceId(), payAmount, invoiceTotal);
			UUID partialStatusId = resolvePartialPaymentInvoiceStatusId();
			transactionDAO.updateInvoiceStatusAndPaidFlag(req.getInvoiceId(), partialStatusId, false,
					req.getCreatedBy());
			responseStatusName = transactionDAO.currentInvoiceStatusName(req.getInvoiceId());
			logger.info(
					"[transactions/v3/finalize] step=invoice_update branch=partial outcome=ok invoiceId={} status={} paidAmount={} of invoiceTotal={}",
					req.getInvoiceId(), responseStatusName, payAmount, invoiceTotal);
		} else {
			logger.info("[transactions/v3/finalize] step=invoice_update branch=full_payment invoiceId={}",
					req.getInvoiceId());
			UUID paidStatusId = transactionDAO.findInvoiceStatusIdByName("PAID");
			transactionDAO.updateInvoiceStatusAndPaidFlag(req.getInvoiceId(), paidStatusId, true, req.getCreatedBy());
			responseStatusName = transactionDAO.currentInvoiceStatusName(req.getInvoiceId());
			logger.info("[transactions/v3/finalize] step=invoice_update outcome=marked_paid invoiceId={} status={}",
					req.getInvoiceId(), responseStatusName);
			logger.info("[transactions/v3/finalize] step=activate_agreement invoiceId={} actor={}", req.getInvoiceId(),
					req.getCreatedBy());
			transactionDAO.activateAgreementAndClientStatusForInvoice(req.getInvoiceId(), req.getCreatedBy());
			logger.info("[transactions/v3/finalize] step=activate_agreement outcome=ok invoiceId={}",
					req.getInvoiceId());
			if (!CollectionUtils.isEmpty(req.getBillingQuoteFinalizeSpecs())) {
				try {
					logger.info(
							"[transactions/v3/finalize] step=billing_quote_fetch start invoiceId={} specCount={} transactionId={}",
							req.getInvoiceId(), req.getBillingQuoteFinalizeSpecs().size(), transactionId);
					List<BillingQuoteLineItemsResponse> quoteLineItems = subscriptionPlanHelper
							.fetchQuoteLineItems(req.getBillingQuoteFinalizeSpecs());
					logger.info(
							"[transactions/v3/finalize] step=billing_quote_fetch outcome=ok invoiceId={} responseCount={}",
							req.getInvoiceId(), quoteLineItems.size());
					try {
						logger.info(
								"[transactions/v3/finalize] step=billing_quote_persist start invoiceId={} transactionId={} clientAgreementId={}",
								req.getInvoiceId(), transactionId, effectiveClientAgreementId);
						billingQuoteSubscriptionPersistenceService.persistFromQuoteResponses(quoteLineItems,
								transactionId, effectiveClientAgreementId, req.getInvoiceId(),
								clientPaymentTransactionId, req.getCreatedBy());
						logger.info("[transactions/v3/finalize] step=billing_quote_persist outcome=ok invoiceId={}",
								req.getInvoiceId());
					} catch (Exception persistEx) {
						logger.error(
								"[transactions/v3/finalize] step=billing_quote_persist outcome=error invoiceId={} message={}",
								req.getInvoiceId(), persistEx.getMessage(), persistEx);
					}
				} catch (Exception e) {
					logger.error(
							"[transactions/v3/finalize] step=billing_quote_fetch outcome=error invoiceId={} message={}",
							req.getInvoiceId(), e.getMessage(), e);
				}
			} else {
				logger.info(
						"[transactions/v3/finalize] step=billing_quote skipped=true reason=no_billing_quote_finalize_specs invoiceId={}",
						req.getInvoiceId());
			}
		}

		logger.info(
				"[transactions/v3/finalize] step=complete invoiceId={} invoiceStatus={} transactionId={} clientPaymentTransactionId={} partialPayment={}",
				req.getInvoiceId(), responseStatusName, transactionId, clientPaymentTransactionId, partialPayment);
		return new FinalizeTransactionResponse(req.getInvoiceId(), responseStatusName, clientPaymentTransactionId,
				transactionId, partialPayment ? "Partial payment recorded" : "");
	}

	private boolean isManualPaymentGateway(FinalizeTransactionRequest req) {
		String code = req.getPaymentGatewayCode();
		return code == null || code.trim().isEmpty() || "MANUAL".equalsIgnoreCase(code.trim())
				|| "CASH".equalsIgnoreCase(code.trim());
	}

	private UUID resolvePartialPaymentInvoiceStatusId() {
		Optional<UUID> configured = Optional.empty();
		if (partiallyPaidStatusName != null && !partiallyPaidStatusName.isBlank()) {
			configured = transactionDAO.tryFindInvoiceStatusIdByName(partiallyPaidStatusName.trim());
		}
		return configured.orElseGet(() -> transactionDAO.findInvoiceStatusIdByName(invoiceInitialStatusName));
	}

	private void sendFinalizeInvoiceNotification(FinalizeTransactionRequest req, boolean isManual) {
		String methodType = deriveNotificationPaymentMethodType(req, isManual);
		String brand = blankToNull(req.getPaymentInstrumentBrand());
		String last4 = blankToNull(req.getPaymentInstrumentLast4());
		String auth = blankToNull(req.getPaymentAuthorizationReference());
		logger.info(
				"[transactions/v3/finalize] step=notification_detail invoiceId={} paymentInstrumentType={} hasBrand={} hasLast4={} hasAuthRef={}",
				req.getInvoiceId(), methodType, brand != null, last4 != null, auth != null);
		try {
			invoiceNotificationHelper.sendInvoiceEmail(req.getInvoiceId(), methodType, brand, last4, auth);
			logger.info("[transactions/v3/finalize] step=notification_send outcome=ok invoiceId={}", req.getInvoiceId());
		} catch (Exception e) {
			logger.warn("[transactions/v3/finalize] step=notification_send outcome=fail invoiceId={} message={}",
					req.getInvoiceId(), e.getMessage());
		}
	}

	private static String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s.trim();
	}

	private String deriveNotificationPaymentMethodType(FinalizeTransactionRequest req, boolean isManual) {
		if (req.getPaymentInstrumentType() != null && !req.getPaymentInstrumentType().isBlank()) {
			return req.getPaymentInstrumentType().trim();
		}
		if (isManual) {
			return "CASH";
		}
		String method = req.getPaymentMethodCode();
		if (method != null) {
			String u = method.toUpperCase();
			if (u.contains("CASH")) {
				return "CASH";
			}
			if (u.contains("UPI")) {
				return "UPI";
			}
		}
		return "CARD";
	}

	@Override
	@Transactional
	public boolean setClientAgreement(UUID transactionId, UUID clientAgreementId) {
		int updated = transactionDAO.updateClientAgreementId(transactionId, clientAgreementId);
		return updated > 0;
	}

	private static final BigDecimal HUNDRED = new BigDecimal("100");
	private static final RoundingMode RM = RoundingMode.HALF_UP;

	private static BigDecimal nz(BigDecimal v) {
		return v != null ? v : BigDecimal.ZERO;
	}

	// tax = base * (rate% / 100)
	private static BigDecimal pct(BigDecimal base, BigDecimal percent) {
		return nz(base).multiply(nz(percent)).divide(HUNDRED, 2, RM);
	}

	@Override
	public List<InvoiceResponseDTO> getInvoicesByClientRole(UUID clientRoleId) {
		// 1) Load invoices (one row per invoice) with latest txn
		List<InvoiceFlatRow> invoices = transactionDAO.findInvoicesWithLatestTxnByClientRole(clientRoleId);
		if (invoices.isEmpty())
			return List.of();

		// 2) Load all entities for these invoices in one shot
		List<UUID> invoiceIds = invoices.stream().map(InvoiceFlatRow::getInvoiceId).toList();
		List<InvoiceEntityRow> entities = transactionDAO.findEntitiesByInvoiceIds(invoiceIds);

		// 3) Group entities by invoice_id
		Map<UUID, List<InvoiceEntityRow>> entitiesByInvoice = entities.stream()
				.collect(Collectors.groupingBy(InvoiceEntityRow::getInvoiceId));

		// 4) Transform to DTOs with hierarchy
		List<InvoiceResponseDTO> result = new ArrayList<>();
		for (InvoiceFlatRow inv : invoices) {
			InvoiceResponseDTO dto = new InvoiceResponseDTO();
			dto.setInvoiceId(inv.getInvoiceId());
			dto.setInvoiceNumber(inv.getInvoiceNumber());
			dto.setInvoiceDate(inv.getInvoiceDate());
			dto.setTotalAmount(inv.getTotalAmount());
			dto.setSubTotal(inv.getSubTotal());
			dto.setTaxAmount(inv.getTaxAmount());
			dto.setDiscountAmount(inv.getDiscountAmount());

			TransactionInfoDTO tx = new TransactionInfoDTO();
			tx.setTransactionCode(inv.getTransactionCode());
			tx.setClientAgreementId(inv.getClientAgreementId());
			tx.setClientPaymentTransactionId(inv.getClientPaymentTransactionId());
			tx.setTransactionDate(inv.getTransactionDate());
			dto.setTransaction(tx);

			List<InvoiceEntityRow> lines = entitiesByInvoice.getOrDefault(inv.getInvoiceId(), List.of());
			buildHierarchy(dto, lines);

			result.add(dto);
		}
		return result;
	}

	/**
	 * Build hierarchy: - Detect children by parent_invoice_entity_id - Any entity
	 * that has children => treat as BundleDTO - Any entity with parent == null and
	 * no children => standalone item (root-level item)
	 */
	private void buildHierarchy(InvoiceResponseDTO dto, List<InvoiceEntityRow> lines) {
		if (lines.isEmpty())
			return;

		// Index by id, and children list map
		Map<UUID, InvoiceEntityRow> byId = lines.stream()
				.collect(Collectors.toMap(InvoiceEntityRow::getInvoiceEntityId, Function.identity()));

		Map<UUID, List<InvoiceEntityRow>> childrenByParent = new HashMap<>();
		for (InvoiceEntityRow r : lines) {
			if (r.getParentInvoiceEntityId() != null) {
				childrenByParent.computeIfAbsent(r.getParentInvoiceEntityId(), k -> new ArrayList<>()).add(r);
			}
		}

		// Identify roots (parent == null)
		List<InvoiceEntityRow> roots = lines.stream().filter(r -> r.getParentInvoiceEntityId() == null).toList();

		for (InvoiceEntityRow root : roots) {
			List<InvoiceEntityRow> kids = childrenByParent.getOrDefault(root.getInvoiceEntityId(), List.of());
			if (!kids.isEmpty()) {
				// This root is a bundle (has children)
				BundleDTO bundle = toBundle(root);
				for (InvoiceEntityRow ch : kids) {
					bundle.getItems().add(toItem(ch));
				}
				dto.getBundles().add(bundle);
			} else {
				// Standalone item
				dto.getItems().add(toItem(root));
			}
		}

		// (Optional) If there are any entities whose parent is not a root (nested),
		// you can extend this to recursive building. Current model supports 2 levels as
		// per requirement.
	}

	private BundleDTO toBundle(InvoiceEntityRow r) {
		BundleDTO b = new BundleDTO();
		b.setInvoiceEntityId(r.getInvoiceEntityId());
		b.setEntityId(r.getEntityId());
		b.setEntityDescription(r.getEntityDescription());
		b.setQuantity(r.getQuantity());
		b.setUnitPrice(r.getUnitPrice());
		b.setDiscountAmount(r.getDiscountAmount());
		b.setTaxAmount(r.getTaxAmount());
		b.setTotalAmount(r.getTotalAmount());
		return b;
	}

	private InvoiceItemDTO toItem(InvoiceEntityRow r) {
		InvoiceItemDTO it = new InvoiceItemDTO();
		it.setInvoiceEntityId(r.getInvoiceEntityId());
		it.setParentInvoiceEntityId(r.getParentInvoiceEntityId());
		it.setEntityId(r.getEntityId());
		it.setEntityDescription(r.getEntityDescription());
		it.setQuantity(r.getQuantity());
		it.setUnitPrice(r.getUnitPrice());
		it.setDiscountAmount(r.getDiscountAmount());
		it.setTaxAmount(r.getTaxAmount());
		it.setTotalAmount(r.getTotalAmount());
		return it;
	}
}

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.springframework.util.CollectionUtils;
import org.springframework.web.server.ResponseStatusException;

import io.clubone.transaction.request.BillingQuoteFinalizeSpec;
import io.clubone.transaction.security.TenantContext;
import io.clubone.transaction.config.LoadPressureGuard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clubone.transaction.dao.InvoiceDAO;
import io.clubone.transaction.dao.SubscriptionPlanDao;
import io.clubone.transaction.dao.TransactionDAO;
import io.clubone.transaction.helper.AgreementHelper;
import io.clubone.transaction.helper.InvoiceNotificationHelper;
import io.clubone.transaction.billing.quote.BillingQuoteSubscriptionPersistenceService;
import io.clubone.transaction.gl.model.GlPaymentCollectedPayload;
import io.clubone.transaction.gl.service.GlPostingOutboxService;
import io.clubone.transaction.integration.WebhookMembershipPurchasePublisher;
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
	private SubscriptionPlanDao subscriptionPlanDao;

	@Autowired
	private AgreementHelper agreementHelper;
	
	@Autowired
	private InvoiceDAO invoiceDao;
	
	@Autowired
	private InvoiceNotificationHelper invoiceNotificationHelper;

	@Autowired
	private GlPostingOutboxService glPostingOutboxService;

	@Autowired
	private WebhookMembershipPurchasePublisher webhookMembershipPurchasePublisher;

	@Autowired
	private NamedParameterJdbcTemplate jdbc;

	@Autowired
	private LoadPressureGuard loadPressureGuard;

	private final TransactionTemplate finalizePersistTx;

	/** Side-effects (email / billing quote) must not block the finalize HTTP response. */
	private static final ExecutorService FINALIZE_ASYNC = Executors.newVirtualThreadPerTaskExecutor();

	private static final Logger logger = LoggerFactory.getLogger(TransactionServiceImpl.class);

	@Value("${clubone.transaction.finalize.amount-tolerance:0.09}")
	private BigDecimal finalizeAmountTolerance;

	@Value("${clubone.transaction.finalize.partially-paid-status-name:PARTIALLY_PAID}")
	private String partiallyPaidStatusName;

	@Value("${clubone.invoice.initial-status-name:PENDING}")
	private String invoiceInitialStatusName;

	@Autowired
	public TransactionServiceImpl(PlatformTransactionManager transactionManager) {
		this.finalizePersistTx = new TransactionTemplate(transactionManager);
	}

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

		// Step 2: Build TransactionDTO
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

		// Step 5: Create Payment (POST /payment/v1 — include invoiceId for CASH / manual allocation)
		PaymentRequestDTO payment = new PaymentRequestDTO();
		payment.setClientRoleId(request.getClientRoleId());
		payment.setInvoiceId(invoiceId);
		payment.setAmount(request.getTotalAmount());
		payment.setPaymentGatewayCode("MANUAL");
		payment.setPaymentMethodCode("CASH");
		payment.setPaymentTypeCode("CASH");
		payment.setCreatedBy(request.getCreatedBy());
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
		pay.setInvoiceId(req.getInvoiceId());
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

	/**
	 * Hot path for POS settle. Payment HTTP (manual only) stays on the request thread;
	 * DB writes are a short {@link TransactionTemplate}; email + vendor billing-quote
	 * fetch/persist run async after commit so the client is not blocked (~seconds of IO).
	 */
	@Override
	public FinalizeTransactionResponse finalizeTransactionV3(FinalizeTransactionRequest req) {
		final long t0 = System.nanoTime();
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

		final boolean isManual = isManualPaymentGateway(req);

		BigDecimal tolerance = finalizeAmountTolerance != null ? finalizeAmountTolerance.setScale(2, RoundingMode.HALF_UP)
				: new BigDecimal("0.09").setScale(2, RoundingMode.HALF_UP);
		BigDecimal invoiceTotal = nz(invoiceSummary.getTotalAmount()).setScale(2, RoundingMode.HALF_UP);

		/*
		 * Corporate split: one round-trip for existence + balances (was two queries).
		 */
		CorporateAllocationSnapshot corporateSnap = loadCorporateAllocationSnapshot(req.getInvoiceId());
		final boolean corporateSplitInvoice = corporateSnap.hasCorporate();
		BigDecimal memberOutstandingBefore = corporateSnap.memberBalance().setScale(2, RoundingMode.HALF_UP);

		logger.info(
				"[transactions/v3/finalize] step=corporate_split_detection invoiceId={} corporateSplitInvoice={} memberOutstanding={} corporateOutstanding={}",
				req.getInvoiceId(), corporateSplitInvoice, memberOutstandingBefore, corporateSnap.corporateBalance());

		BigDecimal payAmount;
		if (req.getAmountToPayNow() != null) {
			payAmount = req.getAmountToPayNow().setScale(2, RoundingMode.HALF_UP);
		} else {
			payAmount = corporateSplitInvoice ? memberOutstandingBefore : invoiceTotal;
		}

		if (invoiceTotal.compareTo(BigDecimal.ZERO) > 0 && payAmount.compareTo(BigDecimal.ZERO) <= 0) {
			logger.warn(
					"[transactions/v3/finalize] step=amount_validation outcome=reject invoiceId={} reason=non_positive_pay_amount invoiceTotal={} payAmount={}",
					req.getInvoiceId(), invoiceTotal, payAmount);
			return new FinalizeTransactionResponse(req.getInvoiceId(), "UNPAID", null, null,
					"Payment amount must be greater than zero");
		}

		BigDecimal maximumCollectibleNow = corporateSplitInvoice ? memberOutstandingBefore : invoiceTotal;
		if (payAmount.subtract(maximumCollectibleNow).compareTo(tolerance) > 0) {
			logger.warn(
					"[transactions/v3/finalize] step=amount_validation outcome=reject invoiceId={} payAmount={} maximumCollectibleNow={} invoiceTotal={} tolerance={} corporateSplitInvoice={} reason=overpayment",
					req.getInvoiceId(), payAmount, maximumCollectibleNow, invoiceTotal, tolerance,
					corporateSplitInvoice);
			return new FinalizeTransactionResponse(req.getInvoiceId(), "UNPAID", null, null,
					corporateSplitInvoice
							? "Payment amount exceeds outstanding member responsibility"
							: "Payment amount exceeds invoice balance");
		}

		boolean fullPayment = corporateSplitInvoice
				? memberOutstandingBefore.compareTo(BigDecimal.ZERO) == 0
						|| payAmount.add(tolerance).compareTo(memberOutstandingBefore) >= 0
				: invoiceTotal.compareTo(BigDecimal.ZERO) == 0
						|| payAmount.add(tolerance).compareTo(invoiceTotal) >= 0;
		boolean partialPayment = corporateSplitInvoice
				? memberOutstandingBefore.compareTo(BigDecimal.ZERO) > 0 && !fullPayment
				: invoiceTotal.compareTo(BigDecimal.ZERO) > 0 && !fullPayment;

		UUID clientPaymentTransactionId = null;

		if (!isManual) {
			if (payAmount.compareTo(BigDecimal.ZERO) > 0 && req.getClientPaymentTransactionId() == null) {
				logger.warn(
						"[transactions/v3/finalize] step=gateway_payment outcome=reject invoiceId={} reason=missing_client_payment_transaction_id",
						req.getInvoiceId());
				return new FinalizeTransactionResponse(req.getInvoiceId(), "UNPAID", null, null,
						"Missing clientPaymentTransactionId for gateway finalize");
			}
			clientPaymentTransactionId = req.getClientPaymentTransactionId();
		}

		if (payAmount.compareTo(BigDecimal.ZERO) <= 0) {
			clientPaymentTransactionId = isManual ? null : req.getClientPaymentTransactionId();
		} else if (isManual) {
			// Payment HTTP outside the DB TX so we never hold Hikari during vendor latency.
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
		}

		final UUID cptId = clientPaymentTransactionId;
		final BigDecimal payAmountFinal = payAmount;
		final boolean fullPaymentHint = fullPayment;
		final boolean partialPaymentHint = partialPayment;

		FinalizePersistOutcome outcome = loadPressureGuard.withFinalizeDb(() -> finalizePersistTx.execute(status -> {
			boolean partial = partialPaymentHint;
			boolean full = fullPaymentHint;

			if (corporateSplitInvoice && payAmountFinal.compareTo(BigDecimal.ZERO) > 0) {
				applyPaymentToCorporateMemberAllocations(
						req.getInvoiceId(), payAmountFinal, req.getCreatedBy(), tolerance);
			}

			TransactionDTO txn = new TransactionDTO();
			txn.setClientAgreementId(effectiveClientAgreementId);
			txn.setLevelId(req.getLevelId());
			txn.setInvoiceId(req.getInvoiceId());
			txn.setClientPaymentTransactionId(cptId);
			txn.setTransactionDate(Timestamp.from(Instant.now()));
			txn.setCreatedBy(req.getCreatedBy());

			UUID transactionId = transactionDAO.saveTransactionV3(txn);

			// Register after-commit hooks while TX synchronization is active.
			scheduleFinalizeInvoiceNotificationAfterCommit(req, isManual);

			String responseStatusName;
			boolean purchaseCompleted;

			if (corporateSplitInvoice) {
				CorporateAllocationSnapshot balancesAfter = loadCorporateAllocationSnapshot(req.getInvoiceId());
				boolean memberResponsibilityPaid = balancesAfter.memberBalance().compareTo(tolerance) <= 0;
				boolean corporateResponsibilityPaid = balancesAfter.corporateBalance().compareTo(tolerance) <= 0;
				purchaseCompleted = memberResponsibilityPaid;
				boolean organizationBalancePending = memberResponsibilityPaid && !corporateResponsibilityPaid;
				full = memberResponsibilityPaid && corporateResponsibilityPaid;

				if (full) {
					UUID paidStatusId = transactionDAO.findInvoiceStatusIdByName("PAID");
					transactionDAO.updateInvoiceStatusAndPaidFlag(
							req.getInvoiceId(), paidStatusId, true, req.getCreatedBy());
					responseStatusName = "PAID";
					partial = false;
				} else if (organizationBalancePending) {
					UUID partialStatusId = resolvePartialPaymentInvoiceStatusId();
					transactionDAO.updateInvoiceStatusAndPaidFlag(
							req.getInvoiceId(), partialStatusId, false, req.getCreatedBy());
					responseStatusName = "PAID";
					partial = false;
				} else {
					UUID pendingStatusId = transactionDAO.findInvoiceStatusIdByName(invoiceInitialStatusName);
					transactionDAO.updateInvoiceStatusAndPaidFlag(
							req.getInvoiceId(), pendingStatusId, false, req.getCreatedBy());
					responseStatusName = invoiceInitialStatusName;
					partial = true;
				}
				logger.info(
						"[transactions/v3/finalize] step=invoice_update branch=corporate_split invoiceId={} responseStatus={} memberBalance={} corporateBalance={} purchaseCompleted={}",
						req.getInvoiceId(), responseStatusName, balancesAfter.memberBalance(),
						balancesAfter.corporateBalance(), purchaseCompleted);
			} else if (partial) {
				purchaseCompleted = false;
				UUID partialStatusId = resolvePartialPaymentInvoiceStatusId();
				transactionDAO.updateInvoiceStatusAndPaidFlag(
						req.getInvoiceId(), partialStatusId, false, req.getCreatedBy());
				responseStatusName = resolvePartialPaymentInvoiceStatusName();
			} else {
				purchaseCompleted = true;
				UUID paidStatusId = transactionDAO.findInvoiceStatusIdByName("PAID");
				transactionDAO.updateInvoiceStatusAndPaidFlag(
						req.getInvoiceId(), paidStatusId, true, req.getCreatedBy());
				responseStatusName = "PAID";
			}

			if (purchaseCompleted) {
				transactionDAO.activateAgreementAndClientStatusForInvoice(req.getInvoiceId(), req.getCreatedBy());

				// Projection triggers only NOTIFY (migration 060); refresh explicitly so list status_display updates.
				final UUID clientRoleIdForProj = req.getClientRoleId() != null
						? req.getClientRoleId()
						: transactionDAO.findClientRoleIdByInvoiceId(req.getInvoiceId()).orElse(null);
				if (clientRoleIdForProj != null) {
					runAfterCommitAsync(() -> transactionDAO.refreshClientDashboardProjection(clientRoleIdForProj));
				}

				if (effectiveClientAgreementId != null) {
					// Fully async: agreement lookup + webhook HTTP never touch the finalize request thread.
					final UUID scheduledTxnId = transactionId;
					runAfterCommitAsync(() -> webhookMembershipPurchasePublisher.publishAfterPaymentSuccess(
							req.getInvoiceId(),
							scheduledTxnId,
							cptId,
							req.getClientRoleId(),
							effectiveClientAgreementId,
							req.getLevelId(),
							payAmountFinal,
							req.getCreatedBy()));
				}

				if (!CollectionUtils.isEmpty(req.getBillingQuoteFinalizeSpecs())) {
					scheduleBillingQuoteFinalizeAfterCommit(
							req.getBillingQuoteFinalizeSpecs(),
							transactionId,
							effectiveClientAgreementId,
							req.getInvoiceId(),
							cptId,
							req.getCreatedBy());
				}
			}

			enqueueGlPaymentCollected(req, cptId, transactionId, payAmountFinal);

			return new FinalizePersistOutcome(transactionId, responseStatusName, purchaseCompleted, partial);
		}));

		if (outcome == null) {
			throw new IllegalStateException("Finalize persist returned null outcome");
		}

		String responseMessage;
		if (corporateSplitInvoice && outcome.purchaseCompleted()) {
			responseMessage = "";
		} else if (outcome.partialPayment()) {
			responseMessage = "Partial payment recorded";
		} else {
			responseMessage = "";
		}

		logger.info(
				"[transactions/v3/finalize] step=complete invoiceId={} invoiceStatus={} transactionId={} clientPaymentTransactionId={} partialPayment={} elapsedMs={}",
				req.getInvoiceId(), outcome.responseStatusName(), outcome.transactionId(), cptId,
				outcome.partialPayment(), (System.nanoTime() - t0) / 1_000_000L);

		return new FinalizeTransactionResponse(
				req.getInvoiceId(),
				outcome.responseStatusName(),
				cptId,
				outcome.transactionId(),
				responseMessage);
	}

	private record FinalizePersistOutcome(
			UUID transactionId,
			String responseStatusName,
			boolean purchaseCompleted,
			boolean partialPayment) {
	}


	private CorporateAllocationSnapshot loadCorporateAllocationSnapshot(UUID invoiceId) {
		if (invoiceId == null) {
			return CorporateAllocationSnapshot.zero();
		}
		return jdbc.query(
				"""
				SELECT
				    COALESCE(BOOL_OR(UPPER(TRIM(ppr.code)) = 'CORPORATE'), FALSE) AS has_corporate,
				    COALESCE(SUM(CASE
				        WHEN UPPER(TRIM(ppr.code)) = 'MEMBER' THEN ipa.balance_amount
				        ELSE 0
				    END), 0) AS member_balance,
				    COALESCE(SUM(CASE
				        WHEN UPPER(TRIM(ppr.code)) = 'CORPORATE' THEN ipa.balance_amount
				        ELSE 0
				    END), 0) AS corporate_balance
				FROM transactions.invoice_payment_allocation ipa
				JOIN agreements.lu_agreement_group_payer_role ppr
				  ON ppr.payer_role_id = ipa.payer_role_id
				 AND ppr.is_active = TRUE
				WHERE ipa.invoice_id = :invoiceId
				  AND ipa.is_active = TRUE
				""",
				new MapSqlParameterSource().addValue("invoiceId", invoiceId),
				rs -> rs.next()
						? new CorporateAllocationSnapshot(
								rs.getBoolean("has_corporate"),
								nz(rs.getBigDecimal("member_balance")).setScale(2, RoundingMode.HALF_UP),
								nz(rs.getBigDecimal("corporate_balance")).setScale(2, RoundingMode.HALF_UP))
						: CorporateAllocationSnapshot.zero());
	}

	private void applyPaymentToCorporateMemberAllocations(
			UUID invoiceId,
			BigDecimal paymentAmount,
			UUID actorId,
			BigDecimal tolerance) {

		BigDecimal remaining = nz(paymentAmount).setScale(2, RoundingMode.HALF_UP);
		List<MemberAllocationBalance> rows = jdbc.query(
				"""
				SELECT
				    ipa.invoice_payment_allocation_id,
				    ipa.balance_amount
				FROM transactions.invoice_payment_allocation ipa
				JOIN agreements.lu_agreement_group_payer_role ppr
				  ON ppr.payer_role_id = ipa.payer_role_id
				 AND ppr.is_active = TRUE
				WHERE ipa.invoice_id = :invoiceId
				  AND ipa.is_active = TRUE
				  AND UPPER(TRIM(ppr.code)) = 'MEMBER'
				  AND ipa.balance_amount > 0
				ORDER BY
				    CASE WHEN ipa.collection_mode_code = 'IMMEDIATE' THEN 0 ELSE 1 END,
				    ipa.due_date NULLS LAST,
				    ipa.created_on,
				    ipa.invoice_payment_allocation_id
				FOR UPDATE OF ipa
				""",
				new MapSqlParameterSource().addValue("invoiceId", invoiceId),
				(rs, rowNum) -> new MemberAllocationBalance(
						rs.getObject("invoice_payment_allocation_id", UUID.class),
						nz(rs.getBigDecimal("balance_amount")).setScale(2, RoundingMode.HALF_UP)));

		if (rows.isEmpty() && remaining.compareTo(tolerance) > 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Corporate split invoice has no outstanding MEMBER payment allocation");
		}

		for (MemberAllocationBalance row : rows) {
			if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
				break;
			}
			BigDecimal applied = remaining.min(row.balanceAmount());
			jdbc.update(
					"""
					UPDATE transactions.invoice_payment_allocation
					SET
					    paid_amount = paid_amount + :applied,
					    balance_amount = GREATEST(
					        total_amount - (paid_amount + :applied) + refunded_amount,
					        0
					    ),
					    allocation_status_code = CASE
					        WHEN total_amount - (paid_amount + :applied) + refunded_amount <= :tolerance
					            THEN 'PAID'
					        WHEN paid_amount + :applied > 0
					            THEN 'PARTIAL'
					        ELSE allocation_status_code
					    END,
					    modified_on = CURRENT_TIMESTAMP,
					    modified_by = :actorId
					WHERE invoice_payment_allocation_id = :allocationId
					""",
					new MapSqlParameterSource()
							.addValue("applied", applied)
							.addValue("tolerance", tolerance)
							.addValue("actorId", actorId)
							.addValue("allocationId", row.allocationId()));
			remaining = remaining.subtract(applied).setScale(2, RoundingMode.HALF_UP);
		}

		if (remaining.compareTo(tolerance) > 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Payment amount exceeds outstanding member responsibility by " + remaining);
		}
	}

	private record MemberAllocationBalance(UUID allocationId, BigDecimal balanceAmount) {
	}

	private record CorporateAllocationSnapshot(
			boolean hasCorporate, BigDecimal memberBalance, BigDecimal corporateBalance) {
		private static CorporateAllocationSnapshot zero() {
			return new CorporateAllocationSnapshot(
					false,
					BigDecimal.ZERO.setScale(2),
					BigDecimal.ZERO.setScale(2));
		}
	}

	private void enqueueGlPaymentCollected(FinalizeTransactionRequest req, UUID clientPaymentTransactionId,
			UUID transactionId, BigDecimal payAmount) {
		if (clientPaymentTransactionId == null || payAmount == null || payAmount.signum() <= 0) {
			return;
		}
		try {
			GlPaymentCollectedPayload payload = GlPaymentCollectedPayload.builder()
					.clientPaymentTransactionId(clientPaymentTransactionId)
					.transactionId(transactionId)
					.invoiceId(req.getInvoiceId())
					.amount(payAmount.setScale(2, RoundingMode.HALF_UP))
					.levelId(req.getLevelId())
					.paymentCurrencyTypeId(req.getPaymentGatewayCurrencyTypeId())
					.paymentMethodCode(req.getPaymentMethodCode())
					.collectedAt(Instant.now())
					.createdBy(req.getCreatedBy())
					.build();
			glPostingOutboxService.enqueuePaymentCollected(payload);
		} catch (Exception ex) {
			logger.error(
					"[transactions/v3/finalize] step=gl_posting_enqueue outcome=error invoiceId={} cpt={} message={}",
					req.getInvoiceId(), clientPaymentTransactionId, ex.getMessage(), ex);
		}
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

	private String resolvePartialPaymentInvoiceStatusName() {
		if (partiallyPaidStatusName != null && !partiallyPaidStatusName.isBlank()
				&& transactionDAO.tryFindInvoiceStatusIdByName(partiallyPaidStatusName.trim()).isPresent()) {
			return partiallyPaidStatusName.trim();
		}
		return invoiceInitialStatusName;
	}

	/**
	 * Never block the HTTP response on notification HTTP. Always after-commit + async.
	 */
	private void scheduleFinalizeInvoiceNotificationAfterCommit(FinalizeTransactionRequest req, boolean isManual) {
		runAfterCommitAsync(() -> {
			logger.info("[transactions/v3/finalize] step=notification invoiceId={} sending_email=true async=true",
					req.getInvoiceId());
			sendFinalizeInvoiceNotification(req, isManual);
		});
	}

	/**
	 * Vendor quote fetch + subscription persist historically dominated finalize latency.
	 * Safe to run after commit: API response does not include quote payloads.
	 */
	private void scheduleBillingQuoteFinalizeAfterCommit(
			List<BillingQuoteFinalizeSpec> specs,
			UUID transactionId,
			UUID clientAgreementId,
			UUID invoiceId,
			UUID clientPaymentTransactionId,
			UUID createdBy) {
		final List<BillingQuoteFinalizeSpec> specsCopy = List.copyOf(specs);
		runAfterCommitAsync(() -> {
			try {
				logger.info(
						"[transactions/v3/finalize] step=billing_quote_fetch start invoiceId={} specCount={} transactionId={} async=true",
						invoiceId, specsCopy.size(), transactionId);
				List<BillingQuoteLineItemsResponse> quoteLineItems = subscriptionPlanHelper
						.fetchQuoteLineItems(specsCopy);
				Optional<UUID> cpmHint = subscriptionPlanDao.findClientPaymentMethodIdByTransactionId(transactionId);
				if (cpmHint.isEmpty() && clientPaymentTransactionId != null) {
					cpmHint = subscriptionPlanDao
							.findClientPaymentMethodIdByClientPaymentTransactionId(clientPaymentTransactionId);
				}
				billingQuoteSubscriptionPersistenceService.persistFromQuoteResponses(
						quoteLineItems,
						transactionId,
						clientAgreementId,
						invoiceId,
						clientPaymentTransactionId,
						createdBy,
						true,
						cpmHint.orElse(null));
				logger.info(
						"[transactions/v3/finalize] step=billing_quote_persist outcome=ok invoiceId={} responseCount={}",
						invoiceId, quoteLineItems.size());
			} catch (Exception e) {
				logger.error(
						"[transactions/v3/finalize] step=billing_quote_async outcome=error invoiceId={} message={}",
						invoiceId, e.getMessage(), e);
			}
		});
	}

	private void runAfterCommitAsync(Runnable task) {
		final TenantContext tenantCtx = TenantContext.get();
		Runnable async = () -> FINALIZE_ASYNC.execute(() -> loadPressureGuard.withFinalizeAsync(() -> {
			TenantContext previous = TenantContext.get();
			try {
				if (tenantCtx != null) {
					TenantContext.set(tenantCtx);
				}
				task.run();
			} catch (Exception ex) {
				logger.warn("[transactions/v3/finalize] step=after_commit_async outcome=error message={}",
						ex.getMessage(), ex);
			} finally {
				if (previous != null) {
					TenantContext.set(previous);
				} else {
					TenantContext.clear();
				}
			}
		}));
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					async.run();
				}
			});
		} else {
			async.run();
		}
	}

	private void sendFinalizeInvoiceNotification(FinalizeTransactionRequest req, boolean isManual) {
		String methodType = deriveNotificationPaymentMethodType(req, isManual);
		String brand = blankToNull(req.getPaymentInstrumentBrand());
		String last4 = blankToNull(req.getPaymentInstrumentLast4());
		String auth = blankToNull(req.getPaymentAuthorizationReference());
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
